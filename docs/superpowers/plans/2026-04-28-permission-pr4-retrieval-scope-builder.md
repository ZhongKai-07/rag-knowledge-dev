# Permission PR4 — Retrieval Scope Builder + Mapper 退役 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按 task-by-task 推进。Step 用 checkbox（`- [ ]`）追踪。

**Goal:** 把 `RAGChatServiceImpl.streamChat` 主流程内联的"权限 scope 计算"抽到独立 `RetrievalScopeBuilder`；`MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` 注入的 `KnowledgeBaseMapper` 退役为 `KbMetadataReader` port；`RetrievalScope` 一等公民 record，承载 `(accessScope, kbSecurityLevels, targetKbId)` 三件套沿调用链一次贯通。eval/异步路径用 `RetrievalScope.all(kbId)` sentinel 不调 builder。同步修订路线图 §3 阶段 B 描述（`RagRequest` 字段迁移命题已不适用）。

**Architecture:** 6 个 commit（c0 文档 + c1-c5 代码），每个 commit 独立 compilable + test-green：

1. **c0 docs(roadmap)** — 路线图 §1 当前坐标 + §3 阶段 B 修订。**第一刀**：reviewer 对照过时路线图会把 PR4 当残缺实现打回。
2. **c1 feat(scope-types)** — `RetrievalScope` record + `RetrievalScopeBuilder` interface + `Impl` + 4 个测试类。新代码无 caller，build 仍绿。
3. **c2 feat(framework)** — `KbMetadataReader` 加 `getCollectionName(kbId)` + `getCollectionNames(Collection)` 2 个方法，`KbMetadataReaderImpl` 同步实现，扩展现有测试。
4. **c3 refactor(rag-signatures)** — `RetrievalEngine.retrieve` / `MultiChannelRetrievalEngine.retrieveKnowledgeChannels` / `ChatForEvalService.chatForEval` 三个签名一次性收敛到 `RetrievalScope`；`RAGChatServiceImpl` 改用 builder；`EvalRunExecutor` 改构造 sentinel。**单一 commit by necessity**——签名变动强制 caller 同步更新。
5. **c4 refactor(mapper-retirement)** — `MultiChannelRetrievalEngine` + `VectorGlobalSearchChannel` 注入 `KbMetadataReader` 退役 `KnowledgeBaseMapper`。
6. **c5 test(gates)** — `permission-pr4-scope-builder-isolated.sh` 守门脚本 + `PermissionBoundaryArchTest` 扩展 PR4-1 / PR4-3 / PR4-4 ArchUnit 规则 + `permission-pr4-smoke.md` 含 S5 + S6 两条手工路径。

**Tech Stack:** Java 17 · Spring Boot 3.5 · MyBatis Plus · Mockito · JUnit 5 · ArchUnit · Lombok · Sa-Token · OpenSearch

**Spec:** `docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md`

**Roadmap:** `docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B · PR4

---

## File Structure

### Files to create

| Path | Responsibility |
|---|---|
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java` | Immutable `record(AccessScope, Map<String,Integer>, String)` + `empty()` / `all(kbId)` 静态工厂 + `isSingleKb()` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilder.java` | Interface — `RetrievalScope build(String requestedKbId)` 单参契约 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilderImpl.java` | Spring `@Service` — 单一 ThreadLocal 触点；fail-closed 优先序 |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeTest.java` | record null-guard / `Map.copyOf` 隔离 / sentinel 一致性（4 case） |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilderImplTest.java` | builder 7 case：(1) requestedKbId==null + 未登录 → empty / (2) requestedKbId!=null + 未登录 → throw / (3) requestedKbId!=null + 无权 → throw / (4) SA + null → all(null) / (5) SA + kbId → all(kbId) / (6) DEPT_ADMIN → ids+map / (7) USER → ids+map |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilderMismatchTest.java` | 反射验证 `build` 仅 1 参 String —— 锁住 review P1#1 修复不退化 |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannelKbMetadataReaderTest.java` | (1) `AccessScope.All` 路径走 `listAllKbIds` + `getCollectionNames` / (2) `AccessScope.Ids` 直接 `getCollectionNames(ids)` / (3) 不依赖 `KnowledgeBaseMapper`（mock 注入校验） |
| `docs/dev/verification/permission-pr4-scope-builder-isolated.sh` | grep-based CI guard for PR4-1 / PR4-3 / PR4-4 |
| `docs/dev/verification/permission-pr4-smoke.md` | 手工 smoke 文档（S5 + S6 + 既有 PR1-3 复用清单） |

### Files to modify

| Path | What changes |
|---|---|
| `framework/src/main/java/com/knowledgebase/ai/ragent/framework/security/port/KbMetadataReader.java` | 加 `String getCollectionName(String kbId)` + `Map<String,String> getCollectionNames(Collection<String> kbIds)` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KbMetadataReaderImpl.java` | 实现 2 新方法（`selectById` 单查 + `selectBatchIds` 批量；`@TableLogic` 自动过滤 deleted） |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KbMetadataReaderImplTest.java` | 加 case：`getCollectionName(null) → null` / 软删 KB → null / 正常 KB → collection；`getCollectionNames(empty) → Map.of()` / `getCollectionNames(mixed)` 仅返存在的 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrievalEngine.java` | `retrieve` 签名 `(subIntents, AccessScope, String)` → `(subIntents, RetrievalScope)`；`buildSubQuestionContext` / `retrieveAndRerank` 同步收敛 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java` | `retrieveKnowledgeChannels` 签名收敛；删 `KnowledgeBaseMapper` + `KbReadAccessPort` 字段，注入 `KbMetadataReader`；`buildSearchContext` 不再二次拉 security levels；单 KB 路径 `:87` 改 `kbMetadataReader.getCollectionName(scope.targetKbId())` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java` | 删 `KnowledgeBaseMapper` 字段 + 构造参数；注入 `KbMetadataReader`；`getAccessibleKBs` 改用 `listAllKbIds` / `ids.kbIds()` + `getCollectionNames`；新增内部 record `KbCollection(String kbId, String collectionName)` 替代 `KnowledgeBaseDO` 流转 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` | `:96` 删 `kbReadAccess` 字段，加 `RetrievalScopeBuilder`；`:121-133` 13 行内联 scope 计算 → 1 行 `builder.build(knowledgeBaseId)`；`:185` `retrieve(subIntents, accessScope, knowledgeBaseId)` → `retrieve(subIntents, scope)` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalService.java` | `chatForEval(AccessScope, String, String)` → `chatForEval(RetrievalScope, String)`；`:87` `retrieve(subIntents, scope, kbId)` → `retrieve(subIntents, scope)` |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/AnswerResult.java` | 若 javadoc 含 signature 引用，同步修订 |
| `bootstrap/src/main/java/com/knowledgebase/ai/ragent/eval/service/EvalRunExecutor.java` | `:116` `AccessScope systemScope = AccessScope.all()` → `RetrievalScope systemScope = RetrievalScope.all(run.getKbId())`；`:122` `chatForEval(systemScope, run.getKbId(), q)` → `chatForEval(systemScope, q)` |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java` | mock `kbReadAccess` 替换为 mock `RetrievalScopeBuilder`；`builder.build(any())` 返预期 scope |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalServiceTest.java` | 改 `chatForEval(AccessScope, String, String)` 调用为 `chatForEval(RetrievalScope, String)`；新增 case：mock builder + `Mockito.verify(builder, never())` 锁 eval 路径不绕路（review P1#2） |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/eval/service/EvalRunExecutorTest.java` | 验证 `chatForEval` 收到的 scope 是 `RetrievalScope.all(kbId)`：`accessScope instanceof All && targetKbId.equals(kbId) && kbSecurityLevels.isEmpty()` |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEnginePostProcessorChainTest.java` | 适配新签名 `retrieveKnowledgeChannels(subIntents, plan, scope)`；mock `KbMetadataReader` 替换 `KnowledgeBaseMapper` |
| `bootstrap/src/test/java/com/knowledgebase/ai/ragent/arch/PermissionBoundaryArchTest.java` | 加 3 条 ArchUnit 规则：`retrieval_scope_construction_allowlist` (PR4-1) / `retrieve_package_no_kb_mapper` (PR4-3) / `get_max_security_levels_single_caller` (PR4-4) |
| `docs/dev/design/2026-04-26-permission-roadmap.md` | §1 当前坐标 + §3 阶段 B 描述 + 成功标准 + §4 阶段依赖图（c0 内容） |

### Files explicitly NOT modified

- 任何 `framework/.../security/port/*` 除 `KbMetadataReader.java`（PR4 不动 PR2/PR3 已稳定的 7 个 port）
- `KbScopeResolver` / `KbScopeResolverImpl`（KB 列表查询路径，与检索 scope 解耦）
- `AuthzPostProcessor`（不变——继续读 `SearchContext.accessScope` + `kbSecurityLevels` 的便利字段；PR5 再清）
- `SearchContext`（保留 `accessScope`/`kbSecurityLevels` 老字段为便利访问，PR5 拆）
- `OpenSearchRetrieverService` / `MetadataFilterBuilder`（PR5 强制 `terms(metadata.kb_id)`）
- 前端 / DB schema / OpenSearch index template
- `LoginUser.maxSecurityLevel` 全局语义（PR6 / 阶段 C）
- 其他 9 个 `KbAccessService` 方法（已 `@Deprecated` 委托模式）

---

## Important Pre-Flight Notes (read before starting)

### Stage A 已完成的不变量必须严格继承

PR1 `A/B/C/D` + PR3 `PR3-1..PR3-6` 全部继续生效。**PR4 不重新协商**：

- **不变量 B（系统态显式性）**：未登录 + `requestedKbId != null` → `kbReadAccess.checkReadAccess(kbId)` 内部由 `bypassIfSystemOrAssertActor()` 抛 `ClientException("missing user context")`——builder 必须保持这条 fail-closed 路径。**不允许** builder 在 user==null 时直接返 empty 短路掉读权校验（review P2#1）。
- **不变量 C（HTTP 语义不变）**：未登录 / 越权访问指定 KB 仍抛 `ClientException`，HTTP 层由 `GlobalExceptionHandler` 包装为 `Result(code=非0, message=...)`。`RAGChatController` 当前是 SSE 接口，异常会在 SSE 建连前抛出（`SseEmitter` 由 `streamChat` 内部 try/catch 处理）。
- **PR3-1 Calculator 纯函数性 / PR3-2 Port current-user-only**：PR4 的 `RetrievalScopeBuilder` 与之精神一致——单一 ThreadLocal 触点（builder 内 `UserContext.hasUser()` + `UserContext.get()`），不再签名层暴露 `LoginUser`。
- **`AccessScope.All` sealed sentinel**：PR3 决策 8+ 处 `instanceof AccessScope.All` 短路（`AuthzPostProcessor`/`SpacesController`/etc）。`RetrievalScope.all(kbId)` 内部 `accessScope = AccessScope.all()` 必须保持 sentinel 不物化。

### `RAGChatServiceImpl:121-133` 当前 13 行内联结构

```java
// L110: String userId = UserContext.getUserId();
// L121-128:
AccessScope accessScope;
if (UserContext.hasUser() && userId != null) {
    accessScope = kbReadAccess.getAccessScope(Permission.READ);
} else {
    accessScope = AccessScope.empty();
}
// L131-133:
if (knowledgeBaseId != null) {
    kbReadAccess.checkReadAccess(knowledgeBaseId);
}
// L185: retrievalEngine.retrieve(subIntents, accessScope, knowledgeBaseId)
```

PR4 后：

```java
// L110: 不动 — userId 仍用于 conversation/memory/handler 路径
// L121-128 + L131-133 → 1 行
RetrievalScope scope = retrievalScopeBuilder.build(knowledgeBaseId);
// L185:
retrievalEngine.retrieve(subIntents, scope)
```

`kbReadAccess` 字段（`:96`）从 `RAGChatServiceImpl` 移除——PR3 起这是它唯一两处 caller，移除后清干净。

### `EvalRunExecutor:116-122` eval 路径迁移

eval/CLAUDE.md 已经把"`AccessScope.all()` 唯一合法持有者"写成硬约束（review P1-1 + spec §15.3）。PR4 把这个合法持有从 `AccessScope.all()` 升级为 `RetrievalScope.all(kbId)`：

```java
// PR4 之前
AccessScope systemScope = AccessScope.all();
ar = chatForEvalService.chatForEval(systemScope, run.getKbId(), item.getQuestion());

// PR4
RetrievalScope systemScope = RetrievalScope.all(run.getKbId());
ar = chatForEvalService.chatForEval(systemScope, item.getQuestion());
```

`RetrievalScope.all(kbId)` 的 `kbSecurityLevels=Map.of()` 显式表达"无 ceiling"，与 `AuthzPostProcessor:103` 的 `ceiling == null` 跳过等级过滤分支天然对齐。eval 路径**不调** `RetrievalScopeBuilder`（builder 读 `UserContext`，eval 跑在异步线程），靠 sentinel 自持权利——这与 eval/CLAUDE.md "零 ThreadLocal 新增" 硬约束一致。

### `KbMetadataReaderImpl` 的 `@TableLogic` 自动过滤

`KnowledgeBaseDO` 上 MyBatis Plus `@TableLogic` 注解，`selectById` / `selectBatchIds` 自动追加 `deleted=0`，**不需要** 手动 `.eq(deleted, 0)`。c2 实现两个新方法时直接复用既有模式：

```java
// 既有 listAllKbIds 用 selectList + Wrappers — c2 follow same style
@Override
public String getCollectionName(String kbId) {
    if (kbId == null) return null;
    KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);  // 自动过滤 deleted=0
    return kb != null ? kb.getCollectionName() : null;
}
```

### `MultiChannelRetrievalEngine` `KbReadAccessPort` 的二次调用

`:67 KbReadAccessPort kbReadAccess` 字段当前唯一用途是 `:262 buildSearchContext` 内调 `getMaxSecurityLevelsForKbs(...)`。PR4 c4 把这一路径删除（`SearchContext.kbSecurityLevels` 从 `RetrievalScope.kbSecurityLevels()` 直接取），**整个字段移除**。

### `VectorGlobalSearchChannel.getAccessibleKBs:172-178` 当前实现

```java
private List<KnowledgeBaseDO> getAccessibleKBs(SearchContext context) {
    AccessScope scope = context.getAccessScope();
    return knowledgeBaseMapper.selectList(Wrappers.lambdaQuery(KnowledgeBaseDO.class)).stream()
            .filter(kb -> scope.allows(kb.getId()))
            .filter(kb -> kb.getCollectionName() != null && !kb.getCollectionName().isBlank())
            .toList();
}
```

PR4 c4 改造：扫全表换成"按 scope 决策 + batch 取 collection names"：

```java
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

下游 `retrieveFromAllCollections(question, kbs, context, topK)` 形参类型从 `List<KnowledgeBaseDO>` → `List<KbCollection>`，访问从 `kb.getId()`/`kb.getCollectionName()` → `kb.kbId()`/`kb.collectionName()`。

### `ChatForEvalService` 当前 cards=List.of() 是有意

`:108` `cards = List.of()` 关闭 citation（review P2-1 + `eval_sources_disabled=true` 常量声明）。PR4 不动这块——`RetrievalScope` 改造仅替换 `(scope, kbId)` 入参对，prompt 构造路径字节级不变。

### ArchUnit allowlist 规则示例

`PermissionBoundaryArchTest.java` 在 PR3 commit `9a387942` 已经存在三条规则。PR4 加的三条规则模板（沿用 `noClasses().that().haveFullyQualifiedName(...)` 或 `classes().should().notDependOnClassesThat()` 模式）：

```java
@ArchTest
public static final ArchRule retrieval_scope_construction_allowlist = noClasses()
        .that().resideOutsideOfPackages(
                "com.knowledgebase.ai.ragent.rag.core.retrieve.scope..",
                "com.knowledgebase.ai.ragent.rag.core.retrieve.scope") // record 自身 + impl
        .should().callConstructor("com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope",
                "com.knowledgebase.ai.ragent.framework.security.port.AccessScope",
                "java.util.Map", "java.lang.String");

@ArchTest
public static final ArchRule retrieve_package_no_kb_mapper = noClasses()
        .that().resideInAPackage("com.knowledgebase.ai.ragent.rag.core.retrieve..")
        .should().dependOnClassesThat()
        .resideInAPackage("com.knowledgebase.ai.ragent.knowledge.dao.mapper..");
```

PR4-4 单 caller 用 grep 守门更便利（ArchUnit 数 method call 复杂），放 `permission-pr4-scope-builder-isolated.sh` Gate 3。

---

## Commit 0 — docs(security): roadmap §3 阶段 B 修订

**目标**：把 `RagRequest` 字段迁移段落删干净；PR4 范围明确为"scope builder + mapper retirement"；阶段 B 成功标准移除已不适用条目。第一刀，让 reviewer 对照新路线图审 c1-c5。

### Task 0.1: 路线图 §1 当前坐标更新

**Files:**
- Modify: `docs/dev/design/2026-04-26-permission-roadmap.md`

- [ ] **Step 1**: §1 表格中"当前位置"行从"PR3 已合并；下一步进入 PR4"改为"PR4 进行中（spec/plan 已落）"

- [ ] **Step 2**: §1"已完成"列表保持 PR1/PR2/PR3 不动；新增一行简述 PR4 spec/plan 落地状态

### Task 0.2: 路线图 §3 阶段 B PR4 描述重写

- [ ] **Step 1**: 找到 §3 阶段 B · PR4 段落

- [ ] **Step 2**: 删除"`RagRequest` 字段：`List<String> indexNames` → `List<String> kbIds + ScopeMode`"段落 + 前端 ChatStore 字段下线段落

- [ ] **Step 3**: 替换为下面内容（与 spec §0.1 对齐）：

> #### PR4 — Retrieval Scope Builder + Mapper 退役
>
> **目标**：scope 计算职责从 `RAGChatServiceImpl.streamChat` 抽出到独立 `RetrievalScopeBuilder`；`MultiChannelRetrievalEngine` + `VectorGlobalSearchChannel` 注入的 `KnowledgeBaseMapper` 退役为 `KbMetadataReader` port；`RetrievalScope` 一等公民 record 沿调用链一次贯通。
>
> **路线图原描述废止说明**：原条目"`RagRequest` 字段迁移"已不适用——经 PR4 spec §0.1 核实，`RagRequest` 类型不存在；HTTP 入口 `RAGChatController:49-52` 是 4 个 `@RequestParam`（`question / conversationId / knowledgeBaseId / deepThinking`）；前端 `ChatStore` 也不传 `indexNames`。PR1-PR3 期间已经把"前端值即 scope"的命题悄悄解决，路线图文本未同步刷新。
>
> 详细 spec：`docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md`

### Task 0.3: 阶段 B 成功标准修正

- [ ] **Step 1**: §3 阶段 B "成功标准" 块删除条目：
  - `RagRequest 不再含 indexNames 字段` ← 命题作废

- [ ] **Step 2**: 新增条目（标记完成的 PR）：
  ```
  RetrievalScopeBuilder 是 UserContext→RetrievalScope 唯一入口   ← PR4
  rag/core/retrieve 不依赖 knowledge.dao.mapper.*               ← PR4
  ChatForEvalService 走 RetrievalScope.all(kbId) sentinel        ← PR4
  MultiChannelRetrievalEngine 不再注入 KnowledgeBaseMapper       ← PR4 (路线图既有条目,标 PR4 完成)
  ```

- [ ] **Step 3**: 保留条目（留 PR5）：
  - `OpenSearch DSL 强制带 terms(metadata.kb_id) + range(security_level)` → PR5

### Task 0.4: 阶段依赖图同步（如有）

- [ ] **Step 1**: §4"阶段依赖图"PR3 prefix 改 PR4，确认 PR4 → PR5/PR6 弱依赖箭头正确

### Task 0.5: 提交 c0

- [ ] **Step 1**: `git add docs/dev/design/2026-04-26-permission-roadmap.md`

- [ ] **Step 2**: 提交：
  ```
  docs(security): roadmap §3 阶段 B 修订 — 删除 RagRequest 字段迁移命题,补 PR4/PR5 真实差距 (PR4 c0)

  PR1-PR3 期间前端契约已变成单 KB + 后端推算 scope, 但路线图 §3 阶段 B
  文本未同步刷新, 仍保留 RagRequest 字段迁移命题(类型不存在; 前端不传
  indexNames). PR4 spec §0.1 核实后命题作废, 路线图同步修订:
  - PR4 范围收敛为 scope builder + mapper retirement
  - 阶段 B 成功标准移除 RagRequest 条目
  - 新增 RetrievalScopeBuilder / KnowledgeBaseMapper retirement 标记
  - OpenSearch DSL terms(kb_id) 留 PR5

  Spec: docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md
  Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B
  ```

- [ ] **Step 3**: `mvn -pl bootstrap test -DskipTests=false` 仅文档变更，所有测试应继续绿（仅触发已编译测试）

---

## Commit 1 — feat(security): RetrievalScope record + Builder + 测试

**目标**：新代码无 caller，build 仍绿。所有现有测试继续通过。

### Task 1.1: 创建 `RetrievalScope` record

**Files:**
- Create: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java`

- [ ] **Step 1**: 创建文件，含标准 ASF license header + 下面内容：

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;

import java.util.Map;
import java.util.Objects;

/**
 * 检索 scope 一等公民 record,承载权限决策三件套:
 * <ul>
 *   <li>{@code accessScope} — 用户对 KB 的访问范围 (sealed All/Ids)</li>
 *   <li>{@code kbSecurityLevels} — 当前用户在每个 KB 上的天花板 (immutable, 可空)</li>
 *   <li>{@code targetKbId} — 单 KB 定向请求 (nullable; null 表示多 KB 全量)</li>
 * </ul>
 *
 * <p>由 {@link RetrievalScopeBuilder#build(String)} 在生产请求路径构造,
 * 异步/系统态路径 (如 EvalRunExecutor) 用 {@link #all(String)} sentinel.
 *
 * <p>PR4-1 不变量: 项目内构造 {@code new RetrievalScope(...)} 仅允许
 * 出现在本 record 自身(静态工厂内)、{@link RetrievalScopeBuilderImpl}、
 * 测试代码三处. ArchUnit 守门见 {@code PermissionBoundaryArchTest}.
 */
public record RetrievalScope(
        AccessScope accessScope,
        Map<String, Integer> kbSecurityLevels,
        String targetKbId) {

    public RetrievalScope {
        Objects.requireNonNull(accessScope, "accessScope");
        // review P2#3: AccessScope.Ids 可能持有可变 Set, 重新包装彻底冻结
        if (accessScope instanceof AccessScope.Ids ids) {
            accessScope = AccessScope.ids(java.util.Set.copyOf(ids.kbIds()));
        }
        kbSecurityLevels = kbSecurityLevels == null
                ? Map.of()
                : Map.copyOf(kbSecurityLevels);
    }

    /** 未登录或显式无授权: 空 ids + 空 map + null target. */
    public static RetrievalScope empty() {
        return new RetrievalScope(AccessScope.empty(), Map.of(), null);
    }

    /**
     * 全量放行 sentinel (SUPER_ADMIN 或系统态/eval 路径合法持有).
     * kbSecurityLevels 留空 — SUPER_ADMIN 跳过等级过滤由 AuthzPostProcessor
     * ceiling==null 分支自然处理.
     */
    public static RetrievalScope all(String targetKbId) {
        return new RetrievalScope(AccessScope.all(), Map.of(), targetKbId);
    }

    /** 单 KB 定向场景: targetKbId 非空. 多 KB 全量场景: null. */
    public boolean isSingleKb() {
        return targetKbId != null;
    }
}
```

### Task 1.2: 创建 `RetrievalScopeBuilder` interface

- [ ] **Step 1**: 创建 `bootstrap/.../rag/core/retrieve/scope/RetrievalScopeBuilder.java`，含 ASF header + 下面内容：

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

/**
 * 生产请求路径 UserContext → RetrievalScope 唯一构造入口 (PR4-1 不变量).
 *
 * <p>review P1#1: 接口 <b>不接受</b> LoginUser 参数 — builder 内部读 UserContext,
 * 单一 ThreadLocal 触点, 与 PR3 KbAccessSubjectFactory.currentOrThrow() 同模式.
 * 双参 build(LoginUser, String) 让 caller 有两种身份来源, 任何漂移都会让内部
 * KbReadAccessPort (current-user-only) 与外部 LoginUser 算出不一致 scope.
 *
 * <p>异步/系统态路径 (如 {@code EvalRunExecutor}) 不走此 builder,
 * 直接用 {@link RetrievalScope#all(String)} sentinel.
 */
public interface RetrievalScopeBuilder {

    /**
     * 构建当前请求的检索 scope.
     *
     * <p>决策顺序 (fail-closed 优先):
     * <ol>
     *   <li>{@code requestedKbId != null} → 先调 {@code kbReadAccess.checkReadAccess(kbId)}:
     *       无 user → 抛 {@code ClientException("missing user context")} (PR1 不变量 B);
     *       无权限 → 抛 {@code ClientException("无权访问")}.
     *       <b>不</b>因 user==null 短路为 empty (review P2#1, PR1 不变量 C HTTP 语义不变)</li>
     *   <li>requestedKbId == null 且 user/userId == null → {@link RetrievalScope#empty()}</li>
     *   <li>SUPER_ADMIN → {@link RetrievalScope#all(String)} (kbSecurityLevels 留空)</li>
     *   <li>其他 → AccessScope.ids(...) + 一次性算齐 getMaxSecurityLevelsForKbs(ids)</li>
     * </ol>
     *
     * @param requestedKbId nullable, 单 KB 定向时非空
     * @return 不可变 RetrievalScope; 越权抛 ClientException
     */
    RetrievalScope build(String requestedKbId);
}
```

### Task 1.3: 创建 `RetrievalScopeBuilderImpl`

- [ ] **Step 1**: 创建 `bootstrap/.../rag/core/retrieve/scope/RetrievalScopeBuilderImpl.java`：

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RetrievalScopeBuilderImpl implements RetrievalScopeBuilder {

    private final KbReadAccessPort kbReadAccess;

    @Override
    public RetrievalScope build(String requestedKbId) {
        // 1. fail-closed 优先: requestedKbId 非空 → checkReadAccess 由 PR1
        // bypassIfSystemOrAssertActor 守卫负责无 user/无权抛 ClientException
        if (requestedKbId != null) {
            kbReadAccess.checkReadAccess(requestedKbId);
            // checkReadAccess 通过 = 当前用户对 kbId 有 READ 权
        }

        // 2. requestedKbId == null 且无 user → empty (多 KB 全量场景未登录)
        LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
        if (user == null || user.getUserId() == null) {
            return RetrievalScope.empty();
        }

        // 3. SUPER_ADMIN → all sentinel
        if (user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.SUPER_ADMIN)) {
            return RetrievalScope.all(requestedKbId);
        }

        // 4. 其他: AccessScope.ids + 一次性算齐 security levels
        AccessScope accessScope = kbReadAccess.getAccessScope(Permission.READ);
        if (!(accessScope instanceof AccessScope.Ids ids)) {
            // SUPER_ADMIN 已在 step 3 处理; 此处遇到 All 异常防御
            return new RetrievalScope(accessScope, Map.of(), requestedKbId);
        }
        Set<String> kbIds = ids.kbIds();
        Map<String, Integer> levels = kbIds.isEmpty()
                ? Map.of()
                : kbReadAccess.getMaxSecurityLevelsForKbs(kbIds);
        return new RetrievalScope(accessScope, levels, requestedKbId);
    }
}
```

### Task 1.4: 创建 `RetrievalScopeTest`

- [ ] **Step 1**: 创建 `bootstrap/src/test/java/.../rag/core/retrieve/scope/RetrievalScopeTest.java`：

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalScopeTest {

    @Test
    void empty_returns_empty_ids_and_no_target() {
        RetrievalScope scope = RetrievalScope.empty();
        assertThat(scope.accessScope()).isInstanceOf(AccessScope.Ids.class);
        assertThat(((AccessScope.Ids) scope.accessScope()).kbIds()).isEmpty();
        assertThat(scope.kbSecurityLevels()).isEmpty();
        assertThat(scope.targetKbId()).isNull();
        assertThat(scope.isSingleKb()).isFalse();
    }

    @Test
    void all_returns_sentinel_with_targetKbId() {
        RetrievalScope scope = RetrievalScope.all("kb-1");
        assertThat(scope.accessScope()).isInstanceOf(AccessScope.All.class);
        assertThat(scope.kbSecurityLevels()).isEmpty();
        assertThat(scope.targetKbId()).isEqualTo("kb-1");
        assertThat(scope.isSingleKb()).isTrue();
    }

    @Test
    void all_with_null_targetKbId_supports_multi_kb_super_admin() {
        RetrievalScope scope = RetrievalScope.all(null);
        assertThat(scope.accessScope()).isInstanceOf(AccessScope.All.class);
        assertThat(scope.isSingleKb()).isFalse();
    }

    @Test
    void constructor_null_accessScope_throws() {
        assertThatThrownBy(() -> new RetrievalScope(null, Map.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("accessScope");
    }

    @Test
    void constructor_isolates_mutable_map() {
        Map<String, Integer> mutable = new HashMap<>();
        mutable.put("kb-1", 2);
        RetrievalScope scope = new RetrievalScope(AccessScope.empty(), mutable, null);
        mutable.put("kb-2", 3);  // 改原 map
        assertThat(scope.kbSecurityLevels()).containsOnlyKeys("kb-1");
    }

    @Test
    void constructor_null_levels_normalized_to_empty_map() {
        RetrievalScope scope = new RetrievalScope(AccessScope.empty(), null, null);
        assertThat(scope.kbSecurityLevels()).isNotNull().isEmpty();
    }

    @Test
    void constructor_isolates_mutable_kb_ids_set() {
        // review P2#3: AccessScope.Ids 持有可变 Set 时, RetrievalScope 必须深拷贝
        java.util.Set<String> mutableIds = new java.util.HashSet<>();
        mutableIds.add("kb-1");
        AccessScope unsafe = AccessScope.ids(mutableIds);
        RetrievalScope scope = new RetrievalScope(unsafe, Map.of(), null);
        mutableIds.add("kb-2");  // 改原 Set
        AccessScope.Ids frozen = (AccessScope.Ids) scope.accessScope();
        assertThat(frozen.kbIds()).containsOnly("kb-1");
    }
}
```

### Task 1.5: 创建 `RetrievalScopeBuilderImplTest`（spec §3.1 七 case）

- [ ] **Step 1**: 创建 `bootstrap/src/test/java/.../rag/core/retrieve/scope/RetrievalScopeBuilderImplTest.java`：

完整测试类按 spec §3.1 七 case 编写。骨架要点：
- `@ExtendWith(MockitoExtension.class)`；`@Mock KbReadAccessPort kbReadAccess`；`@InjectMocks RetrievalScopeBuilderImpl builder`
- 每个 case 用 `try-with-resources` `MockedStatic<UserContext>` 控制 `hasUser()` / `get()`，避免污染其他测试线程
- case (2) `requestedKbId!=null + 未登录` 用 `doThrow(new ClientException("missing user context")).when(kbReadAccess).checkReadAccess("kb-1")` 后断言抛出
- case (5) `SUPER_ADMIN + kbId` 验证返回 `RetrievalScope.all("kb-1")` 且 `kbSecurityLevels()` 为空
- case (6) DEPT_ADMIN 验证 `kbReadAccess.getAccessScope(READ)` 返 `AccessScope.ids(...)` 后 builder 调 `getMaxSecurityLevelsForKbs(ids)` 一次

- [ ] **Step 2**: 运行测试，全 7 case 应通过

### Task 1.6: 创建 `RetrievalScopeBuilderMismatchTest`（review P1#1 锁定）

- [ ] **Step 1**: 创建 `bootstrap/src/test/java/.../rag/core/retrieve/scope/RetrievalScopeBuilderMismatchTest.java`：

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁住 RetrievalScopeBuilder 单参契约 (review P1#1).
 * 双参 build(LoginUser, String) 引入双身份来源风险, 此测试确保不退化.
 */
class RetrievalScopeBuilderMismatchTest {

    @Test
    void build_method_has_only_single_string_parameter() throws NoSuchMethodException {
        Method build = RetrievalScopeBuilder.class.getDeclaredMethod("build", String.class);
        assertThat(build.getParameterCount()).isEqualTo(1);
        assertThat(build.getParameterTypes()[0]).isEqualTo(String.class);
    }

    @Test
    void no_login_user_overload_exists() {
        Method[] methods = RetrievalScopeBuilder.class.getDeclaredMethods();
        boolean hasLoginUserOverload = false;
        for (Method m : methods) {
            if (!"build".equals(m.getName())) continue;
            for (Class<?> p : m.getParameterTypes()) {
                if (p.getSimpleName().equals("LoginUser")) {
                    hasLoginUserOverload = true;
                    break;
                }
            }
        }
        assertThat(hasLoginUserOverload)
                .as("RetrievalScopeBuilder must not accept LoginUser to avoid double-identity drift")
                .isFalse();
    }
}
```

### Task 1.7: 提交 c1

- [ ] **Step 1**: `mvn spotless:apply` 然后 `mvn -pl bootstrap test -Dtest='RetrievalScope*Test'` 全绿

- [ ] **Step 2**: `git add bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/ bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/`

- [ ] **Step 3**: 提交：
  ```
  feat(security): RetrievalScope record + RetrievalScopeBuilder 接口 + 实现 (PR4 c1)

  PR4 阶段 B 入场: 把 scope 计算职责从 RAGChatServiceImpl.streamChat 抽出.
  本 commit 仅引入新类型, 无 caller; build 与既有测试全绿.

  - RetrievalScope record(accessScope, kbSecurityLevels, targetKbId)
    + empty()/all(kbId) sentinel + Map.copyOf 隔离
  - RetrievalScopeBuilder 单参契约 build(String) — review P1#1
    避免双身份漂移; 内部读 UserContext 单一 ThreadLocal 触点
  - fail-closed 优先序: requestedKbId 优先于 user==null — review P2#1
    保持 PR1 不变量 C HTTP 语义不变
  - RetrievalScopeBuilderMismatchTest 反射锁住单参签名

  Spec: docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md §1.1, §2.1
  Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B
  ```

---

## Commit 2 — feat(framework): KbMetadataReader 加 collection name 方法

**目标**：framework port 增加 2 个方法（单查 + 批量），knowledge 域同步实现。无业务逻辑变更，只为 c4 准备。

### Task 2.1: 扩展 `KbMetadataReader` interface

**Files:**
- Modify: `framework/src/main/java/com/knowledgebase/ai/ragent/framework/security/port/KbMetadataReader.java`

- [ ] **Step 1**: 加 import：

```java
import java.util.Map;
```

- [ ] **Step 2**: 在 `filterKbIdsByDept` 后追加 2 个方法：

```java
/**
 * 获取 KB 的 collection_name (物理向量索引名).
 * 单 KB 定向检索路径用 (MultiChannelRetrievalEngine.retrieveKnowledgeChannels).
 * @return collection_name; KB 不存在或已软删返回 null
 */
String getCollectionName(String kbId);

/**
 * 批量解析 kbIds 对应的 collection_name (过滤未删除且 collection_name 非空).
 * 全局/多 KB 检索路径用 (VectorGlobalSearchChannel), 避免 N 次 selectById.
 * 返回 map 仅包含存在且 collection_name 有效的 kbId.
 */
Map<String, String> getCollectionNames(Collection<String> kbIds);
```

### Task 2.2: 实现 `KbMetadataReaderImpl` 新方法

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KbMetadataReaderImpl.java`

- [ ] **Step 1**: 加 import：

```java
import java.util.Map;
import java.util.HashMap;
```

- [ ] **Step 2**: 在类末追加 2 个方法实现：

```java
@Override
public String getCollectionName(String kbId) {
    if (kbId == null) {
        return null;
    }
    KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);  // @TableLogic 自动过滤 deleted=0
    if (kb == null) {
        return null;
    }
    String name = kb.getCollectionName();
    return (name != null && !name.isBlank()) ? name : null;
}

@Override
public Map<String, String> getCollectionNames(Collection<String> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
        return Map.of();
    }
    List<KnowledgeBaseDO> kbs = knowledgeBaseMapper.selectList(
            Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                    .in(KnowledgeBaseDO::getId, kbIds)
                    .select(KnowledgeBaseDO::getId, KnowledgeBaseDO::getCollectionName));
    Map<String, String> result = new HashMap<>(kbs.size() * 2);
    for (KnowledgeBaseDO kb : kbs) {
        String name = kb.getCollectionName();
        if (name != null && !name.isBlank()) {
            result.put(kb.getId(), name);
        }
    }
    return Map.copyOf(result);
}
```

注意：`KbMetadataReaderImpl` 现有 import 已有 `java.util.List`，不需要重加；`Map` / `HashMap` 是新增。

### Task 2.3: 扩展 `KbMetadataReaderImplTest`

**Files:**
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KbMetadataReaderImplTest.java`（如不存在则创建）

- [ ] **Step 1**: 检查测试类是否存在：
  ```bash
  ls bootstrap/src/test/java/com/knowledgebase/ai/ragent/knowledge/service/impl/KbMetadataReaderImplTest.java
  ```
  若不存在，创建（@SpringBootTest + 真 PG fixture，与 `KbChunkSamplerImplTest` 同模式）；若存在，扩展。

- [ ] **Step 2**: 加 4 个 case：

```java
@Test
void getCollectionName_returns_null_when_kbId_null() {
    assertThat(reader.getCollectionName(null)).isNull();
}

@Test
void getCollectionName_returns_null_when_kb_not_exists() {
    assertThat(reader.getCollectionName("non-existent-id")).isNull();
}

@Test
void getCollectionName_returns_collection_for_existing_kb() {
    // fixture: t_knowledge_base 含 id='kb-fixture-1', collection_name='kb_fixture_1'
    String name = reader.getCollectionName("kb-fixture-1");
    assertThat(name).isEqualTo("kb_fixture_1");
}

@Test
void getCollectionNames_empty_input_returns_empty_map() {
    assertThat(reader.getCollectionNames(List.of())).isEmpty();
    assertThat(reader.getCollectionNames(null)).isEmpty();
}

@Test
void getCollectionNames_filters_missing_and_blank_collections() {
    // fixture: kb-fixture-1 (collection='kb_fixture_1'), kb-fixture-blank (collection=NULL)
    Map<String, String> result = reader.getCollectionNames(
            List.of("kb-fixture-1", "kb-fixture-blank", "non-existent-id"));
    assertThat(result).hasSize(1).containsEntry("kb-fixture-1", "kb_fixture_1");
}
```

如果当前 fixture 不含 `kb-fixture-blank`（collection_name=NULL 的种子），需要在测试 `@BeforeEach` 或 `@Sql` 注入。否则可以简化为 `kb-fixture-1` + `non-existent-id` 两条断言。

### Task 2.4: 提交 c2

- [ ] **Step 1**: `mvn spotless:apply` + `mvn -pl bootstrap test -Dtest=KbMetadataReaderImplTest` 全绿

- [ ] **Step 2**: 提交：
  ```
  feat(framework): KbMetadataReader 加 getCollectionName + getCollectionNames (PR4 c2)

  为 PR4 c4 mapper retirement 准备: knowledge 域 KnowledgeBaseMapper
  collection_name 查询通过 port 暴露, 让 rag/core/retrieve 不再跨域注入
  knowledge mapper.

  - 单查 getCollectionName(kbId): 软删/不存在/blank → null
  - 批量 getCollectionNames(Collection): 一次 SELECT IN; 返 immutable map
  - 测试覆盖 null input / 不存在 / blank collection 过滤

  Spec: §2.1 KbMetadataReader 新增 2 方法
  ```

---

## Commit 3 — refactor(rag): RAGChatServiceImpl + ChatForEval + RetrievalEngine 签名收敛

**目标**：三处签名同时收敛到 `RetrievalScope`。这是不可拆分的单 commit——`RetrievalEngine.retrieve` 签名变动强制 caller 同步。

### Task 3.1: `RetrievalEngine.retrieve` 签名收敛

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/RetrievalEngine.java`

- [ ] **Step 1**: 加 import：

```java
import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
```

- [ ] **Step 2**: `retrieve` 签名 `:83` 改：

```java
public RetrievalContext retrieve(List<SubQuestionIntent> subIntents,
                                 RetrievalScope scope) {
```

- [ ] **Step 3**: `:96-106` `tasks` lambda 改用 `scope`：

```java
.map(si -> CompletableFuture.supplyAsync(
        () -> buildSubQuestionContext(
                si,
                resolveSubQuestionPlan(si, globalRecall, globalRerank),
                scope
        ),
        ragContextExecutor
))
```

- [ ] **Step 4**: `buildSubQuestionContext` 签名收敛：

```java
private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, RetrievalPlan plan,
                                                   RetrievalScope scope) {
    // ...
    KbResult kbResult = retrieveAndRerank(intent, kbIntents, plan, scope);
    // ...
}
```

- [ ] **Step 5**: `retrieveAndRerank` 签名收敛：

```java
private KbResult retrieveAndRerank(SubQuestionIntent intent, List<NodeScore> kbIntents, RetrievalPlan plan,
                                    RetrievalScope scope) {
    List<SubQuestionIntent> subIntents = List.of(intent);
    List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(
            subIntents, plan, scope);
    // 后续不变
}
```

- [ ] **Step 6**: 删除 `AccessScope` import（不再直接使用）；保留若 SubQuestionContext 等仍引用

### Task 3.2: `MultiChannelRetrievalEngine.retrieveKnowledgeChannels` 签名收敛

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`

- [ ] **Step 1**: 加 import：

```java
import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
```

- [ ] **Step 2**: `retrieveKnowledgeChannels` 签名 `:80` 改：

```java
public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents,
                                                       RetrievalPlan plan,
                                                       RetrievalScope scope) {
    SearchContext context = buildSearchContext(subIntents, plan, scope);

    // 单知识库定向检索路径
    if (scope.isSingleKb()) {
        // c4 在此处把 knowledgeBaseMapper.selectById(...) 换成 kbMetadataReader.getCollectionName(...)
        // 本 commit 暂时保留 mapper 调用, c4 再切; 但需把 :86 的判断改为读 scope.targetKbId()
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(scope.targetKbId());
        if (kb == null || kb.getCollectionName() == null) {
            return List.of();
        }
        // ...rest unchanged
    }
    // ...
}
```

- [ ] **Step 3**: `buildSearchContext` 签名 `:256` 收敛 + 直接读 `scope.kbSecurityLevels()`：

```java
private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents,
                                          RetrievalPlan plan,
                                          RetrievalScope scope) {
    String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();
    return SearchContext.builder()
            .originalQuestion(question)
            .rewrittenQuestion(question)
            .intents(subIntents)
            .recallTopK(plan.recallTopK())
            .rerankTopK(plan.rerankTopK())
            .accessScope(scope.accessScope())
            .kbSecurityLevels(scope.kbSecurityLevels())  // 直接来自 scope, 不再二次拉
            .build();
}
```

- [ ] **Step 4**: 删除 `KbReadAccessPort kbReadAccess` 字段引入；删除 `import KbReadAccessPort` + `import UserContext`（buildSearchContext 不再需要 hasUser 判断）

> **注**：`KnowledgeBaseMapper` 字段 `:66` 暂留，c4 再删；本 commit 先解耦签名层。

### Task 3.3: `RAGChatServiceImpl.streamChat` 改用 builder

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`

- [ ] **Step 1**: 修改 imports：
  - 删除 `KbReadAccessPort` import（如不再被其他方法用）
  - 删除 `Permission` import
  - 删除 `AccessScope` import（如不再使用）
  - 加 `RetrievalScope` import：`com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope`
  - 加 `RetrievalScopeBuilder` import：`com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScopeBuilder`

- [ ] **Step 2**: `:96` 字段替换：
  ```java
  // 删除
  private final KbReadAccessPort kbReadAccess;
  // 新增
  private final RetrievalScopeBuilder retrievalScopeBuilder;
  ```

- [ ] **Step 3**: `:121-133` 13 行内联 scope 计算 → 1 行：
  ```java
  // 删除 :121-128 (accessScope 计算块) + :131-133 (checkReadAccess 块)
  // 替换为:
  RetrievalScope scope = retrievalScopeBuilder.build(knowledgeBaseId);
  ```

- [ ] **Step 4**: `:185` 调用：
  ```java
  // 之前
  RetrievalContext ctx = retrievalEngine.retrieve(subIntents, accessScope, knowledgeBaseId);
  // 之后
  RetrievalContext ctx = retrievalEngine.retrieve(subIntents, scope);
  ```

- [ ] **Step 5**: 不动 `:110 String userId = UserContext.getUserId()`——userId 仍用于 conversation/memory/handler 路径，scope 是新增维度

### Task 3.4: `ChatForEvalService.chatForEval` 签名收敛

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalService.java`

- [ ] **Step 1**: imports 调整：
  - 删除 `import AccessScope`
  - 加 `import RetrievalScope`

- [ ] **Step 2**: `:74-90` 签名 + 实现改：

```java
public AnswerResult chatForEval(RetrievalScope scope, String question) {
    RewriteResult rewrite = queryRewriteService.rewriteWithSplit(question, List.of());
    List<SubQuestionIntent> subIntents = intentResolver.resolve(rewrite);

    if (guidanceService.detectAmbiguity(rewrite.rewrittenQuestion(), subIntents).isPrompt()) {
        return AnswerResult.ambiguousIntentSkipped();
    }
    boolean allSystemOnly = subIntents.stream()
            .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
    if (allSystemOnly) {
        return AnswerResult.systemOnlySkipped();
    }

    RetrievalContext ctx = retrievalEngine.retrieve(subIntents, scope);
    if (ctx.isEmpty()) {
        return AnswerResult.emptyContext();
    }
    // ...rest unchanged (cards=List.of(), prompt 构造, llm 调用 不变)
}
```

- [ ] **Step 3**: 类级 javadoc `:54` 一行修订为：
  > `<p><b>RetrievalScope 由调用方注入，service 不自造</b>（review P1-1 + PR4 P1#2）：避免任何复用此 service 的入口默认越过 RBAC。eval 路径下唯一合法调用方 {@code EvalRunExecutor} 显式传 {@code RetrievalScope.all(kbId)}（spec §15.3 边界）。其他调用方必须传该入口下登录 principal 的真实 RetrievalScope。`

### Task 3.5: `EvalRunExecutor` 改构造 sentinel

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/eval/service/EvalRunExecutor.java`

- [ ] **Step 1**: imports 调整：
  - 删除 `import AccessScope`
  - 加 `import RetrievalScope`：`com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope`

- [ ] **Step 2**: `:115-122` 改：

```java
// P1-1 + PR4 P1#2: executor 是唯一合法持有 RetrievalScope.all() 的调用者
RetrievalScope systemScope = RetrievalScope.all(run.getKbId());

for (GoldItemDO item : items) {
    long t0 = System.currentTimeMillis();
    AnswerResult ar;
    try {
        ar = chatForEvalService.chatForEval(systemScope, item.getQuestion());
        // ...
    }
}
```

注意：`systemScope` 移出 for 循环（构造一次复用）；`run.getKbId()` 从 `chatForEval` 调用点移到 `RetrievalScope.all(...)` 构造点。

### Task 3.6: 测试 fixture 适配

**Files:**
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalServiceTest.java`
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEnginePostProcessorChainTest.java`
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/eval/service/EvalRunExecutorTest.java`

- [ ] **Step 1**: `RAGChatServiceImplSourcesTest`：
  - 把 `@Mock KbReadAccessPort` 改为 `@Mock RetrievalScopeBuilder`
  - 把 `when(kbReadAccess.getAccessScope(any())).thenReturn(...)` + `verify(kbReadAccess).checkReadAccess(...)` 等 stub 替换为：
    ```java
    when(retrievalScopeBuilder.build(any())).thenReturn(RetrievalScope.empty());
    // 或具体 case:
    when(retrievalScopeBuilder.build("kb-1")).thenReturn(
        new RetrievalScope(AccessScope.ids(Set.of("kb-1")), Map.of("kb-1", 2), "kb-1"));
    ```
  - `retrievalEngine.retrieve(...)` mock 签名同步改为 `retrieve(any(), any(RetrievalScope.class))`

- [ ] **Step 2**: `ChatForEvalServiceTest` 5 个既有 case 改：
  - 调用从 `chatForEval(AccessScope.all(), "kb-1", "q")` → `chatForEval(RetrievalScope.all("kb-1"), "q")`
  - mock `retrievalEngine.retrieve(any(), any(RetrievalScope.class))` 签名同步改

- [ ] **Step 3**: `ChatForEvalServiceTest` 新增 case（review P1#2 强制锁）：

```java
@Test
void chatForEval_does_not_invoke_RetrievalScopeBuilder() {
    // setup: stub retrievalEngine 返非空 ctx
    when(retrievalEngine.retrieve(any(), any(RetrievalScope.class)))
            .thenReturn(buildNonEmptyContext());
    // ...
    chatForEvalService.chatForEval(RetrievalScope.all("kb-1"), "test question");
    // 关键断言: builder 永不被调
    Mockito.verifyNoInteractions(retrievalScopeBuilder);
}
```

注意：`ChatForEvalService` 当前不注入 `RetrievalScopeBuilder`——这是 review P1#2 想锁住的状态。测试用 `@SpyBean RetrievalScopeBuilder retrievalScopeBuilder`（如有）+ `verifyNoInteractions` 验证；如无注入，断言改为 ArchUnit "ChatForEvalService 类不依赖 RetrievalScopeBuilder"（放 c5 守门更合适，本测试可改为构造期断言）。

> **决策**：把"`ChatForEvalService` 不依赖 `RetrievalScopeBuilder`" 移到 c5 ArchUnit 规则；本 task 只做单元 case 改造，不强行注入 spy。

- [ ] **Step 4**: `MultiChannelRetrievalEnginePostProcessorChainTest`：
  - `retrieveKnowledgeChannels(subIntents, plan, accessScope, kbId)` mock/调用全部改为 `(subIntents, plan, scope)`
  - 构造 `RetrievalScope` 用 sentinel 或显式 `new RetrievalScope(...)`（ArchUnit allowlist 含测试包，OK）

- [ ] **Step 5**: `EvalRunExecutorTest` 验证 sentinel 构造（review P1#2）：

```java
@Test
void runInternal_passes_RetrievalScope_all_with_targetKbId_to_chatForEval() {
    ArgumentCaptor<RetrievalScope> scopeCaptor = ArgumentCaptor.forClass(RetrievalScope.class);
    // setup: run.kbId = "kb-eval-1", items 含 1 条
    // ...
    executor.runInternal(runId);
    verify(chatForEvalService).chatForEval(scopeCaptor.capture(), any());
    RetrievalScope captured = scopeCaptor.getValue();
    assertThat(captured.accessScope()).isInstanceOf(AccessScope.All.class);
    assertThat(captured.targetKbId()).isEqualTo("kb-eval-1");
    assertThat(captured.kbSecurityLevels()).isEmpty();
}
```

### Task 3.7: 提交 c3

- [ ] **Step 1**: `mvn spotless:apply` + 定向跑改动相关测试：
  ```bash
  mvn -pl bootstrap -DskipTests compile  # 编译先过
  mvn -pl bootstrap test -Dtest='RAGChatServiceImpl*Test,ChatForEvalServiceTest,EvalRunExecutorTest,MultiChannelRetrievalEngine*Test,RetrievalScope*Test'
  ```
  **不要**跑全量 `mvn -pl bootstrap test`——根 `CLAUDE.md` 已记录 fresh checkout baseline failures（`MilvusCollectionTests` / `InvoiceIndexDocumentTests` / `PgVectorStoreServiceTest.testChineseCharacterInsertion` / `IntentTreeServiceTests.initFromFactory` / `VectorTreeIntentClassifierTests`，10 errors total），这些是 main 上的环境性失败，非本 PR 引入。验收以"PR4 涉及类的定向测试全绿 + 编译通过"为准（review P2#1）。

- [ ] **Step 2**: 提交：
  ```
  refactor(rag): RAGChatServiceImpl + ChatForEval + RetrievalEngine 签名收敛到 RetrievalScope (PR4 c3)

  scope 计算职责从 RAGChatServiceImpl.streamChat 13 行内联抽到独立
  RetrievalScopeBuilder; 三个签名层一次性收敛, 沿调用链贯通同一 record:

  - RAGChatServiceImpl: kbReadAccess 字段移除, 改注入 RetrievalScopeBuilder
    13 行 (accessScope 计算 + checkReadAccess + AccessScope.empty fallback) → 1 行
  - RetrievalEngine.retrieve / retrieveAndRerank / buildSubQuestionContext:
    (subIntents, AccessScope, String kbId) → (subIntents, RetrievalScope)
  - MultiChannelRetrievalEngine.retrieveKnowledgeChannels 同步收敛;
    buildSearchContext 不再二次拉 getMaxSecurityLevelsForKbs (来自 scope)
  - ChatForEvalService.chatForEval(AccessScope, String, String) →
    (RetrievalScope, String); kbId 进 scope.targetKbId
  - EvalRunExecutor 显式构造 RetrievalScope.all(run.getKbId()) sentinel
    eval 路径不调 builder, 保持 zero-ThreadLocal 硬约束

  Tests:
  - RAGChatServiceImplSourcesTest: mock RetrievalScopeBuilder
  - ChatForEvalServiceTest: 5 case 签名适配
  - EvalRunExecutorTest: verify scope=RetrievalScope.all(kbId) (review P1#2)
  - MultiChannelRetrievalEnginePostProcessorChainTest 签名适配

  Spec: §2.2 RAGChatServiceImpl + ChatForEval + RetrievalEngine
  ```

---

## Commit 4 — refactor(rag): MultiChannelRetrievalEngine + VectorGlobalSearchChannel mapper 退役

**目标**：rag/core/retrieve 子树清空 `KnowledgeBaseMapper` 依赖，全部改用 `KbMetadataReader` port。

### Task 4.1: `MultiChannelRetrievalEngine` 注入 `KbMetadataReader` 替换 `KnowledgeBaseMapper`

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`

- [ ] **Step 1**: imports 调整：
  - 删 `import KnowledgeBaseMapper` (`:29`)
  - 删 `import KnowledgeBaseDO` (`:28`)
  - 加 `import KbMetadataReader`：`com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader`

- [ ] **Step 2**: 字段替换 (`:66`)：
  ```java
  // 删除
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  // 新增 (放在 retrieverService 之后)
  private final KbMetadataReader kbMetadataReader;
  ```

- [ ] **Step 3**: `:86-90` 单 KB 路径改用 port：

```java
if (scope.isSingleKb()) {
    String collectionName = kbMetadataReader.getCollectionName(scope.targetKbId());
    if (collectionName == null) {
        return List.of();
    }
    RetrieveRequest req = RetrieveRequest.builder()
            .query(context.getMainQuestion())
            .topK(plan.recallTopK())
            .collectionName(collectionName)
            .metadataFilters(metadataFilterBuilder.build(context, scope.targetKbId()))
            .build();
    List<RetrievedChunk> chunks = retrieverService.retrieve(req);

    SearchChannelResult singleResult = SearchChannelResult.builder()
            .channelType(SearchChannelType.INTENT_DIRECTED)
            .channelName("single-kb-" + collectionName)
            .chunks(chunks)
            .confidence(1.0)
            .latencyMs(0)
            .build();
    return executePostProcessors(List.of(singleResult), context);
}
```

注意：`SearchChannelResult` 仍需 `kbId` 信息？检查现有 `single-kb-` 命名约定——保留，仅把 `kb.getCollectionName()` 换成局部变量 `collectionName`。

### Task 4.2: `VectorGlobalSearchChannel` 注入 `KbMetadataReader` + 内部 record `KbCollection`

**Files:**
- Modify: `bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java`

- [ ] **Step 1**: imports 调整：
  - 删 `import Wrappers`（`:21`）
  - 删 `import KnowledgeBaseDO`（`:24`）
  - 删 `import KnowledgeBaseMapper`（`:25`）
  - 加 `import KbMetadataReader`
  - 加 `import AccessScope`：`com.knowledgebase.ai.ragent.framework.security.port.AccessScope`
  - 加 `import java.util.Map`、`import java.util.Set`

- [ ] **Step 2**: 字段 + 构造函数调整：
  ```java
  private final KbMetadataReader kbMetadataReader;  // 替代 knowledgeBaseMapper

  public VectorGlobalSearchChannel(RetrieverService retrieverService,
                                   SearchChannelProperties properties,
                                   KbMetadataReader kbMetadataReader,        // 替换
                                   MetadataFilterBuilder metadataFilterBuilder,
                                   @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor innerRetrievalExecutor) {
      this.properties = properties;
      this.kbMetadataReader = kbMetadataReader;
      this.metadataFilterBuilder = metadataFilterBuilder;
      this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
  }
  ```

- [ ] **Step 3**: 在类末加内部 record（紧贴 `getType()` 之后）：

```java
/** 检索通道内部表示, 替代跨域 KnowledgeBaseDO. */
private record KbCollection(String kbId, String collectionName) {}
```

- [ ] **Step 4**: `getAccessibleKBs` 改写（`:172-178`）：

```java
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
    if (visibleKbIds.isEmpty()) {
        return List.of();
    }
    Map<String, String> nameMap = kbMetadataReader.getCollectionNames(visibleKbIds);
    return nameMap.entrySet().stream()
            .map(e -> new KbCollection(e.getKey(), e.getValue()))
            .toList();
}
```

- [ ] **Step 5**: `retrieveFromAllCollections` 形参类型改：

```java
private List<RetrievedChunk> retrieveFromAllCollections(String question,
                                                        List<KbCollection> kbs,
                                                        SearchContext context,
                                                        int topK) {
    List<CollectionParallelRetriever.CollectionTask> tasks = kbs.stream()
            .map(kb -> new CollectionParallelRetriever.CollectionTask(
                    kb.collectionName(),
                    metadataFilterBuilder.build(context, kb.kbId())))
            .toList();
    return parallelRetriever.executeParallelRetrieval(question, tasks, topK);
}
```

注意 `kb.getCollectionName()` → `kb.collectionName()`，`kb.getId()` → `kb.kbId()`。

### Task 4.3: 创建 `VectorGlobalSearchChannelKbMetadataReaderTest`

**Files:**
- Create: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannelKbMetadataReaderTest.java`

- [ ] **Step 1**: 完整测试骨架（review P2#3 — 必须验证 `RetrieveRequest` 的 `collectionName` + `metadataFilters` 内容）：

```java
package com.knowledgebase.ai.ragent.rag.core.retrieve.channel;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.rag.config.SearchChannelProperties;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorGlobalSearchChannelKbMetadataReaderTest {

    @Mock RetrieverService retrieverService;
    @Mock KbMetadataReader kbMetadataReader;
    @Mock MetadataFilterBuilder metadataFilterBuilder;

    VectorGlobalSearchChannel channel;

    @BeforeEach
    void setup() {
        // 用真实 SearchChannelProperties; vectorGlobal.enabled=true + 阈值 0.5
        SearchChannelProperties props = new SearchChannelProperties();
        props.getChannels().getVectorGlobal().setEnabled(true);
        props.getChannels().getVectorGlobal().setConfidenceThreshold(0.5);

        channel = new VectorGlobalSearchChannel(
                retrieverService, props, kbMetadataReader, metadataFilterBuilder,
                Executors.newSingleThreadExecutor());
    }

    private SearchContext buildContext(AccessScope scope) {
        return SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .intents(List.of(new SubQuestionIntent("q", List.<NodeScore>of())))  // 空 nodeScores → isEnabled=true
                .recallTopK(10)
                .rerankTopK(5)
                .accessScope(scope)
                .kbSecurityLevels(Map.of())
                .build();
    }

    @Test
    void all_scope_uses_listAllKbIds_then_getCollectionNames_with_per_kb_filters() {
        when(kbMetadataReader.listAllKbIds()).thenReturn(Set.of("kb-1", "kb-2"));
        when(kbMetadataReader.getCollectionNames(Set.of("kb-1", "kb-2")))
                .thenReturn(Map.of("kb-1", "c1", "kb-2", "c2"));
        MetadataFilter f1 = new MetadataFilter(VectorMetadataFields.SECURITY_LEVEL,
                MetadataFilter.FilterOp.LTE_OR_MISSING, 1);
        MetadataFilter f2 = new MetadataFilter(VectorMetadataFields.SECURITY_LEVEL,
                MetadataFilter.FilterOp.LTE_OR_MISSING, 2);
        when(metadataFilterBuilder.build(any(), eq("kb-1"))).thenReturn(List.of(f1));
        when(metadataFilterBuilder.build(any(), eq("kb-2"))).thenReturn(List.of(f2));
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        SearchChannelResult result = channel.search(buildContext(AccessScope.all()));

        assertThat(result.getChannelType()).isEqualTo(SearchChannelType.VECTOR_GLOBAL);
        verify(kbMetadataReader).listAllKbIds();
        verify(kbMetadataReader).getCollectionNames(Set.of("kb-1", "kb-2"));

        // 关键断言: 每个 kb 一次 retrieve, 携带正确 collectionName + metadataFilters
        ArgumentCaptor<RetrieveRequest> reqCap = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, times(2)).retrieve(reqCap.capture());
        Map<String, RetrieveRequest> byCollection = reqCap.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(RetrieveRequest::getCollectionName, r -> r));
        assertThat(byCollection.get("c1").getMetadataFilters()).containsExactly(f1);
        assertThat(byCollection.get("c2").getMetadataFilters()).containsExactly(f2);
    }

    @Test
    void ids_scope_skips_listAllKbIds_uses_ids_directly() {
        when(kbMetadataReader.getCollectionNames(Set.of("kb-1")))
                .thenReturn(Map.of("kb-1", "c1"));
        when(metadataFilterBuilder.build(any(), eq("kb-1"))).thenReturn(List.of());
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        channel.search(buildContext(AccessScope.ids(Set.of("kb-1"))));

        verify(kbMetadataReader, never()).listAllKbIds();
        verify(kbMetadataReader).getCollectionNames(Set.of("kb-1"));
        ArgumentCaptor<RetrieveRequest> reqCap = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(reqCap.capture());
        assertThat(reqCap.getValue().getCollectionName()).isEqualTo("c1");
    }

    @Test
    void empty_visible_kbs_returns_empty_chunks_without_calling_retriever() {
        when(kbMetadataReader.listAllKbIds()).thenReturn(Set.of());

        SearchChannelResult result = channel.search(buildContext(AccessScope.all()));

        assertThat(result.getChunks()).isEmpty();
        verifyNoInteractions(retrieverService);
        verify(kbMetadataReader, never()).getCollectionNames(any());
    }

    @Test
    void empty_ids_scope_skips_both_listAll_and_collectionNames() {
        // AccessScope.ids(empty) — 用户无任何授权 KB
        SearchChannelResult result = channel.search(buildContext(AccessScope.ids(Set.of())));

        assertThat(result.getChunks()).isEmpty();
        verifyNoInteractions(retrieverService);
        verify(kbMetadataReader, never()).listAllKbIds();
        verify(kbMetadataReader, never()).getCollectionNames(any());
    }
}
```

要点：
- `SearchChannelProperties` 用真实实例（不 mock），让 `isEnabled()` 走真实分支判断
- `SearchContext` 用 builder 构造空 `nodeScores` 触发 `isEnabled=true`（vector global 启用条件 1）
- `ArgumentCaptor<RetrieveRequest>` 捕获 retriever 每次接收的请求，验证 `collectionName` + `metadataFilters` 是 per-kb 正确组合（review P2#3 强调的 act/assert）
- 不需要 mock `parallelRetriever`——它内部走 `retrieverService.retrieve(req)`，mock retrieverService 即可截获

> **注**：若 `RetrieveRequest` 字段访问器是 `@Getter` 而非 record，断言改 `getCollectionName()` / `getMetadataFilters()`；`MetadataFilter.FilterOp` 枚举名以现状代码为准（`LTE_OR_MISSING`，已在 `OpenSearchRetrieverService` 验证）。

### Task 4.4: 提交 c4

- [ ] **Step 1**: `mvn spotless:apply` + 定向跑改动相关测试：
  ```bash
  mvn -pl bootstrap -DskipTests compile
  mvn -pl bootstrap test -Dtest='MultiChannelRetrievalEngine*Test,VectorGlobalSearchChannel*Test,KbMetadataReaderImplTest'
  ```
  全量测试受 baseline failures 干扰（review P2#1），不作为 commit 验收门槛。

- [ ] **Step 2**: 提交：
  ```
  refactor(rag): MultiChannelRetrievalEngine + VectorGlobalSearchChannel
  注入 KbMetadataReader, KnowledgeBaseMapper 退役 (PR4 c4)

  rag/core/retrieve 子树清空对 knowledge.dao.mapper.* 的依赖.
  跨域 mapper 注入是 P0 报告 SRC-3 的边界违反; PR4-3 不变量
  + ArchUnit 守门将在 c5 锁住.

  - MultiChannelRetrievalEngine: 删 KnowledgeBaseMapper 字段 + KnowledgeBaseDO,
    单 KB 路径改 kbMetadataReader.getCollectionName(kbId)
  - VectorGlobalSearchChannel: 删 mapper 字段 + selectList 全表扫,
    改 listAllKbIds + getCollectionNames batch; 内部 record KbCollection
    替代跨域 KnowledgeBaseDO 流转
  - VectorGlobalSearchChannelKbMetadataReaderTest 3 case 覆盖
    All/Ids/empty scope

  Spec: §2.2 MultiChannelRetrievalEngine / VectorGlobalSearchChannel
  ```

---

## Commit 5 — test(security): PR4 守门脚本 + ArchUnit + smoke

**目标**：把 PR4-1 / PR4-3 / PR4-4 不变量编码为 CI gate；smoke 文档对齐 §3.3 S5+S6。

### Task 5.1: 创建 `permission-pr4-scope-builder-isolated.sh`

**Files:**
- Create: `docs/dev/verification/permission-pr4-scope-builder-isolated.sh`

- [ ] **Step 1**: 沿用 PR3 脚本的 rg + git grep fallback 模式：

```bash
#!/usr/bin/env bash
# PR4 verification: RetrievalScopeBuilder isolation + mapper retirement.
# Spec: docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md §3.2
# Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 B · PR4

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${REPO_ROOT}"

if ! command -v rg >/dev/null 2>&1; then
  echo "ERROR: ripgrep (rg) not found on PATH; cannot run PR4 grep gates." >&2
  exit 2
fi

failures=0

# Gate 1: PR4-1 — new RetrievalScope( 仅在 allowlist 出现
EXPECTED_CTOR=$(cat <<'EOF'
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilderImpl.java
EOF
)
ACTUAL_CTOR=$(rg -l 'new RetrievalScope\(' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent | sort || true)
if [ "${ACTUAL_CTOR}" != "${EXPECTED_CTOR}" ]; then
  echo "FAIL: PR4-1 — 'new RetrievalScope(' constructor used outside allowlist:"
  echo "Expected:"
  echo "${EXPECTED_CTOR}"
  echo "Actual:"
  echo "${ACTUAL_CTOR}"
  failures=1
fi

# Gate 2: PR4-3 — rag/core/retrieve(含 channel/) 不依赖 KnowledgeBaseMapper
if rg -n 'import.*knowledge\.dao\.mapper\.KnowledgeBaseMapper' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve --quiet; then
  echo "FAIL: PR4-3 — rag/core/retrieve still imports KnowledgeBaseMapper:"
  rg -n 'import.*knowledge\.dao\.mapper\.KnowledgeBaseMapper' \
    bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve
  failures=1
fi

# Gate 3: PR4-4 — getMaxSecurityLevelsForKbs 在 rag 域只调用 1 次 (builder)
COUNT=$(rg -c 'getMaxSecurityLevelsForKbs\(' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag 2>/dev/null \
  | awk -F: '{sum+=$2} END {print sum+0}')
if [ "${COUNT}" -ne 1 ]; then
  echo "FAIL: PR4-4 — getMaxSecurityLevelsForKbs called ${COUNT} times in rag (expected 1, only RetrievalScopeBuilderImpl):"
  rg -n 'getMaxSecurityLevelsForKbs\(' bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag
  failures=1
fi

# Gate 4: ChatForEvalService 不注入 RetrievalScopeBuilder (review P1#2 显式锁)
if rg -n 'private\s+final\s+RetrievalScopeBuilder' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalService.java --quiet; then
  echo "FAIL: ChatForEvalService injects RetrievalScopeBuilder — eval path must use sentinel:"
  rg -n 'RetrievalScopeBuilder' bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalService.java
  failures=1
fi

# Gate 5: RetrievalScope.all( 静态工厂调用点 allowlist (review P1)
# RetrievalScope.all(kbId) 是绕过 RBAC 的合法 sentinel; 必须严格限制持有者,
# 否则普通请求路径直接调用即越权. allowlist:
#   - RetrievalScope.java         (record 自身, all() 静态工厂内调 new)
#   - RetrievalScopeBuilderImpl   (生产路径出口 SUPER_ADMIN 分支)
#   - EvalRunExecutor             (eval 路径合法持有者, spec §15.3)
#   - 测试文件                    (任意 *Test.java)
EXPECTED_SENTINEL=$(cat <<'EOF'
bootstrap/src/main/java/com/knowledgebase/ai/ragent/eval/service/EvalRunExecutor.java
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilderImpl.java
EOF
)
ACTUAL_SENTINEL=$(rg -l 'RetrievalScope\.all\(' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent | sort || true)
if [ "${ACTUAL_SENTINEL}" != "${EXPECTED_SENTINEL}" ]; then
  echo "FAIL: review P1 — 'RetrievalScope.all(' static factory called outside allowlist:"
  echo "Expected (production callers):"
  echo "${EXPECTED_SENTINEL}"
  echo "Actual:"
  echo "${ACTUAL_SENTINEL}"
  echo ""
  echo "If a new caller legitimately needs sentinel scope, add to allowlist + spec §1.2 PR4-1"
  failures=1
fi

if [ "${failures}" -ne 0 ]; then
  exit 1
fi

echo "PR4 grep gates passed"
```

- [ ] **Step 2**: `chmod +x docs/dev/verification/permission-pr4-scope-builder-isolated.sh`

### Task 5.2: 扩展 `PermissionBoundaryArchTest`

**Files:**
- Modify: `bootstrap/src/test/java/com/knowledgebase/ai/ragent/arch/PermissionBoundaryArchTest.java`

- [ ] **Step 1**: 加 import：
  ```java
  import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
  ```

- [ ] **Step 2**: 在类末追加 2 条 ArchUnit 规则：

```java
@ArchTest
public static final ArchRule retrieve_package_no_kb_mapper = noClasses()
        .that().resideInAPackage("com.knowledgebase.ai.ragent.rag.core.retrieve..")
        .should().dependOnClassesThat()
        .resideInAPackage("com.knowledgebase.ai.ragent.knowledge.dao.mapper..")
        .as("PR4-3: rag/core/retrieve must not depend on knowledge.dao.mapper " +
                "(use KbMetadataReader port)");

@ArchTest
public static final ArchRule chat_for_eval_does_not_inject_scope_builder = noClasses()
        .that().haveSimpleName("ChatForEvalService")
        .should().dependOnClassesThat()
        .haveSimpleName("RetrievalScopeBuilder")
        .as("review P1#2: eval path must use RetrievalScope.all(kbId) sentinel, " +
                "not invoke RetrievalScopeBuilder (which reads UserContext)");
```

> **注**：`new RetrievalScope(...)` 与 `RetrievalScope.all(...)` 的 allowlist 用 ArchUnit 表达较繁琐（需 `callConstructor` / `callMethod` API + 跨包 allowlist 元组），决策：grep 守门已在脚本 Gate 1 + Gate 5 覆盖，ArchUnit 此处只锁两条**结构性**约束。Gate 5 是 review P1 真正的 RBAC 绕路防线——构造点开放 record 自身和 builder，但 `RetrievalScope.all(kbId)` 静态工厂的合法持有者只有 `EvalRunExecutor` 一处生产代码，新增持有者必须显式扩 allowlist + spec §1.2 PR4-1 登记。

### Task 5.3: 创建 `permission-pr4-smoke.md`

**Files:**
- Create: `docs/dev/verification/permission-pr4-smoke.md`

- [ ] **Step 1**: 内容：

```markdown
# Permission PR4 Manual Smoke Paths

> **Spec:** docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md §3.3
> **前置**：完成 PR1-PR3 smoke (`permission-pr1-smoke.md`) 全 4 条; 启动 backend 监听 9090.

## S5 — FICC_USER 越权访问指定 KB → 拒绝

**目的**：验证 builder 的 `checkReadAccess` 在 requestedKbId 越权时触发 fail-closed.

```bash
# Step 1: FICC_USER 登录拿 token
TOKEN=$(curl -sX POST http://localhost:9090/api/ragent/login \
  -d 'username=ficc_user&password=...' | jq -r '.data.token')

# Step 2: 调指定 OPS-COB KB (FICC 无权)
curl -sN -H "Authorization: $TOKEN" \
  "http://localhost:9090/api/ragent/rag/v3/chat?question=test&knowledgeBaseId=<OPS-COB-id>"
```

**预期**：SSE 立即收到 ClientException 包装的拒绝（`Result.code != 0`，`message` 含"无权访问"）；HTTP status 200（错误在 Result body）。

## S6 — 未登录访问指定 KB → missing user context (review P2#1 防回归)

**目的**：验证 fail-closed 顺序——`requestedKbId != null` 优先于 `user==null`，HTTP 语义维持 PR1 不变量 C.

```bash
# 不带 Authorization header
curl -sN "http://localhost:9090/api/ragent/rag/v3/chat?question=test&knowledgeBaseId=<any-id>"
```

**预期**：`Result.code != 0`，`message` 含"missing user context"；**不是**返回"未检索到与问题相关的文档内容。"的空回复。

若返回空回复 → builder fail-closed 顺序回归（user==null 先于 requestedKbId 判断），违反 review P2#1。

## S7 — eval 路径 sentinel 持有验证 (smoke)

**目的**：验证 EvalRunExecutor 走 RetrievalScope.all(kbId) sentinel 不调 builder.

后端日志启动 DEBUG `com.knowledgebase.ai.ragent.rag.core.retrieve.scope`，在管理端触发一次 ACTIVE eval run，确认日志中**无** RetrievalScopeBuilder 调用记录（builder 在 eval 线程不应出现）。

## 复用既有 smoke

PR1-3 4 条手工路径继续生效，本 PR 不重做。
```

### Task 5.4: 提交 c5

- [ ] **Step 1**: `mvn -pl bootstrap test -Dtest=PermissionBoundaryArchTest` 全绿

- [ ] **Step 2**: `bash docs/dev/verification/permission-pr4-scope-builder-isolated.sh` 全绿

- [ ] **Step 3**: 提交：
  ```
  test(security): PR4 守门脚本 + ArchUnit + smoke (PR4 c5)

  把 PR4-1 / PR4-3 / PR4-4 不变量编码为 CI gate; smoke 文档
  对齐 spec §3.3 S5/S6 (review P2#1 防回归).

  - permission-pr4-scope-builder-isolated.sh: 4 个 grep gate
    Gate 1 PR4-1: new RetrievalScope( 构造点 allowlist
    Gate 2 PR4-3: rag/core/retrieve 不 import KnowledgeBaseMapper
    Gate 3 PR4-4: getMaxSecurityLevelsForKbs 在 rag 域单 caller
    Gate 4 review P1#2: ChatForEvalService 不注入 RetrievalScopeBuilder
  - PermissionBoundaryArchTest:
    + retrieve_package_no_kb_mapper (PR4-3)
    + chat_for_eval_does_not_inject_scope_builder (review P1#2)
  - permission-pr4-smoke.md S5 (越权拒绝) + S6 (未登录单 KB fail-closed)
    + S7 (eval sentinel 持有)

  Spec: §3.2 守门脚本, §3.3 手工 smoke
  ```

---

## Post-PR Verification Checklist

完成 c5 后，本 PR 准备开 review 前依次确认：

- [ ] **F1**：`mvn clean install -DskipTests spotless:check` 全绿
- [ ] **F2**：定向测试全绿（review P2#1：不跑 `mvn -pl bootstrap test`，因为 main 上 `MilvusCollectionTests` / `InvoiceIndexDocumentTests` / `PgVectorStoreServiceTest.testChineseCharacterInsertion` / `IntentTreeServiceTests.initFromFactory` / `VectorTreeIntentClassifierTests` 共 10 个 baseline 错误依赖 Milvus container / pgvector extension / seeded KB data，**非 PR4 引入**）：
  ```bash
  mvn -pl bootstrap test -Dtest='RetrievalScope*Test,RetrievalScopeBuilder*Test,RAGChatServiceImpl*Test,ChatForEvalServiceTest,EvalRunExecutorTest,MultiChannelRetrievalEngine*Test,VectorGlobalSearchChannel*Test,KbMetadataReaderImplTest,PermissionBoundaryArchTest'
  ```
  全量跑前先记录当前 main baseline failure 列表为基线（`git stash + mvn -pl bootstrap test 2>&1 | tee /tmp/main-baseline.txt && git stash pop`），PR4 全量结果对比应只剩同样的 10 个错误，无新增。
- [ ] **F3**：`bash docs/dev/verification/permission-pr1-controllers-clean.sh` 仍绿（PR4 不破 PR1 守门）
- [ ] **F4**：`bash docs/dev/verification/permission-pr2-kb-access-retired.sh` 仍绿
- [ ] **F5**：`bash docs/dev/verification/permission-pr3-leak-free.sh` 仍绿
- [ ] **F6**：`bash docs/dev/verification/permission-pr4-scope-builder-isolated.sh` 全绿（5 个 gate）
- [ ] **F7**：`grep -rn 'kbReadAccess' bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` 无结果
- [ ] **F8**：`grep -rn 'KnowledgeBaseMapper' bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/` 无结果
- [ ] **F9**：手工 smoke S5 + S6 + S7 通过
- [ ] **F10**：路线图 §3 阶段 B 描述已 reflect 真实 PR4 范围（c0 落地）。**注**：路线图 §1 当前坐标"PR4 完成"标记不在 PR4 范围内——PR merge 后由维护者翻牌（避免 c0 文档预声明状态与 commit 历史不一致；review P3）。

---

## Rollback Strategy

PR4 是单分支 6 commit。回滚粒度：

- **仅 c5 出问题**（脚本/ArchUnit 误报）→ `git revert <c5>`，c0-c4 保留
- **c4 后发现 single-KB 检索回归** → revert c4 + c3，保留 c0-c2（builder 已落但未启用，安全保留）
- **c3 发现 builder 语义错误**（如 fail-closed 顺序）→ revert c3-c5，保留 c0-c2
- **整个 PR 推迟** → revert all 6 commits（c0 文档变更也回退）

不允许 c1-c2 单独存活——record + port method 必须有 caller，否则触发 ArchUnit 死代码守门。

---

## References

- **Spec**：`docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md`
- **Roadmap**：`docs/dev/design/2026-04-26-permission-roadmap.md` §3 阶段 B
- **PR1 plan（模板参考）**：`docs/superpowers/plans/2026-04-26-permission-pr1-controller-thinning.md`
- **PR3 plan（模板参考 + ArchUnit 模式）**：`docs/superpowers/plans/2026-04-27-permission-pr3-access-calculator-threadlocal-guards.md`
- **PR1 spec（不变量 A/B/C/D 来源）**：`docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md`
- **PR3 spec（不变量 PR3-1..PR3-6 + record/factory 模式）**：`docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md`
- **eval/CLAUDE.md**：`bootstrap/src/main/java/com/knowledgebase/ai/ragent/eval/CLAUDE.md`（"`AccessScope.all()` 唯一合法持有者"硬约束）
- **现状代码锚点**：
  - `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java:96, :121-133, :185`
  - `bootstrap/.../rag/core/ChatForEvalService.java:74, :87`
  - `bootstrap/.../rag/core/retrieve/RetrievalEngine.java:83, :214`
  - `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java:66, :87, :256-276`
  - `bootstrap/.../rag/core/retrieve/channel/VectorGlobalSearchChannel.java:50, :172-178`
  - `bootstrap/.../rag/core/retrieve/postprocessor/AuthzPostProcessor.java:83-109`
  - `bootstrap/.../eval/service/EvalRunExecutor.java:115-122`
  - `framework/.../security/port/KbReadAccessPort.java`
  - `framework/.../security/port/AccessScope.java`
  - `framework/.../security/port/KbMetadataReader.java`
