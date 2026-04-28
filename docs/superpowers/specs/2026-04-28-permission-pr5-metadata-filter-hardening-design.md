# PR5 Design — Metadata Filter Contract Hardening

- **日期**：2026-04-28
- **状态**：Draft
- **路线图**：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B PR5
- **前置 PR**：PR1（controller thinning）/ PR2（KbAccessService 退役）/ PR3（calculator + ThreadLocal guard）/ **PR4（RetrievalScope 全链路，PR #27 已合并 commit `52e43c5`）**
- **作用范围**：`rag/core/retrieve/filter/**`、`rag/core/retrieve/OpenSearchRetrieverService`、`rag/core/retrieve/channel/**` 三处契约测试
- **零业务增量**：纯 contract hardening，不改产品语义

---

## 0. 背景与命题修订

### 0.1 路线图原描述与代码现实的 gap

路线图原文（修订前）：
> commit 1: `OpenSearchRetrieverService` 强制带 `terms(kb_id) + range(security_level)`
> commit 2: 添加 `SecurityLevelFilterEnforcedTest`
> commit 3: 清理 `SearchContext` 冗余字段

经 PR4 合并后核对，实际情况：

| 路线图假设 | 代码现实（main 2026-04-28） | 修订 |
|---|---|---|
| `OpenSearchRetrieverService` 是强制注入点 | `OpenSearchRetrieverService:100-140` 只持有 `collectionName` + `metadataFilters`，无可靠 `kbId` 来源；要在它内部"强制注入 kb_id"必须反查或猜，会把权限上下文泄到 storage 层 | 强制注入下沉到 **builder 层**；retriever 只做 fail-fast 守卫 |
| OpenSearch 不支持 `terms` / `range` | `renderFilter` (L217-235) 已支持 `FilterOp.IN → terms` 与 `FilterOp.LTE_OR_MISSING → range OR missing`，无需改 query builder | render 层就位 |
| `range(security_level)` 缺失 | `DefaultMetadataFilterBuilder:43-46` 已为每个 KB 输出 `LTE_OR_MISSING`，仅 `AccessScope.All` / `kbSecurityLevels` 缺 entry 的路径会被跳过 | spec 决议：All 路径补 no-op range（`Integer.MAX_VALUE`） |
| `terms(kb_id)` 缺失 | `DefaultMetadataFilterBuilder` 当前**完全不输出** `kb_id` filter；检索靠 OpenSearch index = collectionName 物理隔离 | **PR5 真正缺口** |
| 检索改为单次多 KB `terms` fan-out | 当前 per-KB 调用结构：`MultiChannelRetrievalEngine:79`（单 KB 路径）/ `VectorGlobalSearchChannel:192`（per-KB 循环）/ `IntentParallelRetriever:63`（per-intent → per-KB）。fan-out 重构会触及 `collectionName` / score cap / 并行错误隔离 | PR5 不做结构重构；用 `IN singleton List.of(kbId)` 兑现 `terms(metadata.kb_id)` 表述 |

### 0.2 PR5 的真实命题

> **把"`metadata.kb_id` 软隔离"和"`metadata.security_level` ≤ ceiling"这两条 query shape invariant 升级为契约——builder 必出，OpenSearch 缺则 fail-fast，三层契约测试守门。**

不是结构性重构，不是性能优化，不是新增过滤维度。**只是把现有过滤约定从"信任调用约定"升级为"代码 + 测试双锁"。**

### 0.3 与 PR3/PR4 的衔接

- PR3 给出了 `KbAccessSubject` / `KbAccessCalculator` / current-user-only `KbReadAccessPort`
- PR4 给出了 `RetrievalScope(accessScope, kbSecurityLevels, targetKbId)` record，沿 `RAGChatServiceImpl → MultiChannelRetrievalEngine → SearchContext → MetadataFilterBuilder` 一次贯通
- PR5 在 builder 出口处加一刀，让"builder 永远输出 kb_id + security_level"成为静态契约；下游 OpenSearch 拼 DSL 自动带上

---

## 1. 不变量（PR5 起约束所有后续 PR）

### 1.1 Query Shape Invariants（PR5 新增）

- **QSI-1（`kb_id` 软隔离）**：所有走 `DefaultMetadataFilterBuilder.build(ctx, kbId)` 的非空 `kbId` 调用，输出 filter 必含 `MetadataFilter(VectorMetadataFields.KB_ID, FilterOp.IN, List.of(kbId))`
- **QSI-2（`security_level` 天花板）**：同上 builder 调用输出 filter 必含 `MetadataFilter(VectorMetadataFields.SECURITY_LEVEL, FilterOp.LTE_OR_MISSING, level)`：
  - 若 `ctx.kbSecurityLevels` 含 `kbId` entry → 用 entry 值
  - 若不含 entry（系统态 / `AccessScope.All` 路径）→ 用 `Integer.MAX_VALUE`（no-op range，仍出现在 DSL 中以让契约绝对化）
- **QSI-3（OpenSearch 缺失守卫）**：`OpenSearchRetrieverService.doSearch` 进入查询前，`metadataFilters` 不含 **同时具备** `kb_id` IN filter **和** `security_level` range filter 时 fail-fast 抛 `IllegalStateException`，绝不静默查询
- **QSI-4（per-KB 调用对齐）**：`MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` / `IntentParallelRetriever` 等通道传入 builder 的 `kbId` 与传入 retriever 的 `collectionName` 来自**同一 KB**——builder 输出的 `kb_id` filter 值必须与查询的 collection 对齐

### 1.2 反模式清单（PR5 起静态守门）

- ❌ 直接 `new MetadataFilter(...)` 在 channel / engine / retriever 层（builder 是唯一构造入口；测试与 builder 内部除外）
- ❌ `OpenSearchRetrieverService.doSearch` 用 try/catch 把 `IllegalStateException` 吞成空 list（fail-fast 必须穿透）
- ❌ `kb_id` filter 用 `EQ` 而非 `IN`（保留 `IN` 是为后续可能的 fan-out 留接口稳定）
- ❌ 任何路径输出"只含 `security_level`"或"只含 `kb_id`"的不完整 filter set（QSI-1 + QSI-2 是 AND 关系）

### 1.3 显式不在 PR5 范围

- per-KB 调用结构改为单次多 KB fan-out（`MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` 重写）
- `LoginUser.maxSecurityLevel` 在管理决策路径的清理（`KbAccessSubjectFactoryImpl:44` / `KbAccessCalculator:132` / `RoleServiceImpl:485`）→ 阶段 C / PR6
- Milvus / pgvector 后端 metadata filter 补齐（启动期 fail-fast 已挡，留 SL-1 backlog）
- `SearchContext.metadata` 字段清理 → 见 §6 决议：spec 阶段 caller 审计完成，列为 c5 可选

---

## 2. 影响面（按文件）

### 2.1 必改

| 文件 | 改动 | 性质 |
|---|---|---|
| `rag/core/retrieve/filter/DefaultMetadataFilterBuilder.java` | 永远输出 `kb_id IN [kbId]`；`level == null` 时输出 `security_level LTE_OR_MISSING Integer.MAX_VALUE` | 行为变更 |
| `rag/core/retrieve/OpenSearchRetrieverService.java` | `doSearch` 入口加 `enforceFilterContract(metadataFilters)` 检查；放在现有 try 之前，或显式不吞 `IllegalStateException` | 行为变更 + 防御 |

### 2.2 必加测试

| 测试 | 锁住的契约 |
|---|---|
| `DefaultMetadataFilterBuilderTest`（已存在则扩，否则新建） | QSI-1 + QSI-2 builder 输出层 |
| `OpenSearchRetrieverServiceFilterContractTest`（新建） | QSI-3 fail-fast 行为 + DSL JSON 含 `terms metadata.kb_id` 与 `range metadata.security_level` 字符串契约 |
| `MetadataFilterChannelAlignmentTest`（新建） | QSI-4 per-KB 调用 → builder 输出 kb_id filter 严格对齐（覆盖 `MultiChannelRetrievalEngine` 单 KB 路径 + `VectorGlobalSearchChannel` 全局通道 + `IntentParallelRetriever` 意图通道） |

### 2.3 可能改

| 文件 | 条件 |
|---|---|
| `OpenSearchVectorStoreService.indexDocumentChunks` | 若发现写入端 metadata 缺 `kb_id` / `security_level` 字段，PR5 范围内**不补**——backlog 处理。PR5 只关心读端契约 |
| `SearchContext.java`（`metadata` 字段删除） | c5 可选，仅当审计证明零 caller、零测试依赖、零未来语义计划时执行；否则留后续 cleanup PR |

### 2.4 不改

- `RetrievalScope` / `RetrievalScopeBuilder`（PR4 出口）
- `MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` / `IntentParallelRetriever` 调用结构（保持 per-KB）
- `MilvusVectorStoreService` / `PgVectorStoreService`（启动期 fail-fast 已挡，PR5 不补）
- `AuthzPostProcessor`（后置纵深防御 order=0，PR5 不弱化也不增强）

---

## 3. 详细设计

### 3.1 `DefaultMetadataFilterBuilder` 强化

**当前实现**（48 行）：
```java
public List<MetadataFilter> build(SearchContext ctx, String kbId) {
    if (kbId == null || ctx.getKbSecurityLevels() == null) {
        return Collections.emptyList();   // ← 缺口：返空 = 无任何 filter
    }
    Integer level = ctx.getKbSecurityLevels().get(kbId);
    if (level == null) {
        return Collections.emptyList();   // ← 缺口：同上
    }
    return List.of(new MetadataFilter(SECURITY_LEVEL, LTE_OR_MISSING, level));
    //                ↑ 缺口：没有 kb_id filter
}
```

**PR5 后语义**：
```java
public List<MetadataFilter> build(SearchContext ctx, String kbId) {
    if (kbId == null) {
        // kb_id 软隔离的前提是知道当前查的是哪个 KB；
        // null 通常意味着调用方走错了路径，由 OpenSearchRetrieverService 的 QSI-3 守卫接住
        return Collections.emptyList();
    }
    int level = resolveLevel(ctx, kbId);   // null/missing → Integer.MAX_VALUE
    return List.of(
        new MetadataFilter(KB_ID, FilterOp.IN, List.of(kbId)),
        new MetadataFilter(SECURITY_LEVEL, FilterOp.LTE_OR_MISSING, level)
    );
}

private static int resolveLevel(SearchContext ctx, String kbId) {
    Map<String, Integer> map = ctx.getKbSecurityLevels();
    if (map == null) return Integer.MAX_VALUE;
    Integer v = map.get(kbId);
    return v != null ? v : Integer.MAX_VALUE;
}
```

**设计点**：
- `kb_id` 用 `FilterOp.IN + List.of(kbId)`（singleton list）。OpenSearch 渲染为 `terms(metadata.kb_id, [kbId])`，与路线图原表述兑现；同时保留未来 fan-out 重构时的接口稳定（多 KB 时直接换成多元素 list，builder 签名不动）
- `security_level` 在 missing/null 时输出 `Integer.MAX_VALUE`：
  - 非授权决策——授权由 `AccessScope` + `AuthzPostProcessor` 兜底
  - 是 **query shape invariant**——让契约测试可以静态断言"所有 OpenSearch DSL 都同时含 kb_id 和 security_level filter"
  - `Integer.MAX_VALUE` = `2^31 - 1`，远超任何业务上可能的安全等级（设计上 0/1/2 三档），渲染为 `range.lte = 2147483647`，等价 no-op
- `kbId == null` 仍返空：让 `OpenSearchRetrieverService` 的 QSI-3 守卫去拒绝，避免在 builder 层重复抛异常造成栈耦合

### 3.2 `OpenSearchRetrieverService` 缺失守卫

**当前结构**：
```java
private List<RetrievedChunk> doSearch(...) {
    try {
        String queryJson = pipelineReady ? buildHybridQuery(...) : buildKnnOnlyQuery(...);
        // ... HTTP call ...
    } catch (Exception e) {
        log.error("OpenSearch retrieval failed for index: {}", collectionName, e);
        return List.of();   // ← 风险：会吞掉契约违规的 IllegalStateException
    }
}
```

**PR5 后**：
```java
private List<RetrievedChunk> doSearch(String collectionName, String query, float[] vector,
                                      int topK, List<MetadataFilter> metadataFilters) {
    enforceFilterContract(metadataFilters, collectionName);   // ← 在 try 之前
    try {
        // ... 原逻辑 ...
    } catch (IllegalStateException e) {
        throw e;   // ← 显式不吞契约违规（即使有人未来把守卫挪进 try）
    } catch (Exception e) {
        log.error("OpenSearch retrieval failed for index: {}", collectionName, e);
        return List.of();
    }
}

private static void enforceFilterContract(List<MetadataFilter> filters, String collectionName) {
    boolean hasKbId = filters != null && filters.stream()
        .anyMatch(f -> VectorMetadataFields.KB_ID.equals(f.field()) && f.op() == FilterOp.IN);
    boolean hasSecurityLevel = filters != null && filters.stream()
        .anyMatch(f -> VectorMetadataFields.SECURITY_LEVEL.equals(f.field())
                   && f.op() == FilterOp.LTE_OR_MISSING);
    if (!hasKbId || !hasSecurityLevel) {
        throw new IllegalStateException(
            "OpenSearch filter contract violated for collection=" + collectionName
            + ", hasKbIdFilter=" + hasKbId + ", hasSecurityLevelFilter=" + hasSecurityLevel
            + ". All retrieval must go through DefaultMetadataFilterBuilder.");
    }
}
```

**设计点**：
- 守卫**放在 try 外**，让 `IllegalStateException` 直接抛到调用栈（`MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel`），不被现有 catch-all 转成空 list
- 加显式 `catch (IllegalStateException e) { throw e; }` 防御未来重构：万一有人把守卫挪进 try，至少不会被 `catch (Exception)` 吞掉
- 错误信息打印 collection 与具体缺失项，方便定位"哪个调用链漏了 builder"
- 守卫只检查 op + field 组合，不验值——值的合法性由 builder 保证；retriever 不重复校验

### 3.3 `VectorMetadataFields.KB_ID` 常量复用

`VectorMetadataFields` 已是字段名常量单一真相源（bootstrap CLAUDE.md 明确）。PR5 不新增字段名常量，只在 builder + 守卫中复用 `KB_ID` / `SECURITY_LEVEL`。

---

## 4. 测试设计（三层契约）

### 4.1 Layer 1 — Builder 单元测试（QSI-1 + QSI-2）

**文件**：`bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/filter/DefaultMetadataFilterBuilderTest.java`（新建或扩展）

**用例矩阵**：
| 场景 | ctx.kbSecurityLevels | kbId | 期望 filter |
|---|---|---|---|
| 普通 user | `{kb-1: 1, kb-2: 2}` | `kb-1` | `[kb_id IN [kb-1], security_level LTE_OR_MISSING 1]` |
| missing entry | `{kb-1: 1}` | `kb-2` | `[kb_id IN [kb-2], security_level LTE_OR_MISSING MAX_VALUE]` |
| null map（系统态） | `null` | `kb-1` | `[kb_id IN [kb-1], security_level LTE_OR_MISSING MAX_VALUE]` |
| level=0（最严） | `{kb-1: 0}` | `kb-1` | `[kb_id IN [kb-1], security_level LTE_OR_MISSING 0]` |
| kbId=null | `{kb-1: 1}` | `null` | `[]`（由 retriever QSI-3 接住） |

### 4.2 Layer 2 — OpenSearch render 契约测试（QSI-3 + DSL shape）

**文件**：`bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverServiceFilterContractTest.java`（新建）

**用例**：
1. `doSearch` 传入合法 filter set → 不抛；mock OpenSearch client 返回空 hits 即可（不验业务结果）
2. `doSearch` 传入空 list / null → 抛 `IllegalStateException`，**不**返空 list
3. `doSearch` 传入只含 `kb_id` 的 filter → 抛 `IllegalStateException`
4. `doSearch` 传入只含 `security_level` 的 filter → 抛 `IllegalStateException`
5. **DSL shape 静态断言**：调 `buildHybridQuery` / `buildKnnOnlyQuery`（必要时把 private 改 package-private 供测试可见，或反射），验证生成的 JSON 字符串包含 `"terms"` + `"metadata.kb_id"` 与 `"range"` + `"metadata.security_level"` 子串
6. **吞异常防御**：构造一个会让 OpenSearch HTTP 调用抛 `RuntimeException` 的 mock，验证 `IllegalStateException` 路径不会被 `catch (Exception)` 吞——通过故意把 enforce 挪进 try 的"反例分支"测试（如果架构允许）；或简化为：直接验证 `IllegalStateException` 没有被转成空 list

### 4.3 Layer 3 — 通道级对齐测试（QSI-4）

**文件**：`bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/MetadataFilterChannelAlignmentTest.java`（新建）

**核心断言**：通道每次调用 `retrieverService.retrieve(req)` 时：
- `req.collectionName == kbMetadataReader.getCollectionName(kbId)`
- `req.metadataFilters` 中 `kb_id` filter 的 value 单元素 == 传入 builder 的 `kbId`

**覆盖通道**：
1. `MultiChannelRetrievalEngine` 单 KB 路径（`AccessScope.Ids` 单元素）
2. `VectorGlobalSearchChannel.search` per-KB 循环
3. `IntentParallelRetriever` per-intent → per-KB 二维循环

**实现思路**：mock `RetrieverService`，捕获每次 `retrieve` 调用的 `RetrieveRequest`，断言 `collectionName` 与 `metadataFilters[kb_id].value[0]` 来自同一 KB。

---

## 5. AccessScope.All 路径的 security_level 决议

**决议**：走 no-op range（`Integer.MAX_VALUE`），让 QSI-2 成为绝对契约。

**理由**：
- **契约绝对化**：无论 `AccessScope.All`（系统态/SUPER_ADMIN）还是普通 user，OpenSearch DSL 都必带 `range(metadata.security_level)`，契约测试可以"任何 retrieval DSL 都包含此子串"做静态断言
- **无授权弱化**：真实授权来自 `AccessScope.All` 的全量放行 + 后置 `AuthzPostProcessor` 校验。`Integer.MAX_VALUE` 的 range 在数据上等价 no-op（业务等级 0/1/2 远低于 MAX）
- **防御回归**：如果未来有人意外用 `Integer.MAX_VALUE` 之外的"大值"绕过，contract test 会发现；no-op range 的存在让"应该过滤但被绕过"的隐患最小
- **代价低**：每次查询多一个 range 子句，OpenSearch 对此优化良好（filter cache），性能影响可忽略

**显式标记**：在 `DefaultMetadataFilterBuilder.resolveLevel` 加 javadoc 注释说明"`Integer.MAX_VALUE` 是 query shape invariant，不是授权决策——授权由 AccessScope 与 AuthzPostProcessor 负责"。

---

## 6. SearchContext caller 审计 → c5 决议

**审计结果**（grep `getMetadata` 在 `rag/` 包内）：

| 文件 | 用途 | 关联 SearchContext.metadata？ |
|---|---|---|
| `MilvusVectorStoreService:215` | `chunk.getMetadata()`（VectorChunk 上的字段） | ❌ 不相关 |
| `OpenSearchVectorStoreService:229` | 同上 | ❌ 不相关 |
| `PgVectorStoreService:114` | 同上 | ❌ 不相关 |
| `SearchContext.metadata` 字段本身 | builder 写入但**无任何 getter caller** | 🟡 候选清理 |

**决议**：
- **c5 可选执行**：如果 c1-c4 review 顺利、CI 全绿，c5 删除 `SearchContext.metadata` 字段 + builder 方法
- **保留条件**：任何 review 反馈说"未来要复用 metadata 装别的"→ 不删
- **不让 c5 影响主线**：c1-c4 落完即可发 PR；c5 单独 commit，被否决就剥离

---

## 7. Commit 序列（草案，与 plan 对齐）

```
PR5 — Metadata Filter Contract Hardening

c1: DefaultMetadataFilterBuilder 强制输出 kb_id (FilterOp.IN singleton) + security_level no-op
    + Layer 1 builder 单元测试（5 个矩阵用例）

c2: OpenSearchRetrieverService.doSearch 加 enforceFilterContract + 显式 IllegalStateException 抛出
    + Layer 2 OpenSearchRetrieverServiceFilterContractTest（4 个守卫用例 + DSL shape 断言）

c3: Layer 3 通道对齐测试 MetadataFilterChannelAlignmentTest
    （MultiChannelRetrievalEngine / VectorGlobalSearchChannel / IntentParallelRetriever 三处）

c4: PR5 守门脚本 docs/dev/verification/permission-pr5-filter-contract.sh
    + ArchUnit 规则：禁止 channel/engine/retriever 直接 new MetadataFilter（builder + test 白名单）
    + S5/S6 smoke 路径文档（与 PR4 smoke 对齐：普通用户 / 系统态 / SUPER_ADMIN 三个回归）

c5（可选）: SearchContext.metadata 字段清理（如 review 通过）
```

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 守卫过严导致已有路径回归 | 先在 builder 端落 c1（向后兼容：filter 多了不少了），跑 smoke；再落 c2 守卫——任何漏的路径会在 c2 立刻暴露 |
| `Integer.MAX_VALUE` 误传到业务比较 | builder javadoc 明确"非授权决策"；契约测试断言渲染出的 DSL 是 `range.lte = 2147483647`（数值上等价 no-op） |
| `enforceFilterContract` 被 `catch (Exception)` 吞 | 守卫放 try 外 + 显式 `catch (IllegalStateException e) { throw e; }` 防御重构 |
| 静态 `new MetadataFilter` 出现在 channel 里 | ArchUnit 规则 + grep 守门；测试白名单 |
| OpenSearch admin 工具方法误触发守卫 | 守卫只在 `doSearch` 入口，`OpenSearchVectorStoreAdmin` 等管理工具不走此路径，天然隔离 |
| Milvus / pgvector 后端不被 PR5 覆盖 | 启动期 `RagVectorTypeValidator` 已 fail-fast，生产只跑 OpenSearch；PR5 不补 Milvus/pg，留 SL-1 backlog |
| **【发布阻塞项】OpenSearch 历史数据缺 `metadata.kb_id`** | `OpenSearchVectorStoreService:238` 当前写路径已显式写 `metadata.kb_id`；但若生产 OpenSearch 存在写路径生效之前的历史 chunk（`metadata.kb_id` 字段缺失），c1 落地后 `terms(metadata.kb_id, [kbId])` 会把这些文档过滤为不可见。**这不是 Milvus/pg 的 SL-1 backlog，是 OpenSearch 生产数据兼容性风险**，发现非零就是发布阻塞项。**部署前必须**：对每个 KB 索引运行 `must_not exists metadata.kb_id` count 查询；命中数 == 0 才能合并 PR5；若非零，先做 reindex / backfill 把 `metadata.kb_id` 补全，再合 PR5。核查命令与口径见 `permission-pr5-smoke.md` S5b |

---

## 9. 决策记录

- **为什么不在 retriever 层强制注入 kb_id？** retriever 只持有 `collectionName + metadataFilters`，没有可靠 `kbId` 来源；要内部反查就要把 KbMetadataReader port 注入到 retriever，把权限上下文泄到 storage 层，边界变差。强制注入留 builder（已经持有 `SearchContext + kbId`）
- **为什么用 `IN singleton` 而非 `EQ`？** 接口稳定——未来若做 fan-out 重构，从单元素 list 升级到多元素 list 不改 builder 签名、不改 retriever DSL 渲染；`EQ` 升级到 `IN` 要改两端
- **为什么 `Integer.MAX_VALUE` 而不是 `Integer.MAX_VALUE - 1` / 99 / 1000？** 都等价，但 `Integer.MAX_VALUE` 语义最直白（"无上限"），契约测试断言 `range.lte = 2147483647` 也最稳——任何"大于业务最高 level"的值都行，挑边界值清晰度最高
- **为什么 fail-fast 而不是 warn-log？** PR5 目标是 contract hardening，warn-log 让安全缺口降级为线上隐性问题；fail-fast 让缺口在测试 / dev / staging 阶段立刻暴露，不可能漏到生产
- **为什么 c5 列为可选？** 不让 cleanup 动作绑架契约硬化主线；零 caller 是工程优化，被 review 卡住也不影响 PR5 价值

---

## 10. 引用源

- 路线图：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B
- PR4 spec：`docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md`
- PR4 plan：`docs/superpowers/plans/2026-04-28-permission-pr4-retrieval-scope-builder.md`
- 关键代码：
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/filter/DefaultMetadataFilterBuilder.java`（builder）
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java:100-235`（retriever + render）
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MetadataFilter.java`（filter 类型）
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/SearchContext.java`（context）
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/vector/VectorMetadataFields.java`（字段名常量）
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`（单 KB 路径）
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`（全局通道）
  - `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java`（意图通道）
