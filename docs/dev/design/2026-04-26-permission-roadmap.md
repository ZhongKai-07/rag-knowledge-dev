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
| 架构状态 | 阶段 A 已完成——PR1 controller thinning、PR2 `KbAccessService` 热路径退役、PR3 target-aware calculator + ThreadLocal guard 均已落地；阶段 B PR4 进行中（spec/plan 已落） |
| 已完成 | **PR1**（2026-04-26）— controller→service 边界下沉、`LoginUser.system` + `UserContext.isSystem()`、`bypassIfSystemOrAssertActor()` 守卫；20 个 service 入口、4 个参数化边界测试、`rg` 守门脚本均已落地。**PR2**（2026-04-27）— `KbScopeResolver`、RAG/KB/user 热路径迁到 `KbReadAccessPort` / `KbManageAccessPort` / `CurrentUserProbe`，`KbAccessService` 降级为 deprecated port 委托者。**PR3**（2026-04-27）— `KbAccessSubject` / factory / calculator、current-user-only read port、`AccessServiceImpl` 泄漏修复、handler ThreadLocal guard、vector fail-fast、ArchUnit + grep gates、T1/T2 测试落地。**PR4 spec/plan**（2026-04-28）— retrieval scope builder + mapper retirement 的设计与实施计划已落地，代码提交从 c0 开始顺序推进 |
| 进行中 | **PR4** 进行中（spec/plan 已落）— c0 先修订路线图，c1-c5 推进 `RetrievalScopeBuilder`、`RetrievalScope`、`KbMetadataReader` 退役 mapper 与守门脚本 |
| 下一步 | **PR4 c1-c5** — scope builder + mapper retirement；OpenSearch DSL `terms(metadata.kb_id)` 强制化留给 PR5 |

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

#### PR4 — Retrieval Scope Builder + Mapper 退役

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
PR4 — Retrieval Scope Builder + Mapper 退役
  c0: 修订路线图 §1 + §3 阶段 B，删除 RagRequest 字段迁移命题
  c1: 新增 RetrievalScope record + RetrievalScopeBuilder interface/impl + 测试
  c2: KbMetadataReader 加 getCollectionName / getCollectionNames batch
  c3: RAGChatServiceImpl + RetrievalEngine + ChatForEvalService 签名收敛到 RetrievalScope
  c4: MultiChannelRetrievalEngine + VectorGlobalSearchChannel 注入 KbMetadataReader，退役 KnowledgeBaseMapper
  c5: PR4 守门脚本 + ArchUnit + S5/S6/S7 smoke

PR5 — OpenSearch filter 强制 + 契约测试
  commit 1: OpenSearchRetrieverService 强制带 terms(kb_id) + range(security_level)
  commit 2: 添加 SecurityLevelFilterEnforcedTest 契约测试
  commit 3: 清理 SearchContext 冗余字段（视 PR4 审查反馈决定是否拆 PR4.5）
```

**成功标准**：
```
RetrievalScopeBuilder 是 UserContext→RetrievalScope 唯一入口   ← PR4
rag/core/retrieve 不依赖 knowledge.dao.mapper.*               ← PR4
ChatForEvalService 走 RetrievalScope.all(kbId) sentinel        ← PR4
MultiChannelRetrievalEngine 不再注入 KnowledgeBaseMapper       ← PR4
OpenSearch query DSL 强制带 terms(metadata.kb_id) + range(security_level) ← PR5
```

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
- 现状：`t_role_kb_relation.max_security_level` 字段已加，但部分代码用全局 `LoginUser.maxSecurityLevel`，导致 FICC_USER 在 OPS/COB KB 上误读 level 2 文档
- 改造：检索使用 `KbReadAccessPort.getMaxSecurityLevelsForKbs(kbIds): Map<kbId, maxLevel>`，按每个 KB 各自上限过滤
- 老的全局 `LoginUser.maxSecurityLevel`：废弃 / 改语义为"用户在所有 KB 上的最低公共下限"，仅 UI 展示用，不参与决策

**架构侧**：
- 把 per-KB security level resolver 放进阶段 A 引入的 `KbAccessCalculator`
- `AuthzPostProcessor` fail-closed 对系统态 / 离线评估路径补审计标记（P0 报告 §异步与事件边界 S2）

**Commit 序列模板**：
```
commit 1: KbAccessCalculator 加 perKbMaxSecurityLevel(targetUserId, kbIds): Map
commit 2: OpenSearch filter 改用 per-KB level（替换全局 maxSecurityLevel 引用）
commit 3: LoginUser.maxSecurityLevel 在决策路径上的引用全部删除
commit 4: AuthzPostProcessor 系统态路径加审计标记
commit 5: 跨 KB level 隔离契约测试（FICC_USER 在不同 KB 看到不同 level 上限）
```

**成功标准**：
```
同一 FICC_USER 登录态：
  在 OPS-COB KB 上 → 仅能检索 level 0 文档
  在 FICC 自家 KB 上 → 能检索到 level 2 文档
LoginUser.maxSecurityLevel 字段在决策路径上引用数 = 0
（仅保留 VO 字段供 UI 展示用）
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

### 阶段 G：暂时不做的事

**OpenFGA / OPA / Casbin 都不在视野内**，理由（enterprise doc §2 Phase 8 表）：
- 当前模型 `用户 → 角色 → KB`，关系图仅 3 层
- 没有继承关系、没有时间窗、没有地域策略、没有交易线维度
- `role_kb_relation.permission + max_security_level` 已表达完所有当前需求

引入第三方框架的成本（学习曲线、运维负担、调试难度）远大于收益。**至少推迟到阶段 F 完成 + 出现具体复杂关系需求时再评估**。

---

## 4. 阶段依赖图

```
阶段 A（边界整理 PR1-PR3）
   │     ↑ PR1 ✅ 已完成
   │
   ├─→ 阶段 B（检索链路对齐 PR4-PR5）
   │      ↑ PR4 进行中（scope builder + mapper retirement）
   │      │
   │      └─→ 阶段 C（security_level 粒度 PR6）
   │
   ├─→ 阶段 D（sharing 产品化 PR7-PR8）
   │      │
   │      └─→ 阶段 F（申请流 PR11-PR12）
   │
   └─→ 阶段 E（审计与治理 PR9-PR10）   ← 可与 D 并行
```

**强依赖**：
- A → B/D/E：没有 port 收敛，god-service 会复活
- D → F：审批通过后写的是 sharing port，sharing 没产品化前申请流没意义

**弱依赖**：
- B → C：B 已抽出 scope，C 只是把 `kbMaxSecurityLevelMap` 用对

**并行安全**：
- D 与 E：审计切面接到 port 上，不依赖 sharing UI

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
