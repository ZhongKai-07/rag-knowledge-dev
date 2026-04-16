# Deferred Follow-ups

由 2026-04-14 `/simplify` 三 agent 审查（91 commit 的 RBAC feature 分支）发现但未在那一轮处理的遗留项。
按**真实优先级**排序，不是按提出顺序。动手前可逐条挑，不必刷全表。

---

## 🔴 上线前必查

### SL-1. Milvus / Pg 检索未实现 `metadataFilters`

**位置**：`MilvusRetrieverService` / `PgRetrieverService`
**症状**：接受 `metadataFilters` 参数但**静默忽略**。
**影响**：`rag.vector.type` 切到 `milvus` 或 `pg` 时，文档 `security_level` 过滤**完全失效** —— 低权用户会读到高密文档。
**修复**：要么补实现、要么启动时校验 `rag.vector.type=opensearch` 或上述 service `@PostConstruct` fail-fast。
**已在** `CLAUDE.md` 标注，但属于"容易忘"的 trap。

### SL-2. `t_user.dept_id` / `t_knowledge_base.dept_id` 缺 FK 约束

**位置**：DB schema
**症状**：`SysDeptServiceImpl.delete()` 本轮加了 `@Transactional` + `SELECT FOR UPDATE` 串行化 dept 自身的并发修改，但仍无法阻止**并发向已删除 dept 插入 user/KB**。
**修复**：补 `ALTER TABLE t_user ADD CONSTRAINT fk_user_dept FOREIGN KEY (dept_id) REFERENCES sys_dept(id) ON DELETE RESTRICT;` 同理 `t_knowledge_base`。
**注意**：需要检查历史数据是否有孤儿行，否则 ALTER 会失败。

---

## 🟠 性能 / 可扩展

### PERF-1. `RagTraceQueryServiceImpl.listNodes` 拉大 blob 全字段

**位置**：`RagTraceQueryServiceImpl.java:100-106`
**症状**：`selectList` 无 `.select()` projection，拉了 `inputData`/`outputData`/`extraData`（LLM I/O，可能很大）但 `toNodeVO` 大多忽略。
**修复**：加 `.select(...)` 只拿 VO 需要的列。

### PERF-2. Last-SUPER_ADMIN 守护 O(users × roles)

**位置**：`KbAccessServiceImpl.countSuperAdminsExcluding` (~line 530)
**症状**：`selectList(所有 user)` 后对每个 user 单独 `selectList(其 roles)`。任何 role mutation / 用户删除都触发。
**修复**：改单条 `SELECT DISTINCT ur.user_id FROM t_user_role ur WHERE ur.role_id IN (validSuperRoleIds) AND ur.user_id NOT IN (excluded)` + Java 层应用 override。
**优先级低**：admin mutation 频率本来就低，延迟感知小。

### PERF-3. `RoleServiceImpl` 批量写入 N+1

**位置**：`RoleServiceImpl.java:152, 201, 278-307`
**症状**：`setRoleKnowledgeBases` / `setUserRoles` / `setKbRoleBindings` 全部逐条 `mapper.insert()`。`setKbRoleBindings` 还在循环内 `selectById` + `selectList`，双 N+1。
**修复**：`saveBatch` 或手写 `INSERT ... SELECT`；循环外一次性 preload roles/userRoles。

### PERF-4. `RoleListPage` 前端 fan-out `getRoleKnowledgeBases`

**位置**：`frontend/src/pages/admin/roles/RoleListPage.tsx:99-110`
**症状**：对每个角色发一次 HTTP 只为拿 KB 数量（30 角色 = 30 round-trip，每次都走一遍 Sa-Token + RBAC）。
**修复**：后端 `GET /role` 响应直接带 `kbCount` 字段，或加 `GET /role/kb-counts` 批量接口。

### OBS-1. Suggested Questions 挂 RagTrace 节点

**位置**：`StreamChatEventHandler.generateAndFinish()` / `DefaultSuggestedQuestionsService.generate()`
**背景**：2026-04-16 `feature/suggested-questions` 落地时已实现 "Option A"（读 `extra_data.suggestedQuestions` 在 trace 详情页展示 chip，commit `a309650`），但**没有挂正式的 `@RagTraceNode`**。目前只能看到最终产物，看不到耗时 / LLM 请求/响应 / 是否走了降级路径。
**症状**：当推荐质量差、耗时异常、或静默降级时，排查只能翻业务日志 + 粗看 extra_data，不能像主检索链路那样下钻查 node。
**修复**：
1. 给 `DefaultSuggestedQuestionsService.generate` 挂 `@RagTraceNode(name="suggestion-generate", type="SUGGESTION")`。
2. 因为推荐在独立 `suggestedQuestionsExecutor` 上异步跑、且该线程池**未包 TTL**，`RagTraceContext` 的 TTL 快照跨不过去 — 要在 submit 前**显式捕获** traceId/userId 传参，或把 `SuggestedQuestionsExecutorConfig` 换成项目里其他 bean 用的 `TtlExecutors.getTtlExecutor(...)` 风格（与 OBS-2 一起做）。
3. 可选：把 LLM request/response 快照写到 node 的 `inputData`/`outputData` 便于复现。
**优先级低**：推荐是辅助 UX，质量问题可容忍。只在真的遇到 "为什么这次推荐这么差/这么慢" 的排查痛点时再做。

### OBS-2. `suggestedQuestionsExecutor` 未使用 TTL 包装

**位置**：`bootstrap/.../rag/config/SuggestedQuestionsExecutorConfig.java`
**症状**：项目里其他所有 RAG 线程池（`ThreadPoolExecutorConfig` 里 9 个）都走 `TtlExecutors.getTtlExecutor(...)`，独此一家用裸 `ThreadPoolTaskExecutor`。当前是"凑巧能工作"：`traceId` / `userId` 在 `StreamChatEventHandler` 构造期被捕获到 final 字段，异步任务靠闭包拿，不走 ThreadLocal。但 `llmService.chat(...)` 内部若读 `RagTraceContext` / `UserContext` 则会拿到空值，未来新增依赖 TTL 的代码会悄悄失效。
**修复**：改为与 `ThreadPoolExecutorConfig` 一致的 `ThreadPoolExecutor` + Hutool `ThreadFactoryBuilder` + `TtlExecutors.getTtlExecutor(...)` 模式；同步修改 `StreamChatHandlerParams` 字段类型为 `Executor` 并调整 Task 17 集成测试（`awaitTermination` 改走 shutdown hook 或用 `Thread.yield()` 等待）。
**优先级低**：与 OBS-1 捆绑做成本最低。

### OBS-3. `sender.sendEvent(FINISH, ...)` 未包 try/catch 导致 taskManager 泄漏

**位置**：`StreamChatEventHandler.onComplete()`
**症状**：`sendEvent(FINISH, payload)` 若抛（客户端已断、SSE 已关），控制流直接逃离 `onComplete`，`taskManager.unregister(taskId)` 不会被调用，条目残留在 `StreamTaskManager`。这是 2026-04-16 feature/suggested-questions 引入前就存在的行为，但由于新增了 `shouldGenerate` 分支，FINISH 之后要做的事更多，泄漏窗口被放大。
**修复**：把 `sendEvent(FINISH, ...)` 包进 try/catch，失败时短路到 `sendDoneAndClose()`（保证 unregister + complete）。
**优先级低**：只有客户端主动断连才会触发，生产上偶发。

---

## 🟡 架构 / 类型安全

### ARCH-1. `KbAccessServiceImpl` 是 god-service（22 方法 / ~550 行）

**位置**：`KbAccessServiceImpl.java`
**症状**：混合 RBAC 读路径、mutation pre-flight（Last-SUPER_ADMIN 模拟）、dept-based admin bypass、doc-level 检查、缓存驱逐、请求时鉴权。每个 `checkXxxAccess` 重复 `hasUser → isSuperAdmin → isDeptAdmin → throw` 开场白。
**建议拆**：
- `SuperAdminInvariantService`（`countSuperAdminsExcluding` + `simulateActiveSuperAdminCountAfter`）
- `KbAuthorizationService`（所有 `check*` 方法）
- `KbAccessQueryService`（`getAccessibleKbIds` / `getMaxSecurityLevelsForKbs`）

### ARCH-2. DTO 还在用 `String` 而非枚举

**位置**：`RoleController.RoleCreateRequest.roleType: String`、`RoleKbBindingRequest.permission: String`、`RoleServiceImpl` 内多处 `RoleType.SUPER_ADMIN.name().equals(str)` 比较
**症状**：项目明明有 `RoleType` / `Permission` 枚举，DTO 却传字符串，一路 `.name().equals()` 比较易拼错，静默回退到默认（e.g. `"MANAGE"` / `"READ"`）。
**修复**：DTO 直接接 `RoleType` / `Permission`，Jackson 自动反序列化；消除 8 处 `.name().equals()` + `KbAccessServiceImpl:160` 的 try/catch。

### ARCH-3. Service 返回 Controller 内部类 DTO

**位置**：`RoleServiceImpl.java:176` 返回 `List<RoleController.RoleKbBindingRequest>`；`getKbRoleBindings:230` 返回 `List<KnowledgeBaseController.KbRoleBindingVO>`
**症状**：Service 依赖 Controller（倒挂）。
**修复**：DTO 移到 `user/dto/` 或 `role/dto/`。

### ARCH-4. 管理页三个 List 页 ~80% 同构

**位置**：`UserListPage.tsx` / `RoleListPage.tsx` / `DepartmentListPage.tsx`
**症状**：`dialogState: { open, mode, entity }` 形状 + create/edit handler 骨架（每页 ~60 LOC）+ 页头/搜索/Card/Table/AlertDialog 包裹层全部重复。
**修复**：抽 `useCrudDialog<T>()` hook + `<AdminListShell>` 布局组件，预计省 ~180 LOC。

### ARCH-5. `KbSharingTab` 与 `RoleListPage` 的 KB 绑定编辑器 90% 重复

**位置**：`KbSharingTab.tsx:102-176` vs `RoleListPage.tsx:438-522`
**修复**：抽 `<KbBindingEditor bindings onChange />` 共用组件。

---

## 🟢 小清理

### ~~CLEAN-1. 错误吞咽~~ ✅ 已解决（2026-04-14, `feature/cleanup-error-swallows`）

~~`UserListPage.tsx:128` / `SpacesPage.tsx:85` / `KbSharingTab.tsx:39` 多处 `catch { /* ignore */ }` 把网络错误当成权限拒绝处理。应该 `inspect err.code`，只在 RBAC 错误码下静默。~~

**处理**：抽 `isRbacRejection(err)` 到 `utils/error.ts`；3 处 catch 都改为"只在 RBAC 拒绝时静默，否则 toast + console.error"。`KbSharingTab` 的 `setNoAccess(true)` 副作用在 RBAC 分支保留。

### CLEAN-2. `authStore.checkAuth` 未节流

`stores/authStore.ts`：每次组件 mount 都无条件调 `fetchCurrentUser`，忽略缓存。加 `lastVerifiedAt` + 60s 节流。

### CLEAN-3. `UserListPage.handleRefresh` 双 fetch

`UserListPage.tsx:95-98`：`setPageNo(1)` + `loadUsers(1, keyword)` 同时触发，page 已在 1 时会 fetch 两次。

---

## 🗂️ 引用

- 审查来源：本地会话 2026-04-14 `/simplify`（3 个并行 agent：reuse / quality / efficiency）
- 本轮已处理：参见 `log/dev_log/dev_log.md` 的 "2026-04-14" 条目
- 本表不是 TODO 兜底，只记"有意识地留到下一轮"的东西。下一轮 feature 不必优先刷它。
