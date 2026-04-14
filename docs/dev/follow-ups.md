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

### CLEAN-1. 错误吞咽

`UserListPage.tsx:128` / `SpacesPage.tsx:85` / `KbSharingTab.tsx:39` 多处 `catch { /* ignore */ }` 把网络错误当成权限拒绝处理。应该 `inspect err.code`，只在 RBAC 错误码下静默。

### CLEAN-2. `authStore.checkAuth` 未节流

`stores/authStore.ts`：每次组件 mount 都无条件调 `fetchCurrentUser`，忽略缓存。加 `lastVerifiedAt` + 60s 节流。

### CLEAN-3. `UserListPage.handleRefresh` 双 fetch

`UserListPage.tsx:95-98`：`setPageNo(1)` + `loadUsers(1, keyword)` 同时触发，page 已在 1 时会 fetch 两次。

---

## 🗂️ 引用

- 审查来源：本地会话 2026-04-14 `/simplify`（3 个并行 agent：reuse / quality / efficiency）
- 本轮已处理：参见 `log/dev_log/dev_log.md` 的 "2026-04-14" 条目
- 本表不是 TODO 兜底，只记"有意识地留到下一轮"的东西。下一轮 feature 不必优先刷它。
