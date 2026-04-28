# PR4 — Retrieval Scope Builder + Mapper 退役（阶段 B 入场）

- **日期**：2026-04-28
- **作者**：brainstorming session（zk2793126229@gmail.com + Claude）
- **范围**：把 `RAGChatServiceImpl.streamChat` 主流程内联的"权限 scope 计算"抽到独立 `RetrievalScopeBuilder`；`MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` 注入的 `KnowledgeBaseMapper` 退役为 `KbMetadataReader` port；`RetrievalScope` 一等公民 record 化，承载 `accessScope + kbSecurityLevels + targetKbId` 三件套，沿调用链一次贯通。
- **路线图位置**：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B · PR4
- **前置**：PR1（commit `a24ea4bb`）+ PR2（`7e1ef680`）+ PR3（`4942d278`）已合并至 main；包名重命名（`4289e553`）+ 守门脚本路径修正（本 PR 之前的 hotfix）
- **Out-of-scope marker**：`RagRequest` 字段迁移 / `ScopeMode { SELECTED_KB, ALL_AUTHORIZED }` / 前端 `indexNames` 字段下线 — **已不适用**（详见 §0.2，路线图描述已过时）；OpenSearch DSL 强制 `terms(metadata.kb_id)` 静态化 + `SecurityLevelFilterEnforcedTest` 留 PR5；阶段 C 起的 per-KB security level 修正 / 阶段 D 起的 sharing UI 全部不在本 PR

---

## 0. Problem Statement

### 0.1 路线图与现实的偏差（必须对齐再开 PR4）

路线图 §3 阶段 B · PR4 的描述写的是"`RagRequest` 字段从 `List<String> indexNames` 改为 `List<String> kbIds + ScopeMode`"。**这套描述已不适用**——它假设了一份从未在当前代码里存在、或在 PR1-PR3 期间被悄悄重塑的契约：

| 路线图假设 | 代码现实（main, 2026-04-28） | 结论 |
|---|---|---|
| 存在 `RagRequest` DTO 含 `List<String> indexNames` | 不存在该类型；HTTP 入口 `RAGChatController:49-52` 是 4 个 `@RequestParam`：`question / conversationId / knowledgeBaseId / deepThinking` | 路线图假设作废 |
| 前端传 `indexNames` 数组 | `grep -rn 'indexNames' frontend/src` 零结果；前端只传单个 `knowledgeBaseId`（`ChatStore`） | 路线图假设作废 |
| 后端"前端值即 scope" | `RAGChatServiceImpl:121-133` 已经用 `kbReadAccess.getAccessScope(Permission.READ)` + `checkReadAccess(knowledgeBaseId)` 后端计算并校验 | 已实现，无需 PR4 重做 |
| `indexNames` 仅存在于 `OpenSearch/Milvus/PgVectorStoreAdmin` | 是底层 vector store admin 工具方法名，与 RAG 入参契约无关 | 命名巧合，不是路线图意图 |

**根因推测**：路线图于 2026-04-26 写就，参考的是 PR1 spec 当时对外部需求的转译；PR1-PR3 在落 controller-thinning / port 拆分时已经把 RAG 入口契约改成单 KB + 后端计算 scope 的形态，但路线图 §3 阶段 B 文本未同步刷新。

### 0.2 阶段 B 真实剩余差距（PR4 必须处理 + PR5 候选）

逐项对照路线图 §3 阶段 B 的"成功标准"扫一遍当前 main：

| 成功标准 | 当前状态 | 处置 |
|---|---|---|
| `RagRequest` 不再含 `indexNames` 字段 | 该类型不存在 → 命题作废 | 路线图条目废止（PR4 c0 修订路线图） |
| OpenSearch DSL 强制带 `terms(metadata.kb_id)` + `range(security_level)` | `range(security_level)` 已经通过 `MetadataFilterBuilder` 注入；`terms(metadata.kb_id)` 当前不强制（single-KB 路径靠 `collectionName=KB.collection_name` 物理隔离，多 KB 路径靠 `AuthzPostProcessor` 兜底） | **PR5** 接手——补强 multi-KB DSL filter 静态化 + 新增 `SecurityLevelFilterEnforcedTest` |
| FICC_USER 无 OPS-COB 绑定时检索结果为空 | 已成立——`AuthzPostProcessor` order=0 三重 fail-closed（`AuthzPostProcessor:83-109`） | 已实现，PR5 加契约测试锁住 |
| `StreamChatEventHandler` 不再调用 `UserContext.getUserId()` | PR3 c4 已落（`PermissionBoundaryArchTest` 守门） | 已实现 |
| `MultiChannelRetrievalEngine` 不再注入 `KnowledgeBaseMapper` | **未做**——`MultiChannelRetrievalEngine:66` 仍 `private final KnowledgeBaseMapper knowledgeBaseMapper;` 用于 `selectById(knowledgeBaseId)` 取 `collectionName` | **PR4 c2** 接手 |

加上路线图 §3 阶段 B 的隐含目标"scope 计算职责从 streamChat 抽出"——`RAGChatServiceImpl:121-133` 当前 13 行内联计算 `accessScope + checkReadAccess + 会话归属校验`，需要抽到 `RetrievalScopeBuilder`。这是 PR4 的核心。

### 0.3 当前的混乱形态

`RAGChatServiceImpl.streamChat`（200+ 行）内主流程混杂三类职责：

```
L121-128: scope 计算       ← 该抽到 builder
L131-133: 单 KB 读权校验    ← 该抽到 builder
L136-146: 会话-KB 归属校验  ← 留在 streamChat（业务校验,不是 scope 决策）
L185:     retrievalEngine.retrieve(subIntents, accessScope, knowledgeBaseId)
```

`accessScope` 与 `knowledgeBaseId` 沿调用链各传各的，到 `MultiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, plan, accessScope, knowledgeBaseId)` 仍是松散两参。下游 `buildSearchContext`（`MultiChannelRetrievalEngine:256-276`）再额外读 `kbReadAccess.getMaxSecurityLevelsForKbs(...)` 装进 `SearchContext`——**安全等级 map 的获取时机晚于 scope 决策点**，等于 scope 计算被拆成两段，AuthzPostProcessor 依赖的 `kbSecurityLevels` 跨边界由检索引擎临时填补。

### 0.4 PR4 根治姿态

不是"再加一个 helper 把 13 行变 5 行"——helper 不解决调用链上下文断裂。PR4 立一个一等公民 record：

```java
public record RetrievalScope(
        AccessScope accessScope,
        Map<String, Integer> kbSecurityLevels,  // immutable, possibly empty (SUPER_ADMIN / 系统态)
        String targetKbId                        // nullable: 单 KB 定向；null=多 KB
) { ... }
```

`RetrievalScopeBuilder.build(requestedKbId) → RetrievalScope` 是项目内**唯一**把 `UserContext + KbReadAccessPort` 转为 `RetrievalScope` 的入口（生产请求路径）。Builder 内部读 `UserContext`，**不接受外部传入的 LoginUser**——避免出现"传入 user ≠ ThreadLocal user"的双身份漂移（review P1#1）。这与 PR3 `KbAccessSubjectFactory.currentOrThrow()` 是同一模式：单一 ThreadLocal 触点。

异步/系统态路径（如 `EvalRunExecutor` 调 `ChatForEvalService`）**不走 builder**，直接构造 `RetrievalScope.all(kbId)` sentinel——这是 §15.3 决策的延续：eval 路径自己持有 `AccessScope.all()` 的合法权利，不冒充用户。allowlist 在 §1.2 PR4-1 显式登记。

`RAGChatServiceImpl` 调一次 builder 拿到完整 scope 三件套；`RetrievalEngine` / `MultiChannelRetrievalEngine` / `SearchContext` 沿调用链消费同一个 record，不再二次回调 `kbReadAccess`。

这同时解决两个边界问题：
- **B1**：当前 `MultiChannelRetrievalEngine.buildSearchContext:261` 二次拉 `getMaxSecurityLevelsForKbs`，与上游 scope 决策**职责断裂、跨边界难测试**——`SearchContext` 何时填齐 `kbSecurityLevels` 取决于检索引擎而非 scope 构造时机，invariant 无法在 builder 处静态验证。`AuthzPostProcessor:94` `scope==null||!allows(kbId)` 已有 fail-closed 兜底，并非"可绕过"，但分散决策让 PR5 把 `terms(kb_id)` 静态化时无法确认 builder 出口契约一致。Builder 一次性算齐就消除这个分散点。
- **B2**：`MultiChannelRetrievalEngine.knowledgeBaseMapper.selectById(knowledgeBaseId)` 取 `collectionName`（`:87`）+ `VectorGlobalSearchChannel.knowledgeBaseMapper.selectList(...)` 拉全量 KB（`:174`）跨域注入了 knowledge 域 mapper，违反 P0 报告 SRC-3 边界。改成 `KbMetadataReader.getCollectionName(kbId)` + `getCollectionNames(Collection<String>)` port 调用。

---

## 1. Layering Contract

### 1.1 类拓扑（PR4 后）

```
┌─────────────────────────────────────────────────────────────────────┐
│ RAGChatController                                                   │
│   - 仅承担 HTTP 协议职责(PR1 不变量 A 继承)                          │
├─────────────────────────────────────────────────────────────────────┤
│ RAGChatServiceImpl.streamChat                                       │
│   - 入口直接 retrievalScopeBuilder.build(knowledgeBaseId)            │
│   - 单 KB 读权校验通过 builder 触发(builder 内部先 checkReadAccess)│
│   - 把 RetrievalScope 一次性传给 retrievalEngine.retrieve(...)      │
│   - 会话-KB 归属校验保留在 streamChat(业务规则,非 scope 决策)        │
├─────────────────────────────────────────────────────────────────────┤
│ ChatForEvalService.chatForEval(RetrievalScope, String)              │
│   - eval/异步路径,**不调 builder**(无 UserContext)                  │
│   - EvalRunExecutor 显式构造 RetrievalScope.all(kbId) sentinel      │
│     (§15.3 决策延续:eval 是 AccessScope.all() 唯一合法持有者)        │
├─────────────────────────────────────────────────────────────────────┤
│ RetrievalScopeBuilder  (rag/core/retrieve/scope/)                  │
│   build(String requestedKbId) → RetrievalScope                      │
│   - 项目内**唯一** UserContext→RetrievalScope 转换入口              │
│   - 内部读 UserContext (单一 ThreadLocal 触点,与                    │
│     KbAccessSubjectFactory.currentOrThrow() 同模式)                 │
│   - 内部依赖 KbReadAccessPort(无 KbMetadataReader,KB→collection 在  │
│     检索层做)                                                        │
│   - 决策顺序(fail-closed 优先):                                      │
│     1. requestedKbId != null → 先调 checkReadAccess(kbId)           │
│        无 user → 抛 ClientException(missing user context)           │
│        无权限 → 抛 ClientException(无权访问)                          │
│     2. user == null/userId == null → RetrievalScope.empty()         │
│     3. SUPER_ADMIN → RetrievalScope.all(requestedKbId)              │
│     4. 其他 → AccessScope.ids(...) + getMaxSecurityLevelsForKbs    │
├─────────────────────────────────────────────────────────────────────┤
│ RetrievalScope  (rag/core/retrieve/scope/)                         │
│   record(AccessScope accessScope,                                   │
│          Map<String,Integer> kbSecurityLevels,                       │
│          String targetKbId)                                          │
│   - 不可变,Map.copyOf,sentinel: empty()/all()                      │
├─────────────────────────────────────────────────────────────────────┤
│ RetrievalEngine.retrieve(subIntents, plan, RetrievalScope)         │
│   - 签名从 (subIntents, AccessScope, String) → (subIntents, plan,  │
│     RetrievalScope) 单参收敛                                         │
│   - plan 仍由 RetrievalEngine 内部从 RagRetrievalProperties 派生     │
├─────────────────────────────────────────────────────────────────────┤
│ MultiChannelRetrievalEngine.retrieveKnowledgeChannels(             │
│       subIntents, plan, RetrievalScope)                             │
│   - 不再注入 KnowledgeBaseMapper                                    │
│   - 注入 KbMetadataReader(framework port)                          │
│   - 单 KB 路径: kbMetadataReader.getCollectionName(kbId)           │
│   - buildSearchContext 不再调 kbReadAccess.getMaxSecurityLevelsForKbs│
│     直接读 RetrievalScope.kbSecurityLevels()                        │
├─────────────────────────────────────────────────────────────────────┤
│ KbMetadataReader  (framework/security/port)                        │
│   + String getCollectionName(String kbId);  ← 本 PR 新增方法         │
│     return null 表示 KB 不存在或已软删                                │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 不变量（PR4 起所有后续 PR 必须保持）

继承 PR1 A/B/C/D + PR3 PR3-1..PR3-6。新增 4 条 **PR4 不变量**：

- **PR4-1 — Scope 单一来源 + 显式 sentinel allowlist**：`RetrievalScopeBuilder.build(...)` 是**生产请求路径** `UserContext → RetrievalScope` 的唯一入口；`RAGChatServiceImpl` 之外的生产代码不得直接 `new RetrievalScope(...)` 也不得调 `kbReadAccess.getAccessScope(...)`。`new RetrievalScope(` 构造点 allowlist 仅含：
  - `RetrievalScope` record 自身（静态工厂 `empty()` / `all()` 内部）
  - `RetrievalScopeBuilderImpl`（生产路径出口）
  - 测试代码 `bootstrap/src/test/.../scope/`
  
  Sentinel 静态工厂 `RetrievalScope.empty()` / `RetrievalScope.all(kbId)` **可被任意 caller 调用**（含 `EvalRunExecutor` 等异步/系统态合法持有者）——这是 §15.3 "eval 是 `AccessScope.all()` 唯一合法持有者" 决策的延续。ArchUnit 守门构造点 allowlist；caller 用 sentinel 不受限制。
- **PR4-2 — RAG 检索链单参契约**：`RetrievalEngine.retrieve(...)` 与 `MultiChannelRetrievalEngine.retrieveKnowledgeChannels(...)` 签名不得再含独立 `AccessScope accessScope` 与 `String knowledgeBaseId` 两参——必须收敛为 `RetrievalScope`。grep 守门：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve` 下方法签名不得包含 `AccessScope\s+\w+,\s*String\s+knowledgeBaseId`。
- **PR4-3 — Mapper 退役**：`MultiChannelRetrievalEngine` 与 `VectorGlobalSearchChannel` 不得 import `KnowledgeBaseMapper`。改用 `KbMetadataReader` port。ArchUnit 守门：`rag/core/retrieve` 包不得依赖 `knowledge.dao.mapper.*`。
- **PR4-4 — `kbSecurityLevels` 单次解析**：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag` 下 grep `getMaxSecurityLevelsForKbs` 出现次数 **= 1**（仅在 `RetrievalScopeBuilder` 实现内）。下游不得二次调用。

### 1.3 反模式清单（PR4 后任何 PR 不允许）

继承路线图 §2.3 全部反模式。新增：

- ❌ 在 `RAGChatServiceImpl` / 检索链路下游手工 `kbReadAccess.getAccessScope(...)`（PR4-1）
- ❌ 在 `rag/core/retrieve` 下注入 `*Mapper`（PR4-3 + 路线图 SRC-3）
- ❌ 把 `RetrievalScope` 拆成松散参数沿调用链传（PR4-2）
- ❌ 在 `RetrievalScopeBuilder` 之外读 `UserContext` 来填充 `RetrievalScope`（PR4-1）

---

## 2. Detailed Design

### 2.1 新增类型

#### `RetrievalScope` (record)

位置：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java`

```java
public record RetrievalScope(
        AccessScope accessScope,
        Map<String, Integer> kbSecurityLevels,
        String targetKbId) {

    public RetrievalScope {
        Objects.requireNonNull(accessScope, "accessScope");
        kbSecurityLevels = kbSecurityLevels == null
                ? Map.of()
                : Map.copyOf(kbSecurityLevels);
        // targetKbId 允许 null
    }

    public static RetrievalScope empty() {
        return new RetrievalScope(AccessScope.empty(), Map.of(), null);
    }

    public static RetrievalScope all(String targetKbId) {
        return new RetrievalScope(AccessScope.all(), Map.of(), targetKbId);
    }

    /** 单 KB 定向场景:targetKbId 非空。多 KB 全量场景:null。 */
    public boolean isSingleKb() {
        return targetKbId != null;
    }
}
```

设计点：
- `kbSecurityLevels` 用 `Map.copyOf` 立即冻结——SUPER_ADMIN 路径返 `Map.of()`，DEPT_ADMIN/USER 路径返 builder 算好的不可变拷贝
- 不在 record 内做语义判断（如"`accessScope==All` 时 `kbSecurityLevels` 必空"）——交给 builder 保证；record 仅做 null-guard
- `isSingleKb()` 是消费侧便利方法；`targetKbId` 仍是字段，下游可直接 destructure

#### `RetrievalScopeBuilder` (interface) + `RetrievalScopeBuilderImpl`

位置：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/`

```java
public interface RetrievalScopeBuilder {
    /**
     * 构建当前请求的检索 scope（**仅生产请求路径**）。
     * 内部读 UserContext，不接受外部 LoginUser 参数——避免双身份漂移（review P1#1）。
     * 异步/系统态路径请直接用 {@link RetrievalScope#all(String)} sentinel。
     *
     * <p>决策顺序（fail-closed 优先）：
     * <ol>
     *   <li>{@code requestedKbId != null} → 先调 {@code kbReadAccess.checkReadAccess(kbId)}：
     *       无 user 抛 {@code ClientException("missing user context")}（PR1 不变量 B 已建立）；
     *       无权抛 {@code ClientException("无权访问")}。<b>不</b>因 user==null 短路为 empty</li>
     *   <li>requestedKbId == null 且 user == null/userId == null → {@link RetrievalScope#empty()}</li>
     *   <li>SUPER_ADMIN → {@link RetrievalScope#all(String)}（kbSecurityLevels 留空,无天花板）</li>
     *   <li>其他 → AccessScope.ids(...) + 一次性算齐 getMaxSecurityLevelsForKbs(ids)</li>
     * </ol>
     */
    RetrievalScope build(String requestedKbId);
}
```

实现侧关键点：
- 注入 `KbReadAccessPort kbReadAccess`（不注入 `KbMetadataReader`——KB→collection 留检索层处理，避免 builder 接两个 port）
- 内部读 `LoginUser user = UserContext.hasUser() ? UserContext.get() : null`——单一 ThreadLocal 触点（review P1#1）
- **fail-closed 优先**：`requestedKbId != null` 时先调 `checkReadAccess(kbId)`，由 `KbAccessServiceImpl.bypassIfSystemOrAssertActor()`（PR1 守卫）负责无 user 抛 `ClientException`——HTTP 语义维持"未登录访问指定 KB → 拒绝"，**不** 退化为"返回 empty 走空检索"（review P2#1）
- SUPER_ADMIN 检测复用 `user.roleTypes.contains(RoleType.SUPER_ADMIN)`，与 `KbScopeResolverImpl:44` 同语义

#### `KbMetadataReader` 新增 2 方法（含 batch）

位置：`framework/src/main/java/com/knowledgebase/ai/ragent/framework/security/port/KbMetadataReader.java`

```java
/**
 * 获取 KB 的 collection_name(物理向量索引名)。
 * 单 KB 定向检索路径用(MultiChannelRetrievalEngine.retrieveKnowledgeChannels)。
 * @return collection_name；KB 不存在或已软删返回 null
 */
String getCollectionName(String kbId);

/**
 * 批量解析 kbIds 对应的 collection_name(过滤未删除且 collection_name 非空)。
 * 全局/多 KB 检索路径用(VectorGlobalSearchChannel),避免 N 次 selectById。
 * 返回 map 仅包含存在且 collection_name 有效的 kbId。
 */
Map<String, String> getCollectionNames(Collection<String> kbIds);
```

实现走 `bootstrap` 侧已有的 `KbMetadataReaderImpl`（或同等位置），`getCollectionNames` 底层走 `selectBatchIds(...)` 单次 SQL——**封装在 knowledge 域内**，对外仅暴露 port。

### 2.2 修改类型

#### `RAGChatServiceImpl.streamChat`

替换 `:121-133`：

```java
// PR4 之前(删除)
AccessScope accessScope;
if (UserContext.hasUser() && userId != null) {
    accessScope = kbReadAccess.getAccessScope(Permission.READ);
} else {
    accessScope = AccessScope.empty();
}
if (knowledgeBaseId != null) {
    kbReadAccess.checkReadAccess(knowledgeBaseId);
}
```

```java
// PR4(新)— builder 内部读 UserContext + 触发 checkReadAccess(kbId)
RetrievalScope scope = retrievalScopeBuilder.build(knowledgeBaseId);
```

`retrievalEngine.retrieve(subIntents, accessScope, knowledgeBaseId)` 改 `retrieve(subIntents, scope)`。

`kbReadAccess` 字段从 `RAGChatServiceImpl` 移除（PR3 起这是它唯一两处 caller），换成 `retrievalScopeBuilder` 注入。

#### `ChatForEvalService.chatForEval`（review P1#2）

签名 `chatForEval(AccessScope scope, String kbId, String question)` 改为 `chatForEval(RetrievalScope scope, String question)`：

```java
// PR4 之前(删除)
public AnswerResult chatForEval(AccessScope scope, String kbId, String question) { ... }

// PR4(新)
public AnswerResult chatForEval(RetrievalScope scope, String question) {
    // ...
    RetrievalContext ctx = retrievalEngine.retrieve(subIntents, scope);
    // ...
}
```

调用方 `EvalRunExecutor` 显式构造 `RetrievalScope.all(kbId)` sentinel——继续承担 §15.3 决策的"`AccessScope.all()` 唯一合法持有者"职责，**不调** `RetrievalScopeBuilder`（builder 读 UserContext，eval 跑在异步线程，无 UserContext 传递）。

测试要求（强制）：
- `ChatForEvalServiceTest` 加 case：注入 mock `RetrievalScopeBuilder`，验证 `chatForEval` 路径**不调** builder（Mockito.verify(builder, never())）
- `EvalRunExecutorTest` 加 case：验证 `EvalRunExecutor` 传给 `chatForEval` 的 scope 是 `RetrievalScope.all(kbId)`（`accessScope instanceof All && targetKbId.equals(kbId)`）

#### `RetrievalEngine` + `MultiChannelRetrievalEngine`

签名收敛：
```java
// 之前
List<RetrievedChunk> retrieveKnowledgeChannels(
        List<SubQuestionIntent> subIntents, RetrievalPlan plan,
        AccessScope accessScope, String knowledgeBaseId);

// PR4
List<RetrievedChunk> retrieveKnowledgeChannels(
        List<SubQuestionIntent> subIntents, RetrievalPlan plan,
        RetrievalScope scope);
```

`MultiChannelRetrievalEngine` 内部：
- 删除 `KnowledgeBaseMapper knowledgeBaseMapper` 字段，删除 `KbReadAccessPort kbReadAccess` 字段（不再二次拉 security levels）
- 新增 `KbMetadataReader kbMetadataReader` 字段
- `:87 KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(...)` → `String collectionName = kbMetadataReader.getCollectionName(scope.targetKbId())`
- `buildSearchContext` 删除二次 `getMaxSecurityLevelsForKbs` 调用，直接 `.kbSecurityLevels(scope.kbSecurityLevels())`

#### `VectorGlobalSearchChannel`（c4 必做，review P2#3）

当前 `:50,56,60,174` 注入 `KnowledgeBaseMapper` 用于 `selectList(...)` 拉全量 KB → `scope.allows(kbId)` 过滤。改造：

```java
// 字段
private final KbMetadataReader kbMetadataReader;  // 替代 knowledgeBaseMapper

// getAccessibleKBs(SearchContext context) 改写
private List<KbCollection> getAccessibleKBs(SearchContext context) {
    AccessScope scope = context.getAccessScope();
    Set<String> visibleKbIds;
    if (scope instanceof AccessScope.All) {
        visibleKbIds = kbMetadataReader.listAllKbIds();
    } else if (scope instanceof AccessScope.Ids ids) {
        visibleKbIds = ids.kbIds();
    } else {
        return List.of();
    }
    Map<String, String> nameMap = kbMetadataReader.getCollectionNames(visibleKbIds);
    return nameMap.entrySet().stream()
            .map(e -> new KbCollection(e.getKey(), e.getValue()))
            .toList();
}
```

下游 `retrieveFromAllCollections` 接 `List<KbCollection>` 替代 `List<KnowledgeBaseDO>`。本地 record `KbCollection(String kbId, String collectionName)` 放在 channel 内部（不污染 framework）。

构造函数同步去掉 `KnowledgeBaseMapper` 参数。

#### `SearchContext`

新增字段 `RetrievalScope scope`（保留 `accessScope` + `kbSecurityLevels` 作为便利访问，第一阶段不删，避免 channel/postprocessor 大面积签名改动）。Channel/postprocessor 改造留 PR4.5 或 PR5 视范围决定——**本 PR 不强行**，只要 `MultiChannelRetrievalEngine` 内的二次拉取消失即可。

### 2.3 不动的边界

- `KbScopeResolver` 与 `RetrievalScopeBuilder` 各自负责不同的输出：
  - `KbScopeResolver.resolveForRead(LoginUser)` → `AccessScope`（用于 KB 列表 / 文档列表查询路径，不带 security levels）
  - `RetrievalScopeBuilder.build(kbId)` → `RetrievalScope`（用于 RAG 检索路径，带 security levels + targetKbId；builder 内读 UserContext，不接受外部 LoginUser）
  - **不合并**——RAG 检索是唯一需要 per-KB security levels 的场景，KB 列表查询从不需要；合并会让 `KbScopeResolver` 携带无关字段。
- `AuthzPostProcessor` 不变——继续读 `SearchContext.accessScope` + `kbSecurityLevels`；这两个字段在 PR4 后由 `RetrievalScope` 解构填充。

---

## 3. Verification

### 3.1 测试矩阵

| 测试 | 关键场景 | 位置 |
|---|---|---|
| `RetrievalScopeBuilderImplTest` | (1) requestedKbId==null + 未登录 → empty<br>(2) **requestedKbId!=null + 未登录 → throw `ClientException("missing user context")`** ← review P2#1 必覆盖<br>(3) requestedKbId!=null + 无权 → throw ClientException<br>(4) SUPER_ADMIN + requestedKbId==null → all(null) + 空 map<br>(5) SUPER_ADMIN + requestedKbId!=null → all(kbId) + 空 map<br>(6) DEPT_ADMIN → ids+map<br>(7) USER → ids+map | `rag/core/retrieve/scope/` |
| `RetrievalScopeBuilderMismatchTest` | builder **不接受** LoginUser 参数,通过反射验证 build 仅有 1 参 String —— 锁住 review P1#1 双身份漂移修复不退化 | 同上 |
| `RetrievalScopeTest` | record null-guard / `Map.copyOf` 隔离原 map / sentinel 一致性 | 同上 |
| `RAGChatServiceImplScopeTest`（已存在则补） | builder 调用一次,下游收到完整 scope；mock builder 返 empty 时检索路径正确短路 | `rag/service/impl/` |
| `ChatForEvalServiceTest`（扩展） | (1) `chatForEval` 收到 RetrievalScope.all(kbId) 时正常走完<br>(2) **mock RetrievalScopeBuilder,Mockito.verify(builder, never())** —— 锁住 eval 路径不绕到 UserContext builder（review P1#2） | `rag/core/` |
| `EvalRunExecutorTest`（扩展） | 验证传给 chatForEval 的 scope 是 `RetrievalScope.all(kbId)` —— `accessScope instanceof All && targetKbId.equals(kbId) && kbSecurityLevels.isEmpty()` | `eval/...` |
| `MultiChannelRetrievalEngineKbMetadataReaderTest` | 单 KB 路径走 `KbMetadataReader.getCollectionName`；`null` collection 返空列表 | `rag/core/retrieve/` |
| `VectorGlobalSearchChannelKbMetadataReaderTest` | (1) AccessScope.All → listAllKbIds + getCollectionNames<br>(2) AccessScope.Ids → 直接 getCollectionNames(ids)<br>(3) 不再依赖 KnowledgeBaseMapper（mock 注入校验） | `rag/core/retrieve/channel/` |
| `PermissionBoundaryArchTest`（扩展） | PR4-1 `new RetrievalScope(` 仅在 allowlist 出现 / PR4-3 `rag/core/retrieve` 包及其 channel 子包均不依赖 `knowledge.dao.mapper.*` | `arch/` |
| `KbAccessServiceRetirementArchTest`（不动） | PR2/PR3 已锁定 | — |

### 3.2 守门脚本（新增）

`docs/dev/verification/permission-pr4-scope-builder-isolated.sh`：

```bash
# Gate 1: RetrievalScope 构造仅在 allowlist
EXPECTED='bootstrap/.../scope/RetrievalScope.java
bootstrap/.../scope/RetrievalScopeBuilderImpl.java
bootstrap/src/test/.../scope/...'
ACTUAL=$(grep_files 'new RetrievalScope\(' bootstrap/src ...)
[ "$ACTUAL" == "$EXPECTED" ] || fail

# Gate 2: rag/core/retrieve(含 channel/ 子包) 不依赖 KnowledgeBaseMapper
# 必须扫整个子树, MultiChannelRetrievalEngine + VectorGlobalSearchChannel 均覆盖
grep_matches 'import.*knowledge\.dao\.mapper\.KnowledgeBaseMapper' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve \
  && fail

# Gate 3: getMaxSecurityLevelsForKbs 在 rag 域只调用 1 次(builder)
COUNT=$(grep_count 'getMaxSecurityLevelsForKbs\(' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag)
[ "$COUNT" -eq 1 ] || fail

echo "PR4 grep gates passed"
```

### 3.3 手工 smoke

复用 `docs/dev/verification/permission-pr1-smoke.md` 4 条手工路径，新增 2 条：
- **S5**：FICC_USER 登录后调 `/rag/v3/chat?knowledgeBaseId=<OPS-COB-id>` → 期望 SSE 立即收到 ClientException 包装的拒绝（`Result.code != 0`，`message` 含"无权访问"）。验证 builder 的 `checkReadAccess` 触发 fail-closed。
- **S6**（review P2#1 防回归）：未登录直接调 `/rag/v3/chat?knowledgeBaseId=<any-id>` → 期望抛 `ClientException("missing user context")`，**不**退化为返回"未检索到相关文档"的空回复。HTTP 语义与 PR3 前一致。

### 3.4 路线图更新

PR4 同 PR 内更新 `docs/dev/design/2026-04-26-permission-roadmap.md`：

- §1 当前坐标：阶段 A 收束 + PR4 完成
- §3 阶段 B：把"`RagRequest` 字段迁移"段落改为"已不适用——前端契约在 PR1-PR3 期间已变成单 KB"，把 commit 序列改为本 spec §4 内容
- §3 阶段 B 成功标准：移除 `RagRequest` 条目；保留 `MultiChannelRetrievalEngine 不再注入 KnowledgeBaseMapper` 标记 PR4 完成；`OpenSearch DSL 强制 terms(kb_id)` 标记 PR5

---

## 4. Commit Plan（TDD 驱动，5 commit）

```
c0  docs(security): roadmap §3 阶段 B 修订 — 删除 RagRequest 字段迁移命题,补 PR4/PR5 真实差距
    Why first: 让 PR4 落到正确范围,避免 reviewer 对照过时路线图打回
    Files: docs/dev/design/2026-04-26-permission-roadmap.md

c1  feat(security): RetrievalScope record + RetrievalScopeBuilder 接口 + 实现 + 测试
    TDD: 先 RetrievalScopeBuilderImplTest(7 case) → 再实现 → 全绿
    Files: bootstrap/.../rag/core/retrieve/scope/RetrievalScope.java
           bootstrap/.../rag/core/retrieve/scope/RetrievalScopeBuilder.java
           bootstrap/.../rag/core/retrieve/scope/RetrievalScopeBuilderImpl.java
           bootstrap/src/test/.../rag/core/retrieve/scope/*Test.java

c2  feat(framework): KbMetadataReader.getCollectionName + 实现
    TDD: KbMetadataReaderImplTest 加 case → 实现 → 全绿
    Files: framework/.../KbMetadataReader.java
           bootstrap/.../KbMetadataReaderImpl.java(或既存实现位置)
           bootstrap/src/test/.../KbMetadataReaderImplTest.java

c3  refactor(rag): RAGChatServiceImpl 改用 RetrievalScopeBuilder + RetrievalEngine 签名收敛
    TDD: 先改 RAGChatServiceImpl 测试期望 → 改实现 → MultiChannelRetrievalEngine 签名同步
    Files: bootstrap/.../rag/service/impl/RAGChatServiceImpl.java
           bootstrap/.../rag/core/retrieve/RetrievalEngine.java
           bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java
           (相关测试)

c4  refactor(rag): MultiChannelRetrievalEngine + VectorGlobalSearchChannel 注入 KbMetadataReader,KnowledgeBaseMapper 退役
    TDD: 先改测试用 mock KbMetadataReader (含 batch getCollectionNames) → 改实现去 KnowledgeBaseMapper 字段 → 全绿
    Files: bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java
           bootstrap/.../rag/core/retrieve/channel/VectorGlobalSearchChannel.java  ← review P2#3 必做,不延期
           bootstrap/src/test/.../VectorGlobalSearchChannelKbMetadataReaderTest.java
           (相关测试)

c5  test(security): PR4 守门脚本 + ArchUnit 规则 + 阶段 B 入场契约
    Files: docs/dev/verification/permission-pr4-scope-builder-isolated.sh
           bootstrap/src/test/.../arch/PermissionBoundaryArchTest.java(扩展 PR4-1/PR4-3 规则)
           docs/dev/verification/permission-pr4-smoke.md(S5 路径)
```

每个 commit 自带测试，不允许"先实现再补测试"。

---

## 5. Risks & Rollback

### 5.1 已识别风险

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| **R1**：`RetrievalScope` record 字段加错（如忘了 `targetKbId`）导致下游单 KB 路径退化为多 KB | builder 构造遗漏 | `RetrievalScopeBuilderImplTest` 必须覆盖 single-KB 路径 + ArchUnit 锁住构造点 |
| **R2**：`KbMetadataReader.getCollectionName` 误读 `deleted=1` 软删 KB | 实现走 `selectById` 没带 `deleted=0` 条件 | 实现走 mybatis-plus `selectById` 已自动过滤逻辑删（`@TableLogic`），但需写测试覆盖软删 KB → null |
| **R3**：`SearchContext` 同时含 `accessScope`+`kbSecurityLevels`+`scope` 字段冗余诱导后续维护错乱 | 本 PR 故意保留 SearchContext 老字段为便利访问 | 在 SearchContext 上加 javadoc 标注"PR5 起统一为 RetrievalScope"，并登 backlog SRC-N |
| **R4**：路线图修订（c0）与代码改动放同 PR，reviewer 难审 | c0 是文档,c1-c5 是代码 | c0 内容仅删除/重写过时段落,不引入新方向；reviewer 可对照本 spec §0.1 表格独立验证 |
| **R5**（review P1#2）：未来新 caller 误用 `RetrievalScope.all(kbId)` 绕过 RBAC | sentinel 静态工厂对所有 caller 开放,缺乏审计 | (1) PR4-1 ArchUnit 守门只锁构造点,但 grep `RetrievalScope\.all\(` 出现位置纳入 code review checklist；(2) sentinel 设计本身保留——它是 §15.3 已决议的合法逃生通道，强制收紧反而让 eval 路径无解；(3) 后续 PR 引入新 sentinel caller 必须在 spec 显式登记到 §15.3 / 本 spec PR4-1 allowlist |
| **R6**（review P2#1）：未登录单 KB 路径在某些边缘情况退化为返回 empty | builder 实现错把 user==null 判断放在 requestedKbId!=null 之前 | TDD 强制：`RetrievalScopeBuilderImplTest` case (2) 显式 assert 抛 ClientException；S6 手工 smoke 兜底；ArchUnit 不可覆盖此语义,只能靠测试 |

### 5.2 Rollback 策略

PR4 的 5 个 commit 严格按依赖顺序：c0 文档先行，c1-c2 引入新类型不动旧逻辑，c3 切换 RAGChatServiceImpl 调用点，c4 退役 mapper，c5 加守门。

回滚粒度：
- 仅 c5 出问题（脚本/ArchUnit 误报）→ revert c5
- c4 后发现 single-KB 路径回归 → revert c4 + c3，保留 c0-c2 等 PR4.5 重做
- c3 发现 builder 语义错误 → revert c3-c5，保留 c0-c2（builder 已落但未启用，安全保留）

不允许 c1-c2 单独存活——record + port method 必须有 caller，否则触发 ArchUnit 死代码守门。

---

## 6. Out-of-scope（延期项明确登记）

- **OS-1**：OpenSearch DSL 强制 `terms(metadata.kb_id)` 静态化 → **PR5**
- **OS-2**：`SecurityLevelFilterEnforcedTest` 契约测试 → **PR5**
- **OS-3**：`SearchContext` 拆分（去 `accessScope`/`kbSecurityLevels` 便利字段，统一为 `RetrievalScope scope`）→ PR5 或 PR4.5 视审查反馈
- **OS-5**：`per-KB security level` 粒度修正（路线图阶段 C 唯一目标）→ **PR6**

> （已删除 OS-4：经核实 `VectorGlobalSearchChannel:50,56,60,174` 确实注入 `KnowledgeBaseMapper`，已并入 c4 必做范围 + Gate 2 覆盖。review P2#3）

---

## 7. Decision Log

- **D1：为什么 record 而不是 class + lombok**？record 自动 final + immutable + value semantics，对 scope 这种"算一次,沿链传"的值最贴。lombok `@Value` 等价但不是 JEP-recognized record，PR3 已经在 `KbAccessSubject` 选 record，保持一致。
- **D2：为什么 builder 内部不注入 `KbMetadataReader`**？builder 的职责是"权限 scope 计算"，KB→collection name 是物理索引解析，属于检索层。混合两个 port 会让 builder 成为"小型 KbAccessService"，违背 PR2/PR3 的边界约束。
- **D3：为什么 `RetrievalScope.kbSecurityLevels` 在 SUPER_ADMIN 路径返 `Map.of()` 而不是物化全部 KB**？路线图阶段 A 不变量"SUPER_ADMIN 在 port 出口必须返 `AccessScope.all()` sentinel"（PR3 决策），物化全集会破 `instanceof All` 短路。本 PR 严格继承——SUPER_ADMIN 跳过等级过滤由 `AuthzPostProcessor:103` 的 `ceiling == null` 分支自然处理。
- **D4：为什么不在 PR4 把 `SearchContext` 也清干净**？范围控制——c3+c4 已经是签名级改动，c5 还要加守门；再加 SearchContext 改造会让 PR 体量超过 PR3。SearchContext 字段冗余在 ArchUnit 上不是 invariant 违反，留 PR5 处理可控。
- **D5：为什么路线图修订（c0）放进 PR4 而不是独立 docs PR**？路线图 §3 阶段 B 现状会让 reviewer 把 PR4 当成"残缺实现"打回（缺 RagRequest 字段迁移）。c0 是 PR4 的入场前置——不修订路线图，PR4 spec 看不出合理性。独立 docs PR 反而增加协调成本。
- **D6：为什么 builder 改为单参 `build(String)`，不接受 LoginUser**？（review P1#1）双参 `build(LoginUser, String)` 让 caller 有两种身份来源（传入参数 + ThreadLocal），任何一端漂移都会让 builder 内部 KbReadAccessPort（current-user-only 签名）和外部 LoginUser 算出不一致的 scope——这正是 PR3 §0 修复的反模式翻版。单参 `build(String)` + builder 内读 UserContext 对齐 PR3 `KbAccessSubjectFactory.currentOrThrow()` 模式：单一 ThreadLocal 触点，签名层面消除诱因。**eval/异步路径不走 builder**，靠 `RetrievalScope.all(kbId)` sentinel 自己持有 `AccessScope.all()`——这是 §15.3 决策的延续，不是新例外。
- **D7：为什么 fail-closed 顺序是"requestedKbId 优先" 而非"user-null 优先"**？（review P2#1）若先判 user==null 返 empty，未登录用户调 `/rag/v3/chat?knowledgeBaseId=<any>` 会得到"未检索到相关文档"的空 SSE 回复——这是 PR3 前完全不存在的语义，违背 PR1 不变量 C "HTTP 语义不变"。改成 requestedKbId 优先：`checkReadAccess` 内部由 PR1 已建立的 `bypassIfSystemOrAssertActor` 守卫负责无 user 抛 `ClientException("missing user context")`——保持 PR1 至 PR3 的 HTTP 语义连续性。
- **D8：为什么 `ChatForEvalService` signature 改成 `(RetrievalScope, String)` 而非保留 `(AccessScope, String, String)`**？（review P1#2）当前 signature `chatForEval(AccessScope scope, String kbId, String question)` 只传 scope + kbId 两段，下游需要再从 kbReadAccess 拉 security levels——eval 路径要么显式传空 map 要么误调 builder。改成 `RetrievalScope`：调用方 `EvalRunExecutor` 一次性构造 `RetrievalScope.all(kbId)`（内部 kbSecurityLevels=Map.of() 显式表示"无 ceiling"），signature 强制 caller 想清楚"我是不是合法的 sentinel 持有者"。`Mockito.verify(builder, never())` 测试锁住 eval 路径不绕到 UserContext 路径。

---

## 8. References

- 路线图：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B
- PR1 spec（不变量 A/B/C/D 来源）：`docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md`
- PR3 spec（不变量 PR3-1..PR3-6 来源 + record 模式参考）：`docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md`
- 当前现状代码锚点：
  - `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java:121-133, :185`
  - `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java:66, :87, :256-276`
  - `bootstrap/.../rag/core/retrieve/postprocessor/AuthzPostProcessor.java:83-109`
  - `framework/.../security/port/KbReadAccessPort.java`
  - `framework/.../security/port/AccessScope.java`
- P0 报告 SRC-3（mapper 跨域注入）：`docs/dev/followup/2026-04-26-architecture-p0-audit-report.md`
