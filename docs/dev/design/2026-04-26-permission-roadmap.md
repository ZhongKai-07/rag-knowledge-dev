# 权限管理体系完善路线图

- **日期**：2026-04-26（v2 — PR1 完成后融合）
- **状态**：Draft（PR 方向引导文档）
- **来源**：综合三份文档后的统一推进计划
  - `docs/dev/research/enterprise_permissions_architecture.md`（业务/产品视角，phase 0-8）
  - `docs/dev/followup/2026-04-26-architecture-p0-audit-report.md`（架构/工程视角，模块边界 + 跨域 mapper + ports）
  - `docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md`（PR1 spec — 已并入本图作为阶段 A 的第一刀）
- **生产前提**：向量后端只考虑 OpenSearch；Milvus / pg 通过启动期 fail-fast 禁用，不再为其补能力

---

## 0. 文档定位

这份路线图是后续每一个权限相关 PR 的**方向引导**：

- 想做权限相关改动前，先来此处定位"我这个 PR 属于哪一阶段、依赖哪一阶段、不能跨过哪一阶段"
- 每个 PR 提交前，对照对应阶段的"成功标准"自检
- 任一阶段被新事实推翻时，回头修订本文档；PR 不要绕过路线图

**不在此处出现的工作**：单点 bug 修复、UI 微调、复盘、零散安全加固。这份文档只服务于"权限体系演进"主线。

---

## 1. 当前坐标（持续更新）

| 维度 | 当前位置 |
|---|---|
| 业务路线 | enterprise doc Phase 1 末 / Phase 2 前——`role_kb_relation` 已落库，`max_security_level` 列已加；sharing UI / audit_log / access_request 未做 |
| 架构状态 | 阶段 A 已完成；**阶段 B 已完成**（PR4 已合并 + PR5 c1-c5 落地） |
| 已完成 | **PR1**（2026-04-26）— controller→service 边界下沉、`LoginUser.system` + `UserContext.isSystem()`、`bypassIfSystemOrAssertActor()` 守卫；20 个 service 入口、4 个参数化边界测试、`rg` 守门脚本均已落地。**PR2**（2026-04-27）— `KbScopeResolver`、RAG/KB/user 热路径迁到 `KbReadAccessPort` / `KbManageAccessPort` / `CurrentUserProbe`，`KbAccessService` 降级为 deprecated port 委托者。**PR3**（2026-04-27）— `KbAccessSubject` / factory / calculator、current-user-only read port、`AccessServiceImpl` 泄漏修复、handler ThreadLocal guard、vector fail-fast、ArchUnit + grep gates、T1/T2 测试落地。**PR4**（2026-04-28，PR #27 合并 commit `52e43c5`）— `RetrievalScope` record + `RetrievalScopeBuilder` + `KbMetadataReader.getCollectionName(s)` 落地；`RAGChatServiceImpl` / `RetrievalEngine` / `MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` / `ChatForEvalService` 签名收敛到 `RetrievalScope`，`KnowledgeBaseMapper` 在检索热路径退役；PR4 守门脚本 + ArchUnit + S5/S6 smoke 通过。**PR5**（2026-04-28）— metadata filter contract hardening：c0 路线图修订 + spec + plan；c1 `DefaultMetadataFilterBuilder` 永远输出 `kb_id IN [kbId]` + `security_level LTE_OR_MISSING level/MAX_VALUE`；c2 `OpenSearchRetrieverService.enforceFilterContract` fail-fast + 显式 `catch (IllegalStateException) { throw e; }` 防御（c5 finalize 把 rethrow guard 推到三通道 fan-out 路径，QSI-3 契约违规真正传播到 SSE）；c3 三通道（`MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` / `IntentParallelRetriever`）filter alignment 契约测试；c4 grep 守门（`permission-pr5-filter-contract.sh`）+ `MetadataFilterConstructionArchTest` ArchUnit + smoke S5/S5b/S6/S7（含 OpenSearch 历史数据兼容性发布阻塞项）；c5 `SearchContext.metadata` 零 caller 字段 cleanup |
| 进行中 | **阶段 C / PR6** 准备中——`max_security_level` 粒度修正（管理决策路径仍用全局 `LoginUser.maxSecurityLevel` 待评估保留 vs 迁移） |
| 下一步 | **PR6 spec/plan 待落**——审计 `KbAccessSubjectFactoryImpl:44` / `KbAccessCalculator:132` / `RoleServiceImpl:485` 三个 caller，决议保留为"调用者授权能力上限"语义还是迁移到 (role × kb) 二维查询 |
| 新增并行线 | **阶段 H — 意图与工具治理**（PR-INT-1/2/3）— 由 Collateral 工程（场景 3 智能筛查 + 场景 4 协议提取）引入；为 KB 级意图节点 + MCP 工具白名单建立治理面，与阶段 C/D/E 并行安全。详见 §3 阶段 H |

---

## 2. 阶段架构契约（PR1 已确立的不变量）

这部分是 PR1 spec 抽取出来的、对**所有后续 PR** 都有约束力的不变量。后续 PR 必须**继承**而非重新协商。

### 2.1 分层契约图

```
┌──────────────────────────────────────────────────────────┐
│ Controller                                               │
│   - 仅承担 HTTP 协议职责                                 │
│     （参数解析 / VO 组装 / Result 包装）                 │
│   - 禁止调用 kbAccessService.check*（PR1 后 grep 守门）  │
│   - scope/list 收敛逻辑暂留（阶段 A · PR2 收敛到         │
│     KbScopeResolver）                                    │
│   - @SaCheckRole 平台级宏门保留（特别是                  │
│     KnowledgeChunkController）                           │
├──────────────────────────────────────────────────────────┤
│ Service（KB / Document / Chunk / Role-Binding）          │
│   - public user-entry 方法 = 信任边界（PR1 已建立）      │
│   - 首行调用 KbAccessService.check*（阶段 A 后改用 port）│
│   - 内部 helper 仅靠 javadoc 标记，不开 HTTP 入口        │
│   - 系统态由 KbAccessService 统一旁路                    │
├──────────────────────────────────────────────────────────┤
│ KbAccessService（PR1 加守卫；阶段 A 后退役为 port 委托者）│
│   if (UserContext.isSystem()) return;                    │
│   if (!UserContext.hasUser() ||                          │
│       UserContext.getUserId() == null)                   │
│       throw new ClientException("missing user context"); │
├──────────────────────────────────────────────────────────┤
│ MQ / Schedule                                            │
│   - 入口必须 LoginUser.builder()...system(true).build()  │
│   - UserContext.set(systemUser) → 调 service             │
└──────────────────────────────────────────────────────────┘
```

### 2.2 不变量 A/B/C/D（来自 PR1 spec §1.1，所有后续 PR 必须保持）

- **A — 边界唯一性**：KB / Document / Chunk service 的 public user-entry 方法是**唯一**授权边界。新 caller（test / 内部 service / 未来 SDK）自动被治理
- **B — 系统态显式性**：`UserContext.isSystem()` **仅**当 `LoginUser.system=true` 显式置位时返回 `true`。HTTP 入口忘记 attach user → `isSystem()=false` AND `getUserId()=null` → 抛 `ClientException("missing user context")`，**不**静默降级为 system bypass
- **C — HTTP 语义不变**：跨 PR 改动权限实现时，"谁能调哪个 HTTP endpoint"的产品规则保持稳定。任何放宽 / 收紧都需独立产品决策 PR，不得隐式跟随重构
- **D — Service vs HTTP 边界分离**：service 层边界可能比 HTTP 边界宽松（如 `KnowledgeChunkController` HTTP 仅放 SUPER_ADMIN，service 放 DEPT_ADMIN 改 own-dept chunk）。这种不对称是**有意**的——HTTP 改产品决策走单独 PR

### 2.3 反模式清单（任意 PR 不允许出现）

- ❌ 在 controller 内联 `kbAccessService.check*`（PR1 后 grep 守门）
- ❌ 新代码注入 `KbAccessService`（PR2 后 ArchUnit 强制）
- ❌ 异步路径调用 `UserContext.getUserId()`（须从参数显式传入）
- ❌ 检索路径直接信任前端传的 `kbIds` / `indexNames`，未走 `RetrievalScopeBuilder`（阶段 B 后）
- ❌ OpenSearch query DSL 不带 `metadata.kb_id` 或 `metadata.security_level` filter（阶段 B 后）
- ❌ 在 service 方法手工写 `auditLog.record(...)`（审计必须切面接入；阶段 E 后）
- ❌ 申请流直接写 `t_role_kb_relation`（必须经过 sharing port；阶段 F）
- ❌ 前端 `pages/` 内联 `isSuperAdmin / isDeptAdmin`（必须走 `usePermissions` / `permissions.ts`）
- ❌ 用户全局 `LoginUser.maxSecurityLevel` 进入决策路径（阶段 C 后）
- ❌ `*AsSystem` 双 API（如 `checkAccessAsSystem`）——系统态由 `UserContext` 单一信号承载，禁止 caller 端切换
- ❌ 任何配置切到 Milvus / pg 而无 `rag.vector.allow-incomplete-backend=true` dev override
- ❌ `IntentNode.scope='GLOBAL'` 同时设了 `mcp_tool_id`（GLOBAL 意图是系统交互层，不绑业务工具；阶段 H 后 DB CHECK 强制）
- ❌ 写 `t_intent_node` `scope='KB'` 行不经 `KbManageAccessPort.canManage(kbId)`（阶段 H 后 ArchUnit 强制）
- ❌ 设置 `IntentNode.mcpToolId` 时不经 `KbMcpBindingService.isEnabled(kbId, toolId)` 校验（写入时校验，不只运行时；阶段 H 后强制）
- ❌ `RetrievalEngine.invokeMCPTool` 调用未在 `t_kb_mcp_binding` 注册的工具（阶段 H 后运行时静默降级 + 告警，不抛异常）
- ❌ 在 service 方法手工创建 `MCPToolExecutor` 实例绕过 `MCPToolRegistry`（注册中心是单一真相源）

---

## 3. 七阶段路线（A-G）

> 每阶段含五块：**业务侧目标 / 架构侧目标 / commit 序列模板 / 成功标准 / 引用源**。PR 落在哪个阶段就执行哪个阶段的清单。

### 阶段 A：边界整理期（PR1-PR3）

把权限决策**收敛到三个 port**，不加任何新业务功能。这是后续所有阶段的入场券。

**业务侧**：零增量。

**架构侧**：

#### PR1 ✅ 已完成（2026-04-26）— Controller Thinning

详细 spec：`docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md`
实施计划：`docs/superpowers/plans/2026-04-26-permission-pr1-controller-thinning.md`

落地内容：
- `LoginUser.system` + `UserContext.isSystem()` 引入
- MQ/Schedule 显式 `system=true`（`KnowledgeDocumentChunkConsumer` + `ScheduleRefreshProcessor`）
- `KbAccessService` `bypassIfSystemOrAssertActor()` 守卫——"无用户=放行" → fail-closed
- 14 处 controller 内联检查 → 20 处 service 入口（KB 3 + Doc 9 + Chunk 6 + RoleBinding 2）
- 4 个参数化边界测试类、T1 fail-closed 契约测试
- `docs/dev/verification/permission-pr1-controllers-clean.sh` grep 守门脚本
- `docs/dev/verification/permission-pr1-smoke.md` 4 条手工 smoke 路径

PR1 残留 / 显式延期项（**全部由 PR2-PR3 接手**）：
- `KbAccessCalculator` 抽取（消除 `AccessServiceImpl:325 / :183` caller-context 泄漏）→ PR3
- `KbScopeResolver` 统一（`KnowledgeBaseController.applyKbScope` + `SpacesController` + `KnowledgeDocumentController.search` 的 accessibleKbIds 计算）→ PR2
- `KnowledgeBaseServiceImpl.update`（无生产 caller）→ 引入 caller 时再加 check
- chunk internal helper 物理 visibility 收紧（`KnowledgeChunkInternalService` 拆分）→ PR3 后续

#### PR2 ✅ 已完成（2026-04-27）— `KbAccessService` 热路径退役

**目标**：把 `KbAccessService` 在 RAG 热路径和 KB 写路径上的引用清零，迁移到 `KbReadAccessPort` / `KbManageAccessPort` / `CurrentUserProbe` 三个 port。

**S1 必做（热路径）**：
- `RAGChatServiceImpl:134` — SSE 问答指定 KB 校验 → `KbReadAccessPort.checkReadAccess`
- `MultiChannelRetrievalEngine:262` — 检索热路径批量安全等级 → `KbReadAccessPort.getMaxSecurityLevelsForKbs`
- `KnowledgeBaseServiceImpl` / `KnowledgeDocumentServiceImpl`（PR1 刚加的 20 个入口）内部从 `kbAccessService.check*` 迁到对应 port
- `KnowledgeBaseServiceImpl:97,206` — `resolveCreateKbDeptId` / `unbindAllRolesFromKb` 改用对应 port

**S2 同 PR 或拆 PR2.5**：
- `SpacesController:54-68` → `CurrentUserProbe + KbReadAccessPort.getAccessScope`
- `DashboardController:63` → `CurrentUserProbe`
- `user/controller/*` → `UserAdminGuard` + `CurrentUserProbe` + `SuperAdminInvariantGuard`
- `RoleServiceImpl` / `UserServiceImpl` 内部的 `simulateActiveSuperAdminCountAfter` / `evictCache` / `isSuperAdmin` → 对应 guard / cache admin port

**Commit 序列模板**：
```
commit 1: 引入 KbScopeResolver（统一 controller / SpacesController / search 三处 scope 计算）
commit 2: RAG 热路径迁移到 KbReadAccessPort（RAGChatServiceImpl + MultiChannelRetrievalEngine + VectorGlobalSearchChannel）
commit 3: KB/Document service 内部从 KbAccessService 迁到 KbManageAccessPort/KbReadAccessPort
commit 4: user 域 controller/service 迁到 CurrentUserProbe + UserAdminGuard + SuperAdminInvariantGuard
commit 5: KbAccessService 接口/实现降级为 @Deprecated（保留实现作为 port 委托者，不物理删除）
commit 6: 启用 ArchUnit 静态规则——新代码不得注入 KbAccessService（先告警，allowlist 仅含 port 委托类）
```

**关键设计**：commit 5 只 `@Deprecated`，**不物理删除**——保留 1-2 个 PR 缓冲期。等 PR3 完成 + RAG/KB/user 全部迁完，再单独开 cleanup PR 物理删除。

#### PR3 ✅ 已完成（2026-04-27）— `KbAccessCalculator` 提取 + caller-context 泄漏修复

**目标**：把 scope 计算从 service 内提到独立 calculator，消除 `AccessServiceImpl:325 / :183` 的 caller-context 泄漏。

落地内容：
- `KbAccessSubject` / `KbAccessSubjectFactory` / `KbAccessCalculator` 落地，calculator 接受显式 target subject，不读 `UserContext`
- `KbReadAccessPort` 收敛为 current-user-only port，去掉 `String userId` 目标用户参数
- `AccessServiceImpl` 的 caller-context 泄漏修复，target 用户 grants / 安全等级统一委托 calculator
- 物理删除单 key `getMaxSecurityLevelForKb`（零 caller + 自带 caller-context leak）
- `StreamChatEventHandler` / handler package 不再 import `UserContext`，异步回调用构造期显式 `userId`
- `RagVectorTypeValidator` 对 Milvus / pg 默认启动期 fail-fast；仅 `rag.vector.allow-incomplete-backend=true` 允许 dev override
- `PermissionBoundaryArchTest` / `KbAccessServiceRetirementArchTest` + `docs/dev/verification/permission-pr3-leak-free.sh` 锁住 PR3 边界
- T1/T2 单元测试覆盖 current-user-only port、calculator target-aware 计算、handler userId 显式传递与 `AccessServiceImpl` 泄漏回归

**改动点**：
- 新增 `KbAccessCalculator`，target-aware（接受 `targetUserId` 显式参数，不读 `UserContext`）
- `AccessServiceImpl:325` `computeRbacKbIdsFor` → 委托给 `KbAccessCalculator`
- `AccessServiceImpl:183` `getMaxSecurityLevelForKb` → 同上
- chunk internal helper 物理 visibility 收紧或拆分 `KnowledgeChunkInternalService`（PR1 留下的 R4 风险）
- `StreamChatEventHandler` 显式 inject `userId`（消除 ThreadLocal 伪登录，准备阶段 B）

**Commit 序列模板**：
```
commit 1: 引入 KbAccessCalculator + 单元测试
commit 2: AccessServiceImpl:325/:183 改用 KbAccessCalculator
commit 3: KnowledgeChunkInternalService 拆分（或 package-private 收紧）
commit 4: StreamChatEventHandler 显式 userId 参数化
```

**配套护栏**（PR1-PR3 期间陆续加）：
- ArchUnit：`framework` 不得 import 业务包；`infra-ai` 不得 import `bootstrap` 业务包
- ArchUnit：Controller 不得依赖 `*Mapper`（先告警，存量 3 处 allowlist：`SpacesController` + `EvalRunController` + `DashboardController`）
- ArchUnit：非 `admin` 域不得注入其他域 `*Mapper`（先告警 + allowlist）
- 静态扫描：新代码不得注入 `KbAccessService`（PR2 commit 6 强制）
- 静态扫描：异步路径不得读 `UserContext.getUserId()`（PR3 commit 4 强制）
- 启动期：`VectorBackendCapabilityValidator`——`rag.vector.type != opensearch` 时启动 fail-fast（除非显式 `rag.vector.allow-incomplete-backend=true` dev override）

**阶段 A 全部成功标准**：
```
rg "KbAccessService" bootstrap/src/main/java | wc -l   ≤ 3   （仅 @Deprecated 接口 + 委托实现 + 测试）
rg "kbAccessService\.check\*" -g '*Controller.java'      = 0
rg "kbAccessService" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag    = 0
rg "AccessServiceImpl.*computeRbacKbIdsFor"   = 0   （已迁到 KbAccessCalculator）
rg "getMaxSecurityLevelForKb\b" bootstrap/src/main/java   = 0   （单 key 方法已物理删除）
启动期 rag.vector.type=milvus 触发 fail-fast
```

**引用源**：
- PR1 spec §2.6（已完成的延期项）
- P0 报告 §"Deprecated 授权接口使用"表（PR2-PR3 完整迁移清单）
- enterprise doc Phase 1（最小 RBAC + 知识库共享模型基础设施）

---

### 阶段 B：检索链路权限对齐（PR4-PR5）

把检索链路权限从"散落在 chat service / retrieval engine / vector channel 的临时决策"收敛为"生产请求路径由后端一次性构造 `RetrievalScope`，检索链路只消费 scope"。原先"前端传 indexNames 后端直接查"的描述已经不是当前代码事实。

#### PR4 ✅ 已合并（2026-04-28，PR #27）— Retrieval Scope Builder + Mapper 退役

**目标**：scope 计算职责从 `RAGChatServiceImpl.streamChat` 抽出到独立 `RetrievalScopeBuilder`；`MultiChannelRetrievalEngine` + `VectorGlobalSearchChannel` 注入的 `KnowledgeBaseMapper` 退役为 `KbMetadataReader` port；`RetrievalScope` 一等公民 record 沿调用链一次贯通。

**路线图原描述废止说明**：**RagRequest 字段迁移命题已不适用**。原条目假设"`RagRequest` 字段从 `List<String> indexNames` 改为 `List<String> kbIds + ScopeMode`"，但经 PR4 spec §0.1 核实，这套前提已经被当前代码事实推翻：

| 路线图假设 | 代码现实（main, 2026-04-28） | 结论 |
|---|---|---|
| 存在 `RagRequest` DTO 含 `List<String> indexNames` | `RagRequest` 类型不存在；HTTP 入口 `RAGChatController:49-52` 是 4 个 `@RequestParam`：`question / conversationId / knowledgeBaseId / deepThinking` | 路线图假设作废 |
| 前端传 `indexNames` 数组 | 前端无 `indexNames` 字段；`ChatStore` 只传单个 `knowledgeBaseId` | 路线图假设作废 |
| 后端"前端值即 scope" | `RAGChatServiceImpl:121-133` 已经用 `kbReadAccess.getAccessScope(Permission.READ)` + `checkReadAccess(knowledgeBaseId)` 后端计算并校验 | PR1-PR3 期间已实现，无需 PR4 重做 |
| `indexNames` 仅存在于 vector store admin 工具方法 | `indexNames` 只出现在 `OpenSearch/Milvus/PgVectorStoreAdmin` 这类底层 admin 方法名里，与 RAG 入参无关 | 命名巧合，不是 RAG 入参契约 |

详细 spec：`docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md`
实施计划：`docs/superpowers/plans/2026-04-28-permission-pr4-retrieval-scope-builder.md`

**业务侧**（兑现 enterprise doc Phase 4 的当前真实切面）：
- 前端传值仅作为请求意图，授权依据必须来自后端 `RetrievalScopeBuilder`
- RAG 入口当前是单个 `knowledgeBaseId`，PR4 不引入 `RagRequest` / `ScopeMode` / 前端 `indexNames` 下线工作
- OpenSearch DSL `terms(metadata.kb_id)` 强制化留 PR5；per-KB security level 粒度修正留阶段 C / PR6

**架构侧**（兑现 P0 报告 SEC-1 / SRC-3 / RAG 拆分候选）：
- 新增 `RetrievalScopeBuilder`，把 scope 计算职责从 `RAGChatServiceImpl.streamChat` 抽出
- 新增 `RetrievalScope(accessScope, kbSecurityLevels, targetKbId)` record，让 scope 三件套沿 `RAGChatServiceImpl → RetrievalEngine → MultiChannelRetrievalEngine → SearchContext` 一次贯通
- `MultiChannelRetrievalEngine` / `VectorGlobalSearchChannel` 不再注入 `KnowledgeBaseMapper`，改用 `KbMetadataReader`
- `ChatForEvalService` / `EvalRunExecutor` 走 `RetrievalScope.all(kbId)` sentinel，不调读取 `UserContext` 的 builder

**Commit 序列模板**：
```
PR4 ✅ 已合并（PR #27）— Retrieval Scope Builder + Mapper 退役
  c0: 修订路线图 §1 + §3 阶段 B，删除 RagRequest 字段迁移命题
  c1: 新增 RetrievalScope record + RetrievalScopeBuilder interface/impl + 测试
  c2: KbMetadataReader 加 getCollectionName / getCollectionNames batch
  c3: RAGChatServiceImpl + RetrievalEngine + ChatForEvalService 签名收敛到 RetrievalScope
  c4: MultiChannelRetrievalEngine + VectorGlobalSearchChannel 注入 KbMetadataReader，退役 KnowledgeBaseMapper
  c5: PR4 守门脚本 + ArchUnit + S5/S6/S7 smoke
```

#### PR5 — Metadata Filter Contract Hardening（命名修订自原 "OpenSearch filter 强制"）

**修订背景**（与原路线图描述的差异）：

| 原路线图描述 | 代码现实（main, 2026-04-28，PR #27 合并后） | 结论 |
|---|---|---|
| "强制 OpenSearch DSL 带 `terms(metadata.kb_id)` + `range(security_level)`" | `OpenSearchRetrieverService.renderFilter` (L217) 已支持 `FilterOp.IN → terms` 和 `FilterOp.LTE_OR_MISSING → range`，无需改 query builder | render 层已就位 |
| "`OpenSearchRetrieverService` 强制注入 filter" | retriever 只持有 `collectionName + metadataFilters`，没有可靠 `kbId` 来源——把强制注入下沉到存储实现会把权限上下文泄到 storage 层 | 强制注入点错位，应在 builder 层 |
| `range(security_level)` 缺失 | `DefaultMetadataFilterBuilder` (L43-46) 已为每个 KB 输出 `LTE_OR_MISSING` filter | 已就位 |
| `terms(kb_id)` 缺失 | `DefaultMetadataFilterBuilder` 当前不输出 `kb_id` filter；检索按 per-KB 调用，依赖 `collectionName` 物理隔离，无 `metadata.kb_id` 软隔离纵深 | **PR5 真正缺口** |
| 检索改为单次多 KB 查询 + `terms` fan-out | 当前 `MultiChannelRetrievalEngine` (L79) / `VectorGlobalSearchChannel` (L192) / `IntentParallelRetriever` (L63) 都是 per-KB 调用；改为多 KB 单查会触及 `collectionName` / score cap / 并行错误隔离，远超 PR5 范围 | 保持 per-KB 调用结构，用 `IN singleton` 兑现 `terms` 表述 |

**目标**：把 `kb_id` 从"靠物理 collection 隔离"加固为"DSL filter 软隔离 + 物理 collection 双重防御"；把"builder 必须输出哪些 filter"从约定升级为契约测试守门。

**业务侧**：零增量。

**架构侧**：
- `DefaultMetadataFilterBuilder.build(context, kbId)` 对非空 `kbId` **永远输出** `KB_ID + FilterOp.IN + List.of(kbId)`（singleton list 在 OpenSearch 端渲染为 `terms(metadata.kb_id, [kbId])`，与路线图原表述兑现）
- `security_level` 输出策略明确化：
  - `AccessScope.Ids` 路径：维持当前 `LTE_OR_MISSING + per-KB level`
  - `AccessScope.All` 路径：spec 阶段决定是否输出 `Integer.MAX_VALUE` 的 no-op range，使"所有 OpenSearch DSL 必带 security_level filter"成为绝对契约
- `OpenSearchRetrieverService` 加缺失守卫：`metadataFilters` 不含 `kb_id` 时 fail / log（防御性，不替代 builder 层的真正强制）
- `SearchContext` 字段清理（如 `metadata` 等冗余字段）**留 PR5 spec 审完决定**——零 caller 才删，不做"为了 commit 3 硬删"

**Commit 序列模板**（草案，spec 阶段可能调整）：
```
PR5 — Metadata Filter Contract Hardening
  c1: DefaultMetadataFilterBuilder 强制输出 kb_id (FilterOp.IN singleton) + builder 单元测试
  c2: AccessScope.All 路径的 security_level 策略（per spec 决议：no-op range 或显式跳过）
  c3: OpenSearchRetrieverService 缺失 kb_id filter 守卫 + render 契约测试
       （锁住 terms(metadata.kb_id) 与 range(metadata.security_level) 真实出现在 DSL JSON）
  c4: 通道级契约测试——per-KB 调用传入的 kbId 与 builder 输出 kb_id filter 严格对齐
       （Multi/VectorGlobal/IntentParallel 三处）
  c5（可选）: SearchContext 冗余字段清理——仅当 spec 证明零 caller
```

**成功标准**：
```
DefaultMetadataFilterBuilder.build(*, kbId) 输出 filter 永远含 kb_id (FilterOp.IN [kbId])  ← PR5 c1
普通 AccessScope.Ids 路径生成的 OpenSearch DSL 同时含 terms(metadata.kb_id) 与 range(metadata.security_level)   ← PR5 c3
AccessScope.All 路径的 security_level 行为符合 spec 显式决议（无歧义）   ← PR5 c2
通道级 per-KB 调用与 builder 输出的 kb_id 严格对齐（无错位、无遗漏）   ← PR5 c4
契约测试 SecurityLevelFilterEnforcedTest + KbIdFilterEnforcedTest 落地，CI 强制   ← PR5 c3-c4
```

**显式不在 PR5 范围**：
- per-KB 调用结构改为单次多 KB fan-out（结构性重构，留后续优化 PR）
- `LoginUser.maxSecurityLevel` 在管理决策路径的清理（仍由 `KbAccessSubjectFactoryImpl:44` / `KbAccessCalculator:132` / `RoleServiceImpl:485` 使用——见阶段 C 修订文案）
- Milvus / pgvector 后端 metadata filter 补齐（启动期 fail-fast 已挡住，留 SL-1 backlog）

**引用源**：
- enterprise doc Phase 4（把权限压进 RAG 检索链路）
- PR4 spec：`docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md`
- PR4 plan：`docs/superpowers/plans/2026-04-28-permission-pr4-retrieval-scope-builder.md`
- P0 报告 §"RAG 编排拆分候选"
- P0 报告 §"向量后端不变量"（OpenSearch filter 当前已支持，本阶段 PR5 强制启用）

---

### 阶段 C：security_level 粒度修正（PR6）

`max_security_level` 必须是**按 (role × kb) 二维**，不能是用户全局值。

**业务侧**（兑现 enterprise doc Phase 5）：
- 现状（按代码事实精准描述）：
  - **RAG 检索主链路**：已经走 `KbReadAccessPort.getMaxSecurityLevelsForKbs(kbIds): Map<kbId, maxLevel>` 的 per-KB level（PR3 + PR4 落地后），不再读全局 `LoginUser.maxSecurityLevel`
  - **管理决策路径**：仍在使用全局 `LoginUser.maxSecurityLevel`，需要本阶段评估保留/迁移，包括：
    - `KbAccessSubjectFactoryImpl.currentOrThrow()` — 把 `LoginUser.maxSecurityLevel` 注入 `KbAccessSubject`
    - `KbAccessCalculator` — 用主体 `maxSecurityLevel` 计算 DEPT_ADMIN 同部门 KB 的 ceiling
    - `RoleServiceImpl` — 通过 `UserContext.get().getMaxSecurityLevel()` 限制角色绑定时可设的 max level
- 改造方向（待 PR6 spec 决议）：
  - 检索路径已迁完，本阶段无需重做
  - 管理路径：要么继续使用全局值（语义为"调用者本人能授予的最高 level 上限"，与 KB 无关），要么改为 per-KB / per-dept 二维查询；spec 阶段比较两种方案的语义清晰度与风险半径
- 老的全局 `LoginUser.maxSecurityLevel`：决策语义不会"全部清零"——视 spec 决议保留为"调用者授权能力上限"或迁移到二维结构

**架构侧**：
- 把 per-KB security level resolver 放进阶段 A 引入的 `KbAccessCalculator`
- `AuthzPostProcessor` fail-closed 对系统态 / 离线评估路径补审计标记（P0 报告 §异步与事件边界 S2）

**Commit 序列模板**（草案，spec 阶段调整）：
```
commit 1: 审计 LoginUser.maxSecurityLevel 在管理决策路径的全部 caller，列出语义保留 vs 迁移决策表
commit 2: 按 spec 决议——管理路径或保留为"授予能力上限"语义并加注释 / 或迁移到 KbAccessCalculator 二维 API
commit 3: AuthzPostProcessor 系统态路径加审计标记（P0 报告 §异步与事件边界 S2）
commit 4: 跨 KB level 隔离契约测试（FICC_USER 在不同 KB 看到不同 level 上限——已是 PR3/PR4 后既成事实，本步补回归测试）
commit 5: ArchUnit / grep 守门——禁止 RAG 检索路径再读全局 maxSecurityLevel
```

**成功标准**：
```
同一 FICC_USER 登录态：
  在 OPS-COB KB 上 → 仅能检索 level 0 文档
  在 FICC 自家 KB 上 → 能检索到 level 2 文档
（以上 PR3/PR4 后已成立，本阶段补契约测试锁住）
RAG 检索路径（rag/core/retrieve/**）grep LoginUser\.getMaxSecurityLevel  = 0
管理决策路径的 maxSecurityLevel 引用：每个剩余 caller 都有 spec §决策记录交代
   "为什么保留全局值"或"为什么迁移到 per-KB"
```

**引用源**：
- enterprise doc Phase 5（修正 security_level 的粒度）
- P0 报告 §异步与事件边界 S2

---

### 阶段 D：sharing 产品化（PR7-PR8）

OPS_ADMIN 在知识库详情页里直接管理共享，无需 DBA 改库。

**业务侧**（兑现 enterprise doc Phase 2 / Phase 3）：
- `GET/PUT /knowledge-base/{kbId}/role-bindings` 接口已存在（PR1 已 thinning），补**产品 UI**：知识库详情页加 `Sharing` tab
- 操作：查看绑定 / 加 FICC_USER READ / 设 max_security_level / 解绑
- 强化"DEPT_ADMIN 只能管理本部门 KB，但**可以授权给其他部门角色**"的不对称规则
- 最小授权原则：推荐方式 B（`FICC_COB_USER` 细分角色），不推荐方式 A（直接授权 `FICC_USER` 整组）

**架构侧**：
- 新增 `KbRoleBindingAdminPort`（替代 `KnowledgeBaseServiceImpl.unbindAllRolesFromKb` 的临时实现）
- 前端 `frontend/src/utils/permissions.ts` 作为单一真相源加 ESLint 护栏，禁止内联 `isSuperAdmin/isDeptAdmin`
- Sharing tab 的权限判断必须走 `usePermissions` hook

**Commit 序列模板**：
```
PR7 — 后端 KbRoleBindingAdminPort + 不对称规则
  commit 1: 新增 KbRoleBindingAdminPort + 实现
  commit 2: KnowledgeBaseServiceImpl.unbindAllRolesFromKb 改用 port
  commit 3: 加强 DEPT_ADMIN 不对称规则单元测试

PR8 — 前端 Sharing Tab
  commit 1: KB 详情页加 Sharing tab 路由 + 框架
  commit 2: 角色绑定 CRUD UI（list / add / edit max_security_level / remove）
  commit 3: ESLint custom rule 禁止 pages/ 内联 isSuperAdmin/isDeptAdmin
```

**成功标准**：
```
OPS_ADMIN 能在 UI 上自助把 OPS-COB 共享给 FICC_USER，无需 DBA 改库
前端 grep 'isSuperAdmin\|isDeptAdmin' src/pages 不在 permissions.ts/usePermissions 之外出现
DEPT_ADMIN 跨部门管理 KB 的尝试 → 403（service 层拒绝）
DEPT_ADMIN 把 own-dept KB 共享给其他部门角色 → 200（不对称规则成立）
```

**引用源**：
- enterprise doc Phase 2 / Phase 3（共享产品化 + DEPT_ADMIN 管理边界）
- P0 报告 §前端热点（permissions.ts 单一真相源）

---

### 阶段 E：审计与治理（PR9-PR10）—— 可与阶段 D 并行

权限体系全程可追溯。

**业务侧**（兑现 enterprise doc Phase 6）：

最小动作覆盖：
```
KB_CREATE / KB_UPDATE / KB_DELETE
KB_SHARE_ADD / KB_SHARE_REMOVE / KB_ROLE_BINDING_UPDATE
DOC_UPLOAD / DOC_DELETE
RAG_QUERY (含 selected_kb_ids / effective_kb_ids / retrieved_doc_ids / answer_id)
SOURCE_OPEN
```

**架构侧**（关键：横切关注点切面接入，绝不手工散写）：
- 在 `KbReadAccessPort` / `KbManageAccessPort` 上用 Spring AOP `@Aspect`，所有权限决策点**自动记审计**
- RAG_QUERY 在 `RAGChatServiceImpl` 主流程的 trace recorder 里捕获（与 P0 报告 RAG 拆分候选"评测采集"统一处理）
- 审计写入走异步队列，使用阶段 A 已建立的 `system=true` 协议；不阻塞主流程

**Commit 序列模板**：
```
PR9 — audit_log schema + AOP 切面
  commit 1: t_audit_log 表 + 升级脚本 upgrade_v1.X_to_v1.Y.sql
  commit 2: AuditLogPort + 异步落盘实现
  commit 3: KbAccessAuditAspect 切面接到 KbReadAccessPort/KbManageAccessPort
  commit 4: 切面单测（system=true 不记 / 普通用户记）

PR10 — RAG_QUERY 审计 + 管理端查看
  commit 1: RagTraceRecorder 整合 RAG_QUERY 审计
  commit 2: 管理端 audit_log 查询 API
  commit 3: 前端简易审计列表页（不做复杂筛选）
```

**成功标准**：
```
任意一次 RAG 查询在 audit_log 都能找到对应记录（含 selected_kb_ids / effective_kb_ids / answer_id）
任意一次 sharing 操作在 audit_log 都有 KB_SHARE_ADD / KB_SHARE_REMOVE
audit_log 写入失败不影响主流程响应
service 方法体内 grep 'auditLog\.' = 0（全部由切面接入）
```

**引用源**：
- enterprise doc Phase 6（补审计日志）
- P0 报告 §RAG 编排拆分候选 P3（评测采集统一）

---

### 阶段 F：申请流（PR11-PR12）—— 可延后

用户没权限时引导申请，不再"线下找人"。

**业务侧**（兑现 enterprise doc Phase 7）：
- 新表 `kb_access_request`（字段见 enterprise doc §2.7）
- 流程：FICC 用户申请 OPS-COB → OPS_ADMIN 审批 → 通过后调用阶段 D 的 sharing port 写 `role_kb_relation`
- 推荐方式 B（细分角色绑定）：审批通过把用户加入 `FICC_COB_USER`，而非直接给 `FICC_USER` 整组授权——降低误授权半径

**架构侧**：
- `bootstrap` 下新开 `access` 域（与 `user / knowledge` 平级）
- **关键隔离**：申请流通过审批后**调用 sharing port 写授权**，不让申请流自己直接改 `role_kb_relation`——保持审计追溯链路单源

**Commit 序列模板**：
```
PR11 — 后端申请流 + 审批
  commit 1: t_kb_access_request 表 + 升级脚本
  commit 2: 新建 access 域骨架 + AccessRequestService
  commit 3: 申请 / 审批 / 拒绝 API；审批通过时调 KbRoleBindingAdminPort
  commit 4: 审批流单测（不直接写 role_kb_relation 的契约）

PR12 — 前端申请入口
  commit 1: KB 列表页"申请访问"入口（用户对没权限的 KB 看到入口）
  commit 2: 管理端审批中心
  commit 3: 审批通过后免登录刷新生效（清缓存 / 重算 accessibleKbIds）
```

**成功标准**：
```
FICC 用户从 UI 申请 OPS-COB → OPS_ADMIN 审批 → 用户立即可检索（无需重新登录）
申请流不直接写 role_kb_relation，只通过 sharing port 触达
拒绝申请 → 用户看到拒绝原因，无法绕过
```

**引用源**：
- enterprise doc Phase 7（访问申请流）

---

### 阶段 H：意图与工具治理（PR-INT-1/2/3）—— 可与阶段 C/D/E 并行

**引入背景**：Collateral 工程（OPS AI 场景 3 智能筛查 + 场景 4 协议提取）引入两个**路线图阶段 A-G 未覆盖**的治理维度：

1. **意图节点 scope**：现有 `t_intent_node` 全部为隐式全局，没有"哪些意图属于哪个 KB"的归属概念。Collateral 智能筛查需要把"该 {counterparty} 下 {协议类型} 的 {业务字段} 是多少"这条固定句式作为 **KB 级业务意图**落地，且只对 Collateral KB 生效；对 COB / SOP KB 不应出现该意图。
2. **MCP 工具的 KB 级访问控制**：现有 `MCPToolRegistry` 是全局单例，无"哪个 KB 启用哪些工具"的访问层。Collateral 的 `collateral_field_lookup` / `start_collateral_extraction` / `get_extraction_result` / `export_collateral_excel` 必须仅对 Collateral KB 可调用，对 COB / SOP KB 不可见。

**与既有阶段的关系**：
- **强依赖阶段 A（PR1-PR3 已完成）**：复用 `KbManageAccessPort.canManage(kbId)` + `KbAccessCalculator` + `KbAccessSubject`，零新 port 引入
- **弱依赖阶段 D**：管理 UI 复用 `permissions.ts` / `usePermissions` 单一真相源；阶段 D 未完成时阶段 H 可先做后端 API + 简易 UI，sharing UI 框架就位后再升级
- **弱依赖阶段 E**：意图节点 / MCP binding 写操作期望由阶段 E 的 `KbAccessAuditAspect` 自动接管审计；阶段 E 未完成时审计**暂缺**，不在阶段 H 内手工散写 `auditLog.record`（违反阶段 E 反模式）
- **与阶段 C 解耦**：阶段 H 不读 / 不写 `LoginUser.maxSecurityLevel`，避开 PR6 的语义争议
- **与阶段 G 不冲突**：阶段 H 仍是"用户 → 角色 → KB → (意图/工具) 白名单"的 3 层模型加一层 KB-工具映射，不引入关系图，不需要 OpenFGA / OPA / Casbin

**业务侧目标**：
- 意图节点支持 `GLOBAL`（系统交互：打招呼 / 引导 / 兜底）+ `KB`（业务意图：绑具体 MCP 工具）两类 scope
- DEPT_ADMIN 自助配置本部门 KB 的业务意图（无需 SUPER_ADMIN 介入）
- MCP 工具按 KB 白名单授权：COB / SOP KB → 不绑任何工具（保持纯 RAG）；Collateral KB → 绑 4 个 Collateral 工具
- 跨 KB 的统一系统交互意图（GLOBAL）由 SUPER_ADMIN 维护，全 KB 共享

**架构侧目标**：
- `t_intent_node` 加 `scope` / `kb_id` / `slot_pattern` / `answer_template_id` 列；DB CHECK 约束 `scope='GLOBAL' XOR kb_id IS NULL` + `scope='KB' OR mcp_tool_id IS NULL`
- 新增 `t_kb_mcp_binding`（kb_id / tool_id / enabled / config_json，partial unique on `deleted=0`）
- 新增 `KbMcpBindingService`（write 走 `KbManageAccessPort`；read 提供 `isEnabled(kbId, toolId)` 给运行时）
- 新增 `IntentScopeResolver`：chat 链路从 `Conversation.kb_id` 取；管理后台从 URL `?kbId=` 取；SUPER_ADMIN 全局视图无 kbId 仅加载 GLOBAL 树
- `IntentTreeFactory.buildTreeForKb(kbId)` 合并 GLOBAL + KB 子树，缓存按 kbId 分桶
- `RetrievalEngine.invokeMCPTool` 增加 binding 校验，未绑定工具静默降级到 KB 检索（**纵深防御**：即使意图节点配错 mcpToolId，运行时仍会被拦下）
- 软删级联：KB 软删 → `t_intent_node WHERE kb_id=?` + `t_kb_mcp_binding WHERE kb_id=?` 同步软删
- ArchUnit 守门三条：(1) 写 `t_intent_node` `scope='KB'` 必经 `KbManageAccessPort`；(2) 设 `IntentNode.mcpToolId` 必经 `KbMcpBindingService.isEnabled` 校验；(3) GLOBAL 意图节点的 `mcpToolId` 永远 null（DB CHECK + ArchUnit 双重）

**Commit 序列模板**：

```
PR-INT-1 — Schema 迁移 + KbMcpBindingService + IntentTreeFactory 加 kbId
  c1: upgrade_v1.X_to_v1.Y.sql — t_intent_node 加 scope/kb_id/slot_pattern/answer_template_id 列
       + t_kb_mcp_binding 新表 + DB CHECK 约束 + 现有节点全部迁 GLOBAL
  c2: KbMcpBindingService + Mapper（write 注解 @KbManagePermission，复用 PR2 port）
  c3: IntentTreeFactory.buildTreeForKb(kbId) 合并 GLOBAL + KB 子树 + 缓存按 kbId 分桶
  c4: 数据迁移单测：现有意图全部 scope=GLOBAL，行为字节级不变
  c5: T1 契约测试：DB CHECK 约束、partial unique index、kbId=null 仅 GLOBAL 加载

PR-INT-2 — 运行时校验 + 软删级联 + ArchUnit 守门
  c1: IntentScopeResolver（三档来源：chat/admin/global）
  c2: RetrievalEngine.invokeMCPTool binding 校验 + 静默降级 + WARN 日志
  c3: KB 软删触发意图 + binding 级联软删（KnowledgeBaseService.delete 钩子）
  c4: IntentTreeCacheInvalidator 统一缓存失效（GLOBAL 改 → 失效全部；KB 改 → 失效该 kbId）
  c5: ArchUnit IntentMcpGovernanceArchTest（三条守门规则）+ grep 守门脚本
       docs/dev/verification/permission-stage-h-clean.sh
  c6: T2 契约测试：DEPT_ADMIN 跨部门写意图 → 403；未绑定工具调用 → 降级路径

PR-INT-3 — /admin/intent 管理界面 + Collateral 首批数据
  c1: 后端 API：意图 CRUD（区分 GLOBAL / KB scope）+ MCP binding CRUD
  c2: 前端 /admin/intent 三 tab（全局意图 / 本部门 KB 意图 / MCP 绑定）
       复用 permissions.ts / usePermissions（避免在 pages/ 内联 isSuperAdmin）
  c3: t_answer_template + t_slot_filling_template（承载场景 3 两段式答案 + 场景 4 多轮话术）
  c4: 首批数据脚本：Collateral KB 配 counterparty_field_lookup 意图节点
       + 4 个 Collateral 工具 binding（field_lookup / start_extraction / get_result / export_excel）
  c5: 阶段 H smoke 文档 docs/dev/verification/permission-stage-h-smoke.md
```

**成功标准**：

```
DB 约束：
  t_intent_node 不存在 (scope='GLOBAL' AND mcp_tool_id IS NOT NULL) 行   ← DB CHECK
  t_intent_node 不存在 (scope='KB' AND kb_id IS NULL) 行                  ← DB CHECK
  t_kb_mcp_binding 同 (kb_id, tool_id) 唯一（deleted=0 范围内）           ← partial unique

运行时：
  COB / SOP KB 的 chat 链路：意图分类只在 GLOBAL + 该 KB 的 KB 子树内进行
                              （该 KB 的 KB 子树为空时仅 GLOBAL）
  Collateral KB 的 chat 链路：能命中 counterparty_field_lookup 意图
                              + 调用 collateral_field_lookup MCP 工具
  COB / SOP KB 强行调用 collateral_field_lookup → RetrievalEngine 静默降级 + WARN 日志

权限：
  DEPT_ADMIN 写本部门 KB 意图 → 200
  DEPT_ADMIN 跨部门写意图 → 403
  DEPT_ADMIN 写 GLOBAL 意图 → 403
  SUPER_ADMIN 写 GLOBAL / KB 意图 → 200

ArchUnit：
  rg "IntentNodeMapper.*insert\|update" 命中代码必经 @KbManagePermission 或 SUPER_ADMIN 校验
  rg "IntentNode\.setMcpToolId" 命中代码必经 KbMcpBindingService.isEnabled
  rg "MCPToolRegistry.*new MCPToolExecutor" = 0（注册中心是单一真相源）

软删级联：
  KB 软删后：相关 t_intent_node 行 deleted=1，相关 t_kb_mcp_binding 行 deleted=1
  IntentTreeFactory.buildTreeForKb(已软删 kbId) → 仅返 GLOBAL 树
```

**显式不在阶段 H 范围**：
- MCP 工具的**注册中心治理**（哪些工具能注册到 `MCPToolRegistry`）—— 仍是 SUPER_ADMIN 启动期注入，与 KB binding 解耦
- 意图分类的**置信度阈值调优** —— 走 RAG 主线，不在权限治理面
- 跨 KB 意图复用（GLOBAL 通用业务模板）—— Collateral 工程明确"先不做"，YAGNI
- Collateral 业务表（`t_collateral_extraction` / `t_collateral_extraction_task` / `t_collateral_extraction_session`）—— 走业务 PR（PR-BIZ-3 / PR-BIZ-4），不在阶段 H

**引用源**：
- Collateral 工程 PR 总收束（场景 3 + 场景 4 闭环 PR 列表，参考 docs/dev/design 下 Collateral 设计文档）
- 阶段 A PR2-PR3（`KbManageAccessPort` / `KbAccessCalculator` / `KbAccessSubject`，本阶段直接复用）
- 阶段 E（审计切面，本阶段写操作期望由切面接管，不手工散写）
- enterprise doc Phase 8 表（明确 OpenFGA / OPA / Casbin 不引入；阶段 H 不违反）

---

### 阶段 G：暂时不做的事

**OpenFGA / OPA / Casbin 都不在视野内**，理由（enterprise doc §2 Phase 8 表）：
- 当前模型 `用户 → 角色 → KB`，关系图仅 3 层
- 没有继承关系、没有时间窗、没有地域策略、没有交易线维度
- `role_kb_relation.permission + max_security_level` 已表达完所有当前需求

引入第三方框架的成本（学习曲线、运维负担、调试难度）远大于收益。**至少推迟到阶段 F 完成 + 出现具体复杂关系需求时再评估**。

---

## 4. 阶段依赖图

```
阶段 A（边界整理 PR1-PR3） ✅ 已完成
   │
   ├─→ 阶段 B（检索链路对齐 PR4-PR5） ✅ 已完成
   │      │
   │      └─→ 阶段 C（security_level 粒度 PR6）   ← 进行中
   │
   ├─→ 阶段 D（sharing 产品化 PR7-PR8）
   │      │
   │      └─→ 阶段 F（申请流 PR11-PR12）
   │
   ├─→ 阶段 E（审计与治理 PR9-PR10）   ← 可与 D 并行
   │
   └─→ 阶段 H（意图与工具治理 PR-INT-1/2/3）   ← Collateral 工程引入，可与 C/D/E 并行
          │
          ├ 弱依赖 D：复用 permissions.ts；D 未完成时先做后端 + 简易 UI
          └ 弱依赖 E：写操作期望由 KbAccessAuditAspect 接管；E 未完成时审计暂缺
```

**强依赖**：
- A → B/D/E/H：没有 port 收敛，god-service 会复活；阶段 H 的 KB scope 写权限直接复用 `KbManageAccessPort`
- D → F：审批通过后写的是 sharing port，sharing 没产品化前申请流没意义

**弱依赖**：
- B → C：B 已抽出 scope，C 只是把 `kbMaxSecurityLevelMap` 用对
- H → D：阶段 H 的管理界面与阶段 D sharing UI 共享前端 `permissions.ts`；D 未完成时阶段 H 先做后端 API + 简易 UI，sharing UI 框架就位后升级
- H → E：阶段 H 写操作期望由阶段 E 审计切面接管；E 未完成时阶段 H **不**手工散写 `auditLog.record`（违反阶段 E 反模式），审计暂缺由 E 完成后补齐

**并行安全**：
- D 与 E：审计切面接到 port 上，不依赖 sharing UI
- H 与 C/D/E：阶段 H 不读 `LoginUser.maxSecurityLevel`（避开 PR6 争议），不阻塞 sharing UI（弱依赖），写操作走切面（弱依赖 E）

---

## 5. 给开发者的"PR 决策树"

提交权限相关 PR 前，按这个树自问：

1. **这个 PR 加的是新业务能力还是清偿架构债？**
   - 全是新业务能力 → 检查阶段 A 是否完成；若否，**先补阶段 A 的 port 收敛**，否则 PR 会复活 god-service
   - 全是架构清偿 → 落到对应阶段（A 的 PR2-3 / B 的 RetrievalScopeBuilder / C 的 KbAccessCalculator 扩展）

2. **这个 PR 改的是权限决策规则吗？**
   - 是 → 决策只能在 `KbAccessService`（PR2 后改 port 实现内部）/ `KbAccessCalculator`（PR3 后）内部改，禁止散落到 controller / service / aspect
   - 否 → 不要碰 `KbAccessServiceImpl` 的 85-194 / 327-380 决策段

3. **这个 PR 引入了新的 service/controller 入口吗？**
   - 是 → service 入口首行必须 `kbAccessService.checkXxx`（阶段 A 期间）/ port 调用（阶段 A 后）；controller 入口禁止内联检查（PR1 后由 grep 守门）

4. **这个 PR 引入了新的异步路径（MQ / @Async / Executor）吗？**
   - 是 → 必须显式 `LoginUser.builder().system(true).build()`，禁止依赖"无用户=放行"
   - 必须显式传 userId 参数，禁止异步路径读 `UserContext.getUserId()`

5. **这个 PR 引入新的检索路径吗？**
   - 是 → 必须经过 `RetrievalScopeBuilder`（阶段 B 后）；OpenSearch query DSL 必须带 `kb_id` + `security_level` filter

6. **这个 PR 引入新的写操作吗？**
   - 是 → 阶段 E 后必须自动接入审计切面；如审计切面无法覆盖，必须显式调用 audit recorder

7. **这个 PR 引入新的意图节点或 MCP 工具绑定吗？**
   - 引入新意图节点 → 必须明确 `scope`：系统交互 / 跨 KB 通用引导 → `GLOBAL`（仅 SUPER_ADMIN 可写）；具体业务意图 → `KB`（写权限走 `KbManageAccessPort`，DEPT_ADMIN 可写本部门 KB）
   - 引入新 MCP 工具 → 需在 `t_kb_mcp_binding` 显式绑定到目标 KB；运行时 `RetrievalEngine.invokeMCPTool` 校验 binding，未绑定静默降级到 KB 检索
   - 任何 `IntentNode.mcpToolId` 写入路径 → 必经 `KbMcpBindingService.isEnabled(kbId, toolId)` 校验（写入时校验，不只运行时）
   - 不在 `pages/` 内联意图配置写权限判断 → 走 `usePermissions` hook（与阶段 D 共享）

---

## 6. 路线图维护规则

- 每完成一个 PR：勾掉对应阶段的检查项；若该阶段全部完成，更新本文档"当前坐标"
- 出现路线图未覆盖的权限需求：先在本文档加一节再开 PR，**禁止 PR 走在路线图前**
- 阶段顺序被新事实推翻：本文档先修订，再开调整 PR
- 引用关系：所有权限相关 PR 描述必须含一行 `Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §<阶段>`
- 每个 PR 配套 spec / plan 应放在 `docs/superpowers/specs/` + `docs/superpowers/plans/`，沿用 PR1 的命名格式 `YYYY-MM-DD-permission-pr<N>-<short-name>-design.md` / `.md`

---

## 7. PR1 决策记录精华（来自 PR1 spec §6，对后续 PR 仍有约束力）

这些决策是 PR1 brainstorming 沉淀下来、**对后续 PR 仍然有效**的工程判断，迁入路线图避免遗忘：

- **`KbAccessCalculator` 为什么不在 PR1 做？** PR1 下游点（KB / Doc / Chunk 写路径）都是 `caller == target` 语义，不需要 target-aware calculator。calculator 只在 `AccessServiceImpl` 的 admin-views-target 路径才需要——延期到 PR3，避免 PR1 范围膨胀
- **为什么 system 用显式 `system=true` 不用启发式（"没 userId 就当系统态"）？** 启发式鼓励 fail-open——任何 HTTP filter 回归丢 `UserContext` → 静默降级为系统旁路。显式 boolean 让"这是系统态"成为构造现场的明确决策，可以 grep `system(true)` 审计
- **为什么不做 `*AsSystem` 双 API？** 把 5 个 check 方法翻倍到 10 个；把"系统/普通"的判断推给每个 caller；相比"入口边界一次置位 `UserContext.isSystem()`"无任何优势
- **为什么 missing-user guard 用 `ClientException` 不用 `ServiceException`？** `KbAccessServiceImpl` 已用 `ClientException` 抛权限拒绝，PR1 "no-new-semantics" 规则保持异常族一致
- **为什么 PR1 后保留 `KnowledgeChunkController` `@SaCheckRole("SUPER_ADMIN")`？** 不变量 C 要求 PR1 HTTP 语义不变。service 层 check 比 SUPER_ADMIN 宽松（admit DEPT_ADMIN on own-dept chunks），但 controller 注解继续把 HTTP 收紧为 SUPER_ADMIN。**是否放宽 HTTP 是单独产品决策**

---

## 8. 引用源

- `docs/dev/research/enterprise_permissions_architecture.md` — 业务/产品视角，phase 0-8
- `docs/dev/followup/2026-04-26-architecture-p0-audit-report.md` — 架构/工程视角，模块边界 + 跨域 mapper + ports
- `docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md` — PR1 spec（不变量 / 分层契约 / 决策记录的权威源）
- `docs/superpowers/plans/2026-04-26-permission-pr1-controller-thinning.md` — PR1 实施计划（6 commit TDD 模板，可作为 PR2-PR12 借鉴）
- `docs/dev/verification/permission-pr1-controllers-clean.sh` — PR1 grep 守门脚本（可扩展为后续阶段的护栏）
- `docs/dev/verification/permission-pr1-smoke.md` — PR1 手工 smoke 路径（PR2 后续验证沿用）
- `docs/dev/followup/backlog.md` — SL-1 / SRC-1 等已登记债务
- `docs/dev/followup/architecture-backlog.md` — ARCH-1 / ARCH-6 / ARCH-7 等已登记债务
