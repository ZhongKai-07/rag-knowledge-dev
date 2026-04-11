# PR3 — RBAC 前端演示闭环 + Legacy 清理 设计文档

| 字段 | 值 |
|---|---|
| **状态** | Draft（brainstorming 完成，待用户 review） |
| **作者** | brainstorming session 产出 |
| **日期** | 2026-04-12 |
| **适用分支** | `feature/rbac-pr3-frontend-demo`（从 `feature/rbac-security-level-pr1` 起） |
| **前置** | PR0 热修复（commit `aea50bc`）+ PR1 数据层 + 检索过滤（分支 `feature/rbac-security-level-pr1`，19 commits） |
| **相关文档** | `docs/dev/design/rbac-and-security-level-implementation.md`（章节五、七、八） |
| **覆盖 Gap** | PR1 数据层完成但前端无法可操作；PR1 决策 2-A/1-A 需在本 PR 落地；Legacy `LoginUser.role` 字段彻底移除 |

---

## 一、TL;DR

PR1 把 RBAC 数据层（`sys_dept`, `RoleType`, `Permission`, `LoginUser.deptId/roleTypes/maxSecurityLevel`, `MetadataFilter`, `SecurityLevelRefreshEvent`）和检索侧过滤建好了，但**前端没有任何配套**——当前前端仍用 `user.role === "admin"` 一刀切，管理后台所有页面缺少 dept/role_type/security_level 相关字段，Legacy `LoginUser.role` 字段还在 framework 里挂着。

**PR3 的使命**：把 PR1 的能力在前端完整闭环，让用户能通过 UI 完成跨部门隔离演示、security_level 隔离演示、DEPT_ADMIN 自管本部门的全部操作，并彻底删除 legacy role 字段。

**推进方式（方案 C）**：Phase 0 一次性改完所有后端地基 + 前端 authStore/permissions/guards（紧密打包，保 compile-clean），然后 Phase 1-9 按垂直切片逐个前端页面改造。

---

## 二、背景与现状

### 2.1 PR1 已完成

- `sys_dept` 表 + `SysDeptDO` 实体 + `SysDeptMapper`（数据层）
- `t_user.dept_id`、`t_knowledge_base.dept_id` 列
- `RoleType` 枚举（SUPER_ADMIN / DEPT_ADMIN / USER，在 `framework/context`）
- `Permission` 枚举（READ / WRITE / MANAGE，在 `framework/context`）
- `LoginUser` 扩充 `deptId, roleTypes, maxSecurityLevel`
- `KbAccessService.isSuperAdmin() / checkAccess / checkManageAccess / getAccessibleKbIds`
- `UserContextInterceptor` JOIN 查询装配 LoginUser
- `SaTokenStpInterfaceImpl.getRoleList` 返回 `List<RoleType.name()>`
- 现有 `@SaCheckRole("SUPER_ADMIN")` 注解在 RoleController (8) / KnowledgeChunkController (类级) / KnowledgeBaseController (3)
- `KnowledgeDocumentServiceImpl` 里 security_level 变更分支 + RocketMQ `SecurityLevelRefreshEvent` 异步刷新 OpenSearch
- GLOBAL 种子部门 + admin 用户关联 GLOBAL

### 2.2 PR1 未做 / PR3 必须做

- **`sys_dept` 业务层**：无 Service/Controller/前端页
- **`"admin".equals` 散点**：4 处（`SpacesController:54`, `RAGChatServiceImpl:114`, `KnowledgeDocumentController:135`, `KnowledgeBaseController:102`）
- **KB 写接口 DEPT_ADMIN 放行**：3 处（`KnowledgeBaseController` 的 create/update/delete 当前硬编码 `@SaCheckRole("SUPER_ADMIN")`）
- **文档写接口授权 BUG**：`KnowledgeDocumentController` 5 个写接口（upload/startChunk/delete/update/enable）使用 `checkAccess()` READ 权限 —— 这是 PR3 之前就存在的 bug，PR3 必须一并修
- **用户写接口 DEPT_ADMIN 放行**：`UserController` 4 个写接口当前硬编码 `StpUtil.checkRole("SUPER_ADMIN")`
- **`RoleController.setUserRoles` DEPT_ADMIN 放行**：当前硬编码 `@SaCheckRole("SUPER_ADMIN")`
- **`getAccessibleKbIds` / `checkAccess` 缺 DEPT_ADMIN 同部门默认可见逻辑**：PR1 的 RBAC-only 路径不能覆盖"DEPT_ADMIN 本部门 KB 不必显式 role-kb 关系也能看"
- **Legacy `LoginUser.role` 字段**：在 framework 里挂着 `@Deprecated`；后端多处仍调 `getRole()`；前端 `authStore.user.role` 与 `user.role === "admin"` 遍地
- **前端页改造**：UserListPage/RoleListPage 字段简陋；无 DepartmentListPage；KB 创建无 dept_id 选择；文档上传无 security_level；侧边栏/路由无角色过滤
- **DEPT_ADMIN 进后台能力**：当前前端完全没有这条路径

---

## 三、目标与非目标

### 3.1 In-Scope（必须完成）

1. **后端地基（Phase 0）**
   - 新增 `SysDeptService/Impl/Controller` + 相关 DTO
   - 新增 `UserProfileLoader`（抽出 interceptor JOIN 逻辑，三处共享）
   - 新增 `LoadedUserProfile` DTO（bootstrap-internal）
   - 扩充 `LoginVO/CurrentUserVO`（删除 legacy role，加 deptId/deptName/roleTypes/maxSecurityLevel/isSuperAdmin/isDeptAdmin）
   - 扩充 `KbAccessService`：`isDeptAdmin`, `resolveCreateKbDeptId`, `checkCreateUserAccess`, `checkUserManageAccess`, `checkAssignRolesAccess`, `checkDocManageAccess`, `checkDocSecurityLevelAccess`
   - 升级 `getAccessibleKbIds` / `checkAccess`：DEPT_ADMIN 同部门 KB 默认并入
   - `KnowledgeBaseController`：`create` 走 `resolveCreateKbDeptId`（新授权路径，不走 checkManageAccess）；`update` / `delete` 降级到 `checkManageAccess`；`list` 去掉 `"admin".equals`
   - 修复 `KnowledgeDocumentController` 5 写接口到 `checkDocManageAccess`（同时修 PR3 之前的 READ/WRITE 授权 bug）
   - 新增 `PUT /knowledge-base/docs/{docId}/security-level` 专用 endpoint
   - 抽出 `KnowledgeDocumentServiceImpl.updateSecurityLevel()` 独立方法
   - 降级 `UserController` 4 写接口到 `checkCreateUserAccess` / `checkUserManageAccess`
   - 原子化 `POST /users`（事务内写 user + user_role）
   - 降级 `RoleController.setUserRoles` 到 `checkAssignRolesAccess`
   - 收敛 4 处 `"admin".equals`
   - 删除 `framework/context/LoginUser.role` 字段
   - `SysDeptController` 类级 `@SaCheckRole("SUPER_ADMIN")`

2. **前端地基（Phase 0 配套）**
   - 新增 `utils/permissions.ts`（`getPermissions(user)` 纯函数 + `usePermissions()` hook 双层）
   - 新增 `router/guards.tsx`（`RequireAuth / RequireAnyAdmin / RequireSuperAdmin / RequireMenuAccess`）
   - `authStore` state shape 迁移（删除 role，加新字段）
   - `types/index.ts` `User` 类型迁移
   - 所有 `services/*.ts` 类型扩充

3. **垂直切片（Phase 1-9）**
   - Slice 1: 部门管理页（Greenfield）
   - Slice 2: 用户管理页改造
   - Slice 3: 角色管理页升级（不拆分，原地扩展）
   - Slice 4: KB 创建加 dept_id
   - Slice 5: 文档 security_level 全链路（上传 / 列表 / 详情 / 修改按钮 / 专用 endpoint）
   - Slice 6: 侧边栏 + 路由守卫切换到 permissions helper
   - Slice 7: Legacy role 字段清理（前后端同步）
   - Slice 8: Fixture SQL + demo 演示脚本 + `.http` bypass 矩阵
   - Slice 9: 手工走完 12 条验收 checklist

### 3.2 Out-of-Scope（明确不做）

- 测试基础设施（MockMvc / Sa-Token mock / Mockito）→ P3 任务
- RocketMQ `SecurityLevelRefreshEvent` DLQ 监控 → P4 任务
- Windows surefire argline 修复 → P2 任务
- `KnowledgeChunkController` 降级（决策 1-A：保持类级 `@SaCheckRole("SUPER_ADMIN")`）
- `RoleController` 除 setUserRoles 外的 7 个方法降级（决策 2-A：SUPER_ADMIN 独占角色 CRUD）
- GLOBAL 部门下的 DEPT_ADMIN（设计文档 §八 #1，暂不允许）
- 部门层级树（parent_id）
- `t_user.role` 列最终 DROP（保留作 Sa-Token 兼容层使用）
- `UserProfileCache` Redis 层（Phase 0 每请求 JOIN，不加缓存；瓶颈时另做）
- 细粒度 security_level 规则（"level=3 只 SUPER_ADMIN 能设"等）
- 用户调岗（修改 dept_id）的专用 endpoint（本 PR 通过 `checkUserManageAccess` 隐式禁止跨部门修改）
- Role 类型多 KB 绑定的 permission 粒度升级之外的 binding 改动

---

## 四、关键设计决策

### 4.1 继承 PR1 的决策

- **Decision 1-A**（来自 PR1 设计文档 §5）：`KnowledgeChunkController` 维持类级 `@SaCheckRole("SUPER_ADMIN")`，不降级给 DEPT_ADMIN
- **Decision 2-A**（来自 PR1 设计文档 §5）：角色全局化，`t_role` 无 `dept_id`；SUPER_ADMIN 独占 `RoleController` 大部分方法（createRole / updateRole / deleteRole / listRoles / setRoleKnowledgeBases / getRoleKnowledgeBases / getUserRoles 保持 SUPER_ADMIN，**仅 setUserRoles 降级**）

### 4.2 PR3 新决策

- **Decision 3-A**：DEPT_ADMIN 可见的管理后台菜单 = `[dashboard, knowledge, users]`（矩阵 A，3 项）；其他 9 项 SUPER_ADMIN 独占；菜单总数 12 项（PR1 原有 11 项 + PR3 新增 departments）
- **Decision 3-B**：登录后统一 landing `/spaces`，不按角色做跳转
- **Decision 3-C**：前端 `permissions.ts` 采用双层 API —— `getPermissions(user)` 纯函数供非 React 代码和单测使用，`usePermissions()` hook 供组件使用
- **Decision 3-D**：`RequireMenuAccess` 失败策略：`Navigate("/admin/dashboard")` + `toast.info("您没有此页面的访问权限")`，**不做独立 403 页**
- **Decision 3-E**：权限强制的前后端并行原则——UI 过滤必须配对后端拦截，单元测试要能 bypass UI 直打后端验证；这条已写入 memory `feedback_permission_enforcement.md`
- **Decision 3-F**：PR3 分支上任何 commit 都保 compile-clean，后端 contract 变更和前端配套切换在紧密连续的 commit 里完成，**不加 tombstone 临时兼容字段**
- **Decision 3-G**：`UserProfileLoader` Phase 0 不做 Redis 缓存，每请求 JOIN；瓶颈时另做 `UserProfileCache` 独立组件
- **Decision 3-H**：`KbAccessService.resolveCreateKbDeptId(@Nullable String requestedDeptId)` —— 校验 + DEPT_ADMIN 强制锁定本部门合一，返回最终的 effective deptId（比 `void checkCreateKbAccess(...)` 更贴业务）
- **Decision 3-I**：security_level 修改走专用 endpoint `PUT /knowledge-base/docs/{docId}/security-level`，同时**从通用 `KnowledgeDocumentUpdateRequest` 删除 `securityLevel` 字段**，避免两条入口两套规则
- **Decision 3-J**：`POST /users` 原子化 —— 一次请求体里带 `deptId` + `roleIds`，service 层事务内写 user + user_role
- **Decision 3-K**：部门 CRUD 使用 sys_dept 业务键（`dept_code`）作为 fixture 的稳定锚点；GLOBAL 硬保护（不可删不可改名）；删除部门前校验 user/KB 挂载计数 = 0，否则 409 Conflict
- **Decision 3-L**：权限验收断言基于**结构性存在/缺失**（"results 不含 docId=X"），不耦合响应模板文案；这条已写入 memory `feedback_assertion_decoupling.md`
- **Decision 3-M**（P1 Review 补充）：Last SUPER_ADMIN 系统级硬保护 —— 任何时候必须有至少 1 个有效的 SUPER_ADMIN 用户，禁止通过任何 mutation 路径使系统丧失最后一个 SUPER_ADMIN。详见 §5.7

---

## 五、Phase 0：后端地基层详细设计

### 5.1 KbAccessService 方法集（扩充）

```java
public interface KbAccessService {
    // === KB 访问 ===
    /**
     * SUPER_ADMIN 全量；DEPT_ADMIN → RBAC 授权 KB ∪ dept_id 匹配本部门的所有 KB；USER → 仅 RBAC 授权 KB。
     * 【升级自 PR1】语义变更，签名不变。
     *
     * <p><strong>缓存策略（PR3 更新）</strong>：
     * <ul>
     *   <li>SUPER_ADMIN：直接扫全量 KB，不走缓存（PR1 已如此）</li>
     *   <li>DEPT_ADMIN：<b>不走缓存</b>，每次执行 JOIN（RBAC ∪ 同部门 KB）。
     *       原因：同部门 KB 集合依赖 kb.dept_id 运行时值，缓存失效面包含"KB create/delete/dept 变更"等多处 mutation，
     *       挂载复杂且容易漏。DEPT_ADMIN 用户量少，JOIN 成本可忽略。</li>
     *   <li>USER：继续走 Redis 缓存 `kb_access:{userId}`，TTL 30min。
     *       失效触发保持 PR1 原有：user-role 关联变更时 evictCache(userId)。</li>
     * </ul>
     */
    Set<String> getAccessibleKbIds(String userId, Permission minPermission);
    default Set<String> getAccessibleKbIds(String userId) { return getAccessibleKbIds(userId, Permission.READ); }

    /**
     * READ 权限校验。SUPER_ADMIN 放行；系统态放行；
     * DEPT_ADMIN 同部门放行；其他必须有 RBAC 关系。
     * 【升级自 PR1】语义变更，签名不变。
     */
    void checkAccess(String kbId);

    /** MANAGE 权限校验。【PR1 已实现，签名和行为保持】 */
    void checkManageAccess(String kbId);

    /**
     * 创建 KB 时的权限解析器（Decision 3-H）。
     *
     * <p>行为矩阵：
     * <ul>
     *   <li>未登录上下文（`!UserContext.hasUser()`）：抛 `ClientException("未登录用户不可创建知识库")`。
     *       <b>没有任何 GLOBAL fallback 分支</b>——PR3 之前的历史代码里可能存在 currentUser==null → GLOBAL 的默认行为，
     *       PR3 明确封堵这条路径。系统态（MQ 消费者、定时任务）今后不应触发 KB 创建接口。</li>
     *   <li>SUPER_ADMIN：返回 requestedDeptId；若为空则 fallback 到 GLOBAL（'1'）</li>
     *   <li>DEPT_ADMIN：强制返回 self.deptId；忽略 requestedDeptId；若 requestedDeptId 非空且 != self.deptId 抛 403</li>
     *   <li>USER（非 SUPER 非 DEPT）：抛 403</li>
     * </ul>
     *
     * <p>这是 KB create 路径的授权单一真相源。Controller 不再依赖 `@SaCheckRole` 注解，也不依赖 `"admin".equals`。
     * 【新增】
     */
    String resolveCreateKbDeptId(String requestedDeptId);

    // === 文档 ===
    /**
     * 文档级 MANAGE 权限：doc → kb → checkManageAccess(kb.id) 的组合语义。
     * 【新增】
     */
    void checkDocManageAccess(String docId);

    /**
     * 文档 security_level 修改专用守卫。当前等同 checkDocManageAccess；
     * 保留独立方法以便未来加 level-specific 规则。
     * 【新增】
     */
    void checkDocSecurityLevelAccess(String docId, int newLevel);

    // === 用户 ===
    /**
     * 创建用户授权（Decision 3-J）。SUPER_ADMIN 任何 deptId；
     * DEPT_ADMIN 仅 targetDeptId == self.deptId 且 roleIds 中不含 role_type=SUPER_ADMIN 的角色。
     * 【新增】
     */
    void checkCreateUserAccess(String targetDeptId, List<String> roleIds);

    /**
     * 修改/删除/重置密码用户的授权。
     * SUPER_ADMIN 任何 targetUser；DEPT_ADMIN 仅当 loaded targetUser.deptId == self.deptId。
     * 【新增】
     */
    void checkUserManageAccess(String targetUserId);

    /**
     * 给用户分配角色的授权。
     * SUPER_ADMIN 任何；DEPT_ADMIN 仅当 loaded targetUser.deptId == self.deptId
     * 且 newRoleIds 中不含 role_type=SUPER_ADMIN 的角色。
     * 【新增】
     */
    void checkAssignRolesAccess(String targetUserId, List<String> newRoleIds);

    // === 判断 ===
    boolean isSuperAdmin();
    boolean isDeptAdmin();   // 【新增】

    // === 系统完整性保护（Last SUPER_ADMIN 锁死防护）===
    /**
     * 返回当前系统内有效的 SUPER_ADMIN 用户数量。
     * 有效 = t_user.deleted=0 AND 存在至少一个 t_user_role 关联到 role_type=SUPER_ADMIN 的有效（deleted=0）角色。
     *
     * <p>仅做"当前快照"查询；各 mutation 的 pre-check 不直接用它比较 == 1，而是用 post-mutation 模拟（见下）。
     * 【新增，PR3 独有】
     */
    int countActiveSuperAdmins();

    /**
     * 判断某用户当前是否是 SUPER_ADMIN（有任一 role_type=SUPER_ADMIN 的有效角色）。辅助方法。
     */
    boolean isUserSuperAdmin(String userId);

    /**
     * <b>Invariant-preservation 模拟器</b>（P1 Review 核心加固）。
     *
     * <p>传入一次 mutation 的语义，返回 <strong>mutation 完成后</strong> 系统内还会有多少个有效 SUPER_ADMIN 用户。
     * 调用方用返回值做 {@code >= 1} 判断，< 1 即拒绝。
     *
     * <p>支持的 mutation 语义（{@link SuperAdminMutationIntent}）：
     * <ul>
     *   <li>{@code DELETE_USER(userId)} —— 删除某个用户，其所有 user-role 关联被作废</li>
     *   <li>{@code REPLACE_USER_ROLES(userId, newRoleIds)} —— 用 newRoleIds 替换 userId 的角色集（对应 setUserRoles）</li>
     *   <li>{@code CHANGE_ROLE_TYPE(roleId, newRoleType)} —— 改变某角色的 role_type</li>
     *   <li>{@code DELETE_ROLE(roleId)} —— 删除某角色，所有用到它的 user-role 关联被作废</li>
     * </ul>
     *
     * <p>实现要点：不需要真的开事务预执行，只需基于当前 DB 快照对"去掉这一批关联/角色后剩下谁还有 SUPER_ADMIN 来源" 做 SQL 聚合。
     * 注意：必须把 {@code t_role.deleted=0} 和 {@code t_role_kb_relation/t_user_role.deleted=0} 过滤条件都带上，避免误把软删的角色当作有效 SUPER_ADMIN 来源。
     *
     * <p>【核心使用规则】所有 4 处 mutation 路径统一一条规则：
     * <blockquote>
     *   if (simulateActiveSuperAdminCountAfter(intent) < 1) throw ClientException(...);
     * </blockquote>
     * 不再散落"count == 1 才拒绝"这种 pre-mutation 判断 —— 那是 P1 Review 抓到的 delete-role 漏洞的根因
     * （2 个超管都只挂同一个 SUPER_ADMIN 角色 → count==2 放行 → 删完变 0）。
     *
     * 【新增，PR3 独有】
     */
    int simulateActiveSuperAdminCountAfter(SuperAdminMutationIntent intent);

    // === 缓存管理 ===
    void evictCache(String userId);
}
```

### 5.2 UserProfileLoader & LoadedUserProfile

```java
// bootstrap/user/dao/dto/LoadedUserProfile.java
public record LoadedUserProfile(
    String userId,
    String username,
    String avatar,
    String deptId,
    String deptName,              // 来自 sys_dept.dept_name JOIN
    List<String> roleIds,
    Set<RoleType> roleTypes,      // 聚合去重
    int maxSecurityLevel,         // MAX 聚合，没有角色时为 0
    boolean isSuperAdmin,
    boolean isDeptAdmin
) {}

// bootstrap/user/service/UserProfileLoader.java
public interface UserProfileLoader {
    /**
     * 执行完整 JOIN：t_user + sys_dept + t_user_role + t_role
     * 返回 profile 快照。Phase 0 不走缓存（Decision 3-G）。
     */
    LoadedUserProfile load(String userId);
}
```

**映射矩阵**：
- `LoadedUserProfile` → `LoginUser`（framework，授权用）：投影 userId / username / avatar / deptId / roleTypes / maxSecurityLevel
- `LoadedUserProfile` → `LoginVO`（登录返回）：全部字段 + token
- `LoadedUserProfile` → `CurrentUserVO`（/user/me）：全部字段

**调用点**：
- `AuthServiceImpl.login()` → 成功后调 `userProfileLoader.load(userId)` 组装 `LoginVO`
- `UserController.currentUser()` → 调 `userProfileLoader.load(currentUserId)` 组装 `CurrentUserVO`
- `UserContextInterceptor` → 现有 JOIN 逻辑替换为 `userProfileLoader.load(userId)` → `LoginUser`

### 5.3 LoginVO / CurrentUserVO Shape

```java
public record LoginVO(
    String userId,
    String username,
    String avatar,
    String token,
    String deptId,
    String deptName,
    List<String> roleTypes,       // ["SUPER_ADMIN"] or ["DEPT_ADMIN","USER"]
    int maxSecurityLevel,
    boolean isSuperAdmin,
    boolean isDeptAdmin
) {
    // NO legacy role field
}

public record CurrentUserVO(
    String userId,
    String username,
    String avatar,
    String deptId,
    String deptName,
    List<String> roleTypes,
    int maxSecurityLevel,
    boolean isSuperAdmin,
    boolean isDeptAdmin
) {}
```

### 5.4 路由改动表（授权边界收敛）

| Controller | Endpoint | 当前 | PR3 改为 |
|---|---|---|---|
| `KnowledgeBaseController` | `POST /knowledge-base` | `@SaCheckRole("SUPER_ADMIN")` | 移除注解；service 层调 `resolveCreateKbDeptId(request.deptId)` |
| `KnowledgeBaseController` | `PUT /knowledge-base/{id}` | `@SaCheckRole("SUPER_ADMIN")` | `kbAccessService.checkManageAccess(kbId)` |
| `KnowledgeBaseController` | `DELETE /knowledge-base/{id}` | `@SaCheckRole("SUPER_ADMIN")` | `kbAccessService.checkManageAccess(kbId)` |
| `KnowledgeBaseController` | `GET /knowledge-base` (list) | `"admin".equals` | `isSuperAdmin()` + `getAccessibleKbIds()` |
| `KnowledgeDocumentController` | `POST /.../{kbId}/docs/upload` | `checkAccess(kbId)` | `checkManageAccess(kbId)` |
| `KnowledgeDocumentController` | `POST /.../docs/{docId}/chunk` | `checkAccess()` via helper | `checkDocManageAccess(docId)` |
| `KnowledgeDocumentController` | `DELETE /.../docs/{docId}` | `checkAccess()` via helper | `checkDocManageAccess(docId)` |
| `KnowledgeDocumentController` | `PUT /.../docs/{docId}` | `checkAccess()` via helper | `checkDocManageAccess(docId)` |
| `KnowledgeDocumentController` | `PATCH /.../docs/{docId}/enable` | `checkAccess()` via helper | `checkDocManageAccess(docId)` |
| `KnowledgeDocumentController` | `PUT /.../docs/{docId}/security-level` | **不存在** | **新增**，`checkDocSecurityLevelAccess(docId, newLevel)` |
| `KnowledgeDocumentController` | `GET /.../{kbId}/docs` (list) | `checkAccess(kbId)` | 保持 `checkAccess(kbId)` |
| `KnowledgeDocumentController` | `GET /.../docs/{docId}` (get) | `checkAccess(doc.kbId)` | 保持 |
| `KnowledgeDocumentController` | `GET /.../docs/search` | `"admin".equals` | `isSuperAdmin()` + `getAccessibleKbIds()` |
| `RoleController` | `PUT /user/{userId}/roles` | `@SaCheckRole("SUPER_ADMIN")` | `checkAssignRolesAccess(userId, roleIds)` |
| `RoleController` | 其他 7 个方法 | `@SaCheckRole("SUPER_ADMIN")` | **保持**（决策 2-A） |
| `KnowledgeChunkController` | 类级 | `@SaCheckRole("SUPER_ADMIN")` | **保持**（决策 1-A） |
| `UserController` | `GET /users` | `StpUtil.checkRole("SUPER_ADMIN")` | `isSuperAdmin() \|\| isDeptAdmin()` + service 层按 dept 过滤 |
| `UserController` | `POST /users` | `StpUtil.checkRole("SUPER_ADMIN")` | `checkCreateUserAccess(targetDeptId, roleIds)` + 事务化写 user + user_role |
| `UserController` | `PUT /users/{id}` | `StpUtil.checkRole("SUPER_ADMIN")` | `checkUserManageAccess(id)` |
| `UserController` | `DELETE /users/{id}` | `StpUtil.checkRole("SUPER_ADMIN")` | `checkUserManageAccess(id)` |
| `SysDeptController` | 5 个 CRUD | **不存在** | **新增**，类级 `@SaCheckRole("SUPER_ADMIN")` |
| `RAGChatServiceImpl:114` | 检索时 RBAC 绕过 | `"admin".equals` | `isSuperAdmin()` |
| `SpacesController:54` | 空间统计 | `"admin".equals` | `isSuperAdmin()` |

### 5.5 POST /users 原子化（Decision 3-J）

- `UserCreateRequest` 扩充：`deptId: String, roleIds: List<String>`
- `UserServiceImpl.create(request)` 在同一事务内：
  1. 校验 `kbAccessService.checkCreateUserAccess(request.deptId, request.roleIds)`
  2. 写 `t_user`（含 `dept_id`）
  3. 批量写 `t_user_role`（for each roleId in request.roleIds）
  4. Commit
- 前端 `UserListPage.handleSave` 合并原本的两步调用为一步

### 5.6 security_level 入口单一化（Decision 3-I）

- 新增 endpoint：`PUT /knowledge-base/docs/{docId}/security-level`，body `{ "newLevel": 0-3 }`
- Controller 守卫：`kbAccessService.checkDocSecurityLevelAccess(docId, newLevel)`
- Controller 实现：调用 `knowledgeDocumentService.updateSecurityLevel(docId, newLevel)`
- `KnowledgeDocumentServiceImpl.updateSecurityLevel()` 独立方法：抽自现有 `update()` 大方法里的 security_level 分支（line 531-575），复用 MQ 刷新逻辑
- **从 `KnowledgeDocumentUpdateRequest` 删除 `securityLevel` 字段**（当前存在于 line 68）。通用 `PUT /knowledge-base/docs/{docId}` 今后不再承载 security_level 变更语义
- **`KnowledgeDocumentUploadRequest.securityLevel` 保留**（当前存在于 line 73）。上传时首次设 security_level 仍然走 upload endpoint，这是正常创建路径，不属于"变更"语义，不走 MQ 刷新
- 若 writing-plans 阶段发现 `update` 方法的其他调用点依赖通用 DTO 里的 `securityLevel` 字段，列为该 task 的 pre-work grep

### 5.7 Last SUPER_ADMIN 保护规则（Decision 3-M，P1+P2 Review 两轮加固版）

**动机**：`sys_dept` 的 GLOBAL 部门有硬保护，admin 种子用户有 `isProtectedAdmin` UI 守护，但"系统必须始终至少有一个有效 SUPER_ADMIN 用户" 这条不变量在 PR1 之前没有后端强制。如果运维不小心把唯一的 admin 用户删了，或者把 admin 的 SUPER_ADMIN 角色解绑了，或者把挂给 admin 的那个 SUPER_ADMIN 角色定义本身删了，系统会陷入"没人能进 SUPER-only 页面"的死锁 —— 只能 SQL 直改数据库恢复。

**核心不变量（P1 Review 加固版）**：
> 系统任何时候都必须满足 `countActiveSuperAdmins() >= 1`。
> 每处可能减少 SUPER_ADMIN 用户数的 mutation，**必须用 post-mutation 模拟计算 `simulateActiveSuperAdminCountAfter(intent)`**，若结果 < 1 立即拒绝。
> **不允许**用 pre-mutation 的 `countActiveSuperAdmins() == 1` 作为判据 —— 那会漏掉"2 个超管都只挂同一个 SUPER_ADMIN 角色，一次 delete-role 把俩都扒光"这类多元归零场景（P1 Review 原例）。

**4 处后端 mutation 的 pre-check（统一模板）**：

```java
// 伪码
SuperAdminMutationIntent intent = /* 构造具体 intent */;
if (kbAccessService.simulateActiveSuperAdminCountAfter(intent) < 1) {
    throw new ClientException("该操作会使系统失去最后一个 SUPER_ADMIN 用户，已拒绝");
}
// ... 实际执行 mutation
```

| 调用点 | 构造的 intent | 对应场景 |
|---|---|---|
| `UserServiceImpl.delete(targetUserId)` | `DELETE_USER(targetUserId)` | 删除用户直接/间接吊销其所有 SUPER_ADMIN 来源 |
| `RoleServiceImpl.setUserRoles(targetUserId, newRoleIds)` | `REPLACE_USER_ROLES(targetUserId, newRoleIds)` | 替换角色集后 target 可能失去所有 SUPER_ADMIN 角色 |
| `RoleServiceImpl.updateRole(roleId, newRoleType)` | `CHANGE_ROLE_TYPE(roleId, newRoleType)` 若 `newRoleType != SUPER_ADMIN` 且 `oldRoleType == SUPER_ADMIN` | 角色类型从 SUPER_ADMIN 降级，所有挂载此角色的用户可能一齐失去超管身份 |
| `RoleServiceImpl.deleteRole(roleId)` | `DELETE_ROLE(roleId)` 若该角色 `role_type == SUPER_ADMIN` | **P1 Review 抓到的漏洞修复点**：即便 `countActiveSuperAdmins() > 1`，只要所有当前超管都依赖这个角色，删完就归零 |

**错误消息分级**（可选，不同调用点可用不同文案提高 UX）：
- DELETE_USER → "不能删除该用户：此操作会使系统失去最后一个 SUPER_ADMIN"
- REPLACE_USER_ROLES → "不能修改该用户角色：此操作会使系统失去最后一个 SUPER_ADMIN"
- CHANGE_ROLE_TYPE → "不能降级该角色：此操作会使系统失去最后一个 SUPER_ADMIN"
- DELETE_ROLE → "不能删除该角色：此操作会使系统失去最后一个 SUPER_ADMIN"

`SysDeptServiceImpl.delete(deptId)` 不涉及 SUPER_ADMIN 不变量（GLOBAL 部门保护是独立规则，见 Slice 1）。

**前端配套（P2 Review 简化版）**：

> 前端**不做**任何静态"最后一个 SUPER_ADMIN"的禁用判断。原因是前端没有可靠的全局 `activeSuperAdminCount` 数据源 —— 若硬造一个新的 GET endpoint 或在列表 envelope 塞全局计数，都会污染 API shape 并引入跨行 race。

- **`UserListPage` 列表**：admin 种子用户的"删除"按钮保持 disabled（已有 `isProtectedAdmin` UI 守护，这是 PR0 就有的软 UX 层）
- **用户编辑 Dialog**：角色多选框**不做**任何"不可 uncheck SUPER_ADMIN"的客户端逻辑
- **所有 4 处 mutation 按钮**：乐观放行，点击后直接打后端；后端返回 `ClientException` 时用 `toast.error(err.message)` 显示分级文案
- **错误处理位置**：在现有 `services/userService.ts` / `services/roleService.ts` 的请求拦截器里统一捕获 400 响应，抛友好错误；组件只需 `try / catch` 兜一下

**为什么不能单靠 UI 守护**：前端守护是 UX，后端是授权边界（见 memory `feedback_permission_enforcement.md`）。直接 curl `DELETE /users/{adminId}` 或 `DELETE /role/{superAdminRoleId}` 也必须被后端拦住。这条和上面"前端不做静态禁用"并不矛盾 —— 前者说"后端必须拦"，后者说"前端不要假装能预测"，两条合起来就是"后端是权威源，前端只处理响应"。

### 5.8 Git Commit Hygiene（Decision 3-F）

**铁律**：PR3 分支上不能有任何一个 commit 是前端不可编译的状态。

具体约束：
- Phase 0 后端 contract 变更（`LoginVO` / `CurrentUserVO` / `LoginUser.role` 删除等）必须和 Slice 6（前端 `permissions.ts` 切换）+ Slice 7（`authStore.user.role` 清理）打包进紧密连续的 commit
- 不使用 tombstone 临时兼容字段
- 由 writing-plans 阶段将任务排序为合理的 commit 粒度

### 5.9 Phase 0 文件清单

**NEW（9 个）**
- `user/dao/dto/LoadedUserProfile.java` (record)
- `user/service/UserProfileLoader.java` (interface)
- `user/service/impl/UserProfileLoaderImpl.java`
- `user/service/SysDeptService.java`
- `user/service/impl/SysDeptServiceImpl.java`
- `user/controller/SysDeptController.java`
- `user/controller/request/SysDeptCreateRequest.java`
- `user/controller/request/SysDeptUpdateRequest.java`
- `user/controller/vo/SysDeptVO.java`

**MOD（18 个）**
- `framework/context/LoginUser.java` — 删除 `role` 字段
- `user/controller/vo/LoginVO.java` — 扩充
- `user/controller/vo/CurrentUserVO.java` — 扩充
- `user/controller/vo/UserVO.java` — 列表项扩充
- `user/controller/request/UserCreateRequest.java` — 加 deptId + roleIds
- `user/controller/request/UserUpdateRequest.java` — 加 deptId
- `user/service/impl/AuthServiceImpl.java` — login 走 loader
- `user/service/impl/UserServiceImpl.java` — create 事务化 + 校验；`delete()` 加 Last SUPER_ADMIN pre-check（§5.7）
- `user/service/impl/RoleServiceImpl.java` — `setUserRoles` 切到 `checkAssignRolesAccess` + Last SUPER_ADMIN pre-check；`updateRole` / `deleteRole` 对 role_type=SUPER_ADMIN 做 Last SUPER_ADMIN pre-check
- `user/service/KbAccessService.java` + `impl/KbAccessServiceImpl.java` — 新方法集 + DEPT_ADMIN-aware getAccessibleKbIds/checkAccess
- `user/config/UserContextInterceptor.java` — 走 loader
- `user/controller/UserController.java` — 4 个写接口切换
- `user/controller/RoleController.java` — setUserRoles 切到 checkAssignRolesAccess
- `knowledge/controller/KnowledgeBaseController.java` — 3 个写接口 + 1 处 admin.equals
- `knowledge/controller/KnowledgeDocumentController.java` — 5 个写接口 + 1 处 admin.equals + 新增 security-level endpoint
- `knowledge/controller/SpacesController.java` — 1 处 admin.equals
- `knowledge/service/impl/KnowledgeDocumentServiceImpl.java` — 抽出 updateSecurityLevel 独立方法
- `rag/service/impl/RAGChatServiceImpl.java` — 1 处 admin.equals

**AUDIT（不改，但 Phase 0 完成后做回归）**
- `user/config/SaTokenStpInterfaceImpl.java` — PR1 已切好，验证所有 `@SaCheckRole("SUPER_ADMIN")` 仍生效

---

## 六、前端架构改动（Phase 0 配套）

### 6.1 authStore / User 类型迁移

```typescript
// types/index.ts
interface User {
  userId: string;                   // 与后端 LoginVO / CurrentUserVO 字段名对齐；PR3 前曾为 `id`，PR3 统一迁移
  username: string;
  avatar?: string;
  deptId: string | null;
  deptName: string | null;
  roleTypes: string[];              // ["SUPER_ADMIN"] or ["DEPT_ADMIN","USER"]
  maxSecurityLevel: number;
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
  // NO role field
}
```

`authStore.ts` 的 `login()` / `checkAuth()` 都装配新 shape。所有 `user.role === "admin"` 引用必须 grep 清零。

### 6.2 `utils/permissions.ts`（Decision 3-C）

```typescript
import { useMemo } from "react";
import { useAuthStore } from "@/stores/authStore";
import type { User } from "@/types";

export type AdminMenuId =
  | "dashboard" | "knowledge" | "users"           // SUPER + DEPT
  | "intent-tree" | "ingestion" | "mappings"
  | "traces" | "evaluations" | "sample-questions"
  | "roles" | "departments" | "settings";         // SUPER only

const DEPT_VISIBLE: AdminMenuId[] = ["dashboard", "knowledge", "users"];

export interface Permissions {
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
  isAnyAdmin: boolean;
  deptId: string | null;
  deptName: string | null;
  maxSecurityLevel: number;
  canSeeAdminMenu: boolean;
  canSeeMenuItem: (id: AdminMenuId) => boolean;
  canCreateKb: (targetDeptId: string) => boolean;
  canManageKb: (kb: { deptId: string }) => boolean;
  canManageUser: (targetUser: { deptId: string }) => boolean;
  canEditDocSecurityLevel: (doc: { kbDeptId: string }) => boolean;
  canAssignRole: (role: { roleType: string }) => boolean;
}

// 纯函数：单测/工具函数/非 React 代码用
export function getPermissions(user: User | null): Permissions {
  const isSuperAdmin = user?.isSuperAdmin ?? false;
  const isDeptAdmin = user?.isDeptAdmin ?? false;
  const isAnyAdmin = isSuperAdmin || isDeptAdmin;
  return {
    isSuperAdmin,
    isDeptAdmin,
    isAnyAdmin,
    deptId: user?.deptId ?? null,
    deptName: user?.deptName ?? null,
    maxSecurityLevel: user?.maxSecurityLevel ?? 0,
    canSeeAdminMenu: isAnyAdmin,
    canSeeMenuItem: (id) => isSuperAdmin || (isDeptAdmin && DEPT_VISIBLE.includes(id)),
    canCreateKb: (targetDeptId) => isSuperAdmin || (isDeptAdmin && targetDeptId === user?.deptId),
    canManageKb: (kb) => isSuperAdmin || (isDeptAdmin && kb.deptId === user?.deptId),
    canManageUser: (targetUser) => isSuperAdmin || (isDeptAdmin && targetUser.deptId === user?.deptId),
    canEditDocSecurityLevel: (doc) => isSuperAdmin || (isDeptAdmin && doc.kbDeptId === user?.deptId),
    canAssignRole: (role) => isSuperAdmin || role.roleType !== "SUPER_ADMIN",
  };
}

// React hook：薄封装
export function usePermissions(): Permissions {
  const user = useAuthStore(s => s.user);
  return useMemo(() => getPermissions(user), [user]);
}
```

### 6.3 Route Guards（Decision 3-D）

```tsx
// router/guards.tsx
function RequireAnyAdmin({ children }: { children: ReactNode }) {
  const { canSeeAdminMenu } = usePermissions();
  if (!canSeeAdminMenu) return <Navigate to="/spaces" replace />;
  return <>{children}</>;
}

function RequireSuperAdmin({ children }: { children: ReactNode }) {
  const { isSuperAdmin } = usePermissions();
  if (!isSuperAdmin) {
    toast.info("您没有此页面的访问权限");
    return <Navigate to="/admin/dashboard" replace />;
  }
  return <>{children}</>;
}

function RequireMenuAccess({ menuId, children }: { menuId: AdminMenuId; children: ReactNode }) {
  const { canSeeMenuItem } = usePermissions();
  if (!canSeeMenuItem(menuId)) {
    toast.info("您没有此页面的访问权限");
    return <Navigate to="/admin/dashboard" replace />;
  }
  return <>{children}</>;
}
```

`router.tsx` 路由树示例：
```tsx
<Route element={<RequireAnyAdmin><AdminLayout /></RequireAnyAdmin>}>
  <Route path="/admin/dashboard" element={<RequireMenuAccess menuId="dashboard"><DashboardPage /></RequireMenuAccess>} />
  <Route path="/admin/knowledge" element={<RequireMenuAccess menuId="knowledge"><KnowledgeListPage /></RequireMenuAccess>} />
  <Route path="/admin/users" element={<RequireMenuAccess menuId="users"><UserListPage /></RequireMenuAccess>} />
  <Route path="/admin/departments" element={<RequireSuperAdmin><DepartmentListPage /></RequireSuperAdmin>} />
  <Route path="/admin/roles" element={<RequireSuperAdmin><RoleListPage /></RequireSuperAdmin>} />
  {/* 其他 9 项 SUPER_ADMIN only */}
</Route>
```

### 6.4 Sidebar 过滤 + roleLabel 四档 fallback

```tsx
// AdminLayout.tsx
const permissions = usePermissions();

const visibleMenuGroups = useMemo(
  () => menuGroups
    .map(group => ({
      ...group,
      items: group.items.filter(item => permissions.canSeeMenuItem(item.id))
    }))
    .filter(group => group.items.length > 0),
  [permissions]
);

const roleLabel = useMemo(() => {
  if (permissions.isSuperAdmin) return "超级管理员";
  if (permissions.isDeptAdmin && permissions.deptName) return `${permissions.deptName}管理员`;
  if (permissions.isDeptAdmin) return "部门管理员";
  return "成员";
}, [permissions]);
```

`menuGroups` 数组每一项必须加 `id: AdminMenuId` 字段。

### 6.5 其他前端入口点

- `ChatPage` / `Sidebar` / `SpacesPage` 中"进入后台"按钮条件从 `user?.role === "admin"` 切到 `permissions.canSeeAdminMenu`
- `authService.ts` LoginResponse 类型扩充
- 所有 `services/*.ts` 的 DTO 类型对齐后端 Phase 0 新 shape

---

## 七、9 个垂直切片详细交付

### Slice 1: 部门管理页（Greenfield）

**后端**
- `SysDeptService` / `SysDeptServiceImpl`：
  - `list(keyword)`, `create(request)`, `update(id, request)`, `delete(id)`, `getById(id)`
  - `create` 唯一性校验 `dept_code`
  - `delete` 预检：`id != '1'`（GLOBAL）且 `SELECT COUNT FROM t_user WHERE dept_id=?` = 0 且 `SELECT COUNT FROM t_knowledge_base WHERE dept_id=?` = 0，否则 `ClientException "部门下仍有用户/知识库，不可删除"`
  - `update` 禁止修改 GLOBAL 的 `dept_code` 和 `dept_name`
- `SysDeptController` 类级 `@SaCheckRole("SUPER_ADMIN")`：
  - `GET /sys-dept` — 列表 + keyword 可选
  - `POST /sys-dept` — 创建
  - `PUT /sys-dept/{id}` — 更新
  - `DELETE /sys-dept/{id}` — 删除
  - `GET /sys-dept/{id}` — 单个（可选）

**前端**
- `pages/admin/departments/DepartmentListPage.tsx`（见 mockup）
- `services/sysDeptService.ts`
- router.tsx 注册 `/admin/departments`
- `AdminLayout.tsx` 的 `menuGroups` 设置区组加入 `{ id: "departments", path: "/admin/departments", label: "部门管理", icon: Building2 }`
- 面包屑 `breadcrumbMap` 加 `departments: "部门管理"`

**验收**
- SUPER_ADMIN 登录 → /admin/departments → 创建 "研发部/RND"、"法务部/LEGAL"
- 点删除已被挂载用户的部门 → 弹出 409 错误提示
- DEPT_ADMIN 访问该 URL → Navigate + toast

### Slice 2: 用户管理页改造

**前端**
- `UserListPage.tsx` 大改（见 mockup）
  - 列表列：用户 | 部门（Badge chip） | 角色类型（多个 chip 堆叠） | 最大密级（彩色 Badge） | 操作
  - 搜索框保留
  - 新建/编辑 Dialog 字段：
    - 用户名 + 密码
    - **部门** 下拉（`permissions.isSuperAdmin` → 所有部门可选；`permissions.isDeptAdmin` → 只能选 `self.deptId` 且 disabled）
    - **角色多选** checkbox（通过 `permissions.canAssignRole(role)` 过滤掉 `role_type=SUPER_ADMIN` 的选项）
    - 头像
  - **删除** 老的 `roleOptions` admin/user 下拉 + `form.role` 字段 + `roleLabel` 老逻辑
  - `handleSave` 合并两步调用为单次 `createUser({ ...payload, deptId, roleIds })`
  - 列表按 `permissions.canManageUser(user)` 决定是否显示编辑/删除按钮
- `services/userService.ts`：
  - `UserCreatePayload` 加 `deptId, roleIds`
  - `UserUpdatePayload` 加 `deptId`
  - `UserItem` 加 `deptId, deptName, roleTypes, maxSecurityLevel`

**后端**（Phase 0 已覆盖）
- `UserCreateRequest` / `UserUpdateRequest` / `UserServiceImpl.create` / `UserController.pageQuery` 按 dept 过滤（DEPT_ADMIN 视角）

**验收**
- DEPT_ADMIN alice 登录 → /admin/users 只看到研发部用户
- 新建用户 "carol"：部门下拉锁定 "研发部"，角色下拉没有任何 SUPER_ADMIN 类型的选项
- 提交 → service 层事务写 user + user_role → 列表立即可见
- alice 尝试通过手工改 deptId 提交 → 后端 403

### Slice 3: 角色管理页升级

**前端**
- `RoleListPage.tsx` 升级（见 mockup）
  - 列表新增两列：角色类型（chip） + 最大密级（Badge）
  - 编辑 Dialog 新增 `role_type` 下拉（SUPER_ADMIN 只在 SUPER_ADMIN 当前用户时可选）+ `max_security_level` 下拉（0-3）
  - KB 绑定 Dialog 每行加 `permission` 下拉（READ / WRITE / MANAGE），未 checked 的行 permission 下拉置灰可见但不可改
- `services/roleService.ts`：
  - `RoleItem` 加 `roleType, maxSecurityLevel`
  - `RoleCreatePayload` 加 `roleType, maxSecurityLevel`
  - `setRoleKnowledgeBases(roleId, bindings)` payload 从 `string[]` 升级到 `Array<{ kbId: string, permission: "READ"|"WRITE"|"MANAGE" }>`
  - `getRoleKnowledgeBases(roleId)` 返回 `Array<{ kbId, permission }>`

**后端**
- `RoleCreateRequest` 加 `roleType` (String)、`maxSecurityLevel` (int)
- `RoleServiceImpl.createRole/updateRole` 扩充写入这两个字段
- `RoleServiceImpl.setRoleKnowledgeBases(roleId, List<RoleKbBinding>)` 签名升级；`RoleKbBinding` 为 bootstrap 内部的 record `(kbId, permission)`
- `t_role_kb_relation.permission` 列已在 PR1 建好

**验收**
- SUPER_ADMIN 创建 "研发部管理员"（role_type=DEPT_ADMIN, max=3）
- 绑定 "研发知识库" permission=MANAGE
- 编辑时这两个字段能正确回显
- DEPT_ADMIN 访问该 URL → Navigate + toast

### Slice 4: KB 创建 dept_id

**前端**
- `CreateKnowledgeBaseDialog.tsx`（或 KB 创建表单）加 dept_id 下拉
  - SUPER_ADMIN：从 `/sys-dept` 拉所有部门，默认 GLOBAL
  - DEPT_ADMIN：锁定 `self.deptId`，disabled
- `services/knowledgeService.ts` 的 `createKnowledgeBase` payload 加 `deptId`；`KnowledgeBase` 类型加 `deptId, deptName`

**后端**
- `KnowledgeBaseCreateRequest` 已有 `deptId` 字段（PR1）—— 验证一次
- `KnowledgeBaseServiceImpl.create` 调 `kbAccessService.resolveCreateKbDeptId(request.deptId)` 获取最终 effective deptId

**验收**
- alice 创建 "研发 Q2 文档库" → 自动归属研发部（dept_id 锁定）→ 列表可见

### Slice 5: 文档 security_level 全链路

**前端**
- 上传 Dialog `CreateKnowledgeDocumentDialog.tsx` 加 security_level 下拉（0-3，默认 0），选项文字格式 "0 公开 / 1 内部 / 2 机密 / 3 绝密"；上传时前端 payload 加 `securityLevel`（后端 DTO 字段已存在）
- 列表 `KnowledgeDocumentsPage.tsx` 表格加密级列，用彩色 Badge（见 mockup）
- 详情页头部加密级 Badge + "修改密级" 按钮，按钮可见性通过 `permissions.canEditDocSecurityLevel(doc)` 控制
- 点"修改密级"弹 Dialog：新密级选择 + 提示"该操作会异步刷新 OpenSearch metadata（RocketMQ）"
- `services/knowledgeService.ts`：
  - `KnowledgeDocumentSearchItem` / `KnowledgeDocumentVO` 加 `securityLevel`
  - 上传 payload 类型加 `securityLevel`（后端 DTO 已就绪）
  - 新增 `updateDocumentSecurityLevel(docId, newLevel)` 调新 endpoint

**后端**（Phase 0 覆盖 endpoint + service 抽出）
- Controller 层校验 `checkDocSecurityLevelAccess(docId, newLevel)`
- Service 层复用现有 `SecurityLevelRefreshEvent` MQ 路径
- 【验证点】`KnowledgeDocumentUploadRequest.securityLevel` 字段 PR1 已就绪（line 73），前端对齐即可，无需后端改动

**验收**
- alice 上传 "机密架构.md" 密级=2 → 列表可见彩色 Badge
- 点详情 → 点"修改密级"改为 3 → MQ 日志确认已发出刷新事件
- carol（max_security_level=0）登录 chat → 问涉及该文档的问题 → **检索结果不包含该文档**（结构性断言，非文案耦合）

### Slice 6: 侧边栏 + 路由守卫切换

**前端**
- `utils/permissions.ts` 按 §6.2 建立
- `router/guards.tsx` 按 §6.3 建立
- `router.tsx` 全量替换 `RequireAdmin` 为新守卫
- `AdminLayout.tsx` 的 `menuGroups` 加 `id`，useMemo 过滤显示
- `AdminLayout.tsx` `roleLabel` 改四档 fallback
- `ChatPage` / `SpacesPage` / `Sidebar` 的后台入口按钮切到 `permissions.canSeeAdminMenu`

**验收**
- SUPER_ADMIN 登录 → /admin 显示 12 项完整菜单
- DEPT_ADMIN 登录 → /admin 显示 3 项（Dashboard / 知识库 / 用户管理）
- DEPT_ADMIN 直输 `/admin/roles` → Navigate + toast
- USER 登录 → 进 /admin 入口按钮不可见；直输 `/admin/dashboard` → Navigate /spaces
- Grep `user\.role\s*===\s*"admin"` 零命中

### Slice 7: Legacy 清理

**后端**
- `framework/context/LoginUser.java` **删除** `role` 字段
- 所有 `user.getRole()` / `LoginUser.getRole()` 调用修掉（`UserController` 装配 `CurrentUserVO` / `AuthServiceImpl.login` 组装 `LoginVO` 改为从 `LoadedUserProfile` 映射）
- `t_user.role` 列**保留**（Sa-Token 兼容层使用），不 drop

**前端**
- `types/index.ts` 的 `User` 类型**删除** `role` 字段
- `authStore.ts` 所有引用切换
- grep `authStore.user.role` / `user.role ===` / `user?.role ===` 零命中

**验收**
- 后端全量编译通过
- 前端全量编译通过（TypeScript 严格模式）
- grep `LoginUser\.role\|LoginUser\.getRole\|\.role\s*=\s*user\.role` 零命中（后端）

### Slice 8: Fixture SQL + Demo 脚本 + .http 矩阵

**交付 1：`resources/database/fixture_pr3_demo.sql`**

可重复执行（基于业务键清理 + 插入）：
```sql
-- 1. 清理先前的演示数据（基于业务键）
DELETE FROM t_role_kb_relation WHERE role_id IN (
  SELECT id FROM t_role WHERE name IN ('研发部管理员', '法务部管理员', '普通研发员', '普通法务员')
);
DELETE FROM t_user_role WHERE user_id IN (
  SELECT id FROM t_user WHERE username IN ('alice', 'bob', 'carol')
);
DELETE FROM t_role WHERE name IN ('研发部管理员', '法务部管理员', '普通研发员', '普通法务员');
DELETE FROM t_user WHERE username IN ('alice', 'bob', 'carol');
DELETE FROM t_knowledge_base WHERE name IN ('研发知识库', '法务知识库');
DELETE FROM sys_dept WHERE dept_code IN ('RND', 'LEGAL');

-- 2. 部门
INSERT INTO sys_dept (id, dept_code, dept_name) VALUES
  ('2', 'RND', '研发部'),
  ('3', 'LEGAL', '法务部');

-- 3. 用户（密码都是 123456）
INSERT INTO t_user (id, username, password, role, dept_id) VALUES
  ('10', 'alice', '123456', 'user', '2'),
  ('11', 'bob', '123456', 'user', '3'),
  ('12', 'carol', '123456', 'user', '2');

-- 4. 角色（role_type / max_security_level 在 t_role 上，PR1 已加列）
INSERT INTO t_role (id, name, description, role_type, max_security_level) VALUES
  ('100', '研发部管理员', '管理研发部的 KB 和用户', 'DEPT_ADMIN', 3),
  ('101', '法务部管理员', '管理法务部的 KB 和用户', 'DEPT_ADMIN', 3),
  ('102', '普通研发员', '只读访问研发 KB', 'USER', 0),
  ('103', '普通法务员', '只读访问法务 KB', 'USER', 0);

-- 5. 用户-角色关联
INSERT INTO t_user_role (user_id, role_id) VALUES
  ('10', '100'),  -- alice = 研发部管理员
  ('11', '101'),  -- bob = 法务部管理员
  ('12', '102');  -- carol = 普通研发员

-- 6. KB（预建，供 Slice 4/5 演示挂载）
INSERT INTO t_knowledge_base (id, name, description, dept_id, ...) VALUES
  ('kb-rnd-001', '研发知识库', '研发部技术文档', '2', ...),
  ('kb-legal-001', '法务知识库', '法务部合同文档', '3', ...);

-- 7. 角色-KB 关联（PR1 结构 + permission）
INSERT INTO t_role_kb_relation (role_id, kb_id, permission) VALUES
  ('100', 'kb-rnd-001', 'MANAGE'),
  ('101', 'kb-legal-001', 'MANAGE'),
  ('102', 'kb-rnd-001', 'READ');
```

**交付 2：`docs/dev/pr3-demo-walkthrough.md`**

9 步演示脚本，严格对应验收 checklist 12 条。包含：
- 登录 URL、账号密码
- 每步的点击路径和预期结果
- Slice 5 需要手动 UI 上传两份测试文档（一份 security_level=0，一份 security_level=2）的精确步骤

**交付 3：`docs/dev/pr3-curl-matrix.http`**

HTTP Client 格式的 bypass 测试矩阵，覆盖 §九 权限规则目录里所有"有真实 HTTP 边界"的规则。UI-only 规则（如菜单隐藏）在文件里写注释说明。示例：
```http
### (R3) DEPT_ADMIN curl GET /sys-dept → 403
GET http://localhost:9090/api/ragent/sys-dept
Authorization: Bearer {{dept_admin_token}}

### (R7) DEPT_ADMIN 改其他部门 KB → 403
PUT http://localhost:9090/api/ragent/knowledge-base/kb-legal-001
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "name": "偷改" }

### (R12) DEPT_ADMIN 创建用户给 SUPER_ADMIN 角色 → 403
POST http://localhost:9090/api/ragent/users
Authorization: Bearer {{alice_token}}
Content-Type: application/json

{ "username": "hacker", "password": "x", "deptId": "2", "roleIds": ["<SUPER_ADMIN role id>"] }
```

**交付 4：`README.md`** 加一节"PR3 Demo 演示流程"指路到上述三个文件

### Slice 9: 手工验收跑完 12 条 checklist

见下一节 §十 完整清单。Slice 9 不产出代码，只产出 `docs/dev/pr3-verification-log.md`（记录每条 checklist 的运行结果和遇到的问题，用于 debug 和复盘）。

---

## 八、Slice 依赖关系

```
Phase 0 (后端地基 + authStore + utils/permissions.ts)
  │
  ├─ Slice 6 (guards + sidebar) 依赖 permissions.ts
  ├─ Slice 7 (legacy cleanup) 依赖 Phase 0 + Slice 6
  │
  ├─ Slice 1 (部门页 greenfield) 可并行
  ├─ Slice 4 (KB dept_id) 可并行
  │
  ├─ Slice 2 (用户页) 依赖 Slice 1 (拉部门列表) + Slice 6
  ├─ Slice 3 (角色页) 依赖 Slice 6
  ├─ Slice 5 (文档密级) 依赖 Slice 4 + Slice 6
  │
  ├─ Slice 8 (fixture) 依赖 Slice 1/2/3/4/5 endpoint 完整
  └─ Slice 9 (验收) 依赖所有其他 slice 完成
```

由 writing-plans 阶段展开成合理的任务排序，同时保持 Decision 3-F 的 compile-clean 铁律。

---

## 九、完整权限规则目录

| # | 操作 | 前端过滤 | 后端拦截 | Bypass 预期 |
|---|---|---|---|---|
| R1 | 进入 `/admin` 下任一页 | `RequireAnyAdmin` guard | 每个 endpoint 单独鉴权 | 浏览器：redirect 到 /spaces；raw API：403 |
| R2 | 查看 SUPER-only 菜单项（9 项） | `canSeeMenuItem(id)` filter | 对应 endpoint 的守护 | 浏览器：redirect 到 /admin/dashboard + toast；raw API：403 |
| R3 | 查看部门列表 | 只 SUPER_ADMIN 可见菜单 | `GET /sys-dept` 类级 SUPER_ADMIN | DEPT_ADMIN raw → 403 |
| R4 | 创建/改/删除部门 | 只 SUPER_ADMIN 可见按钮 | 类级 SUPER_ADMIN + GLOBAL 保护 + 预检挂载计数 | DEPT_ADMIN raw → 403；删除已挂载部门 → 409 |
| R5 | 查看 KB 列表 | 自动过滤（后端返回已是过滤后的） | `isSuperAdmin()` 返全量 / 否则 `getAccessibleKbIds()` | — |
| R6 | 创建 KB | SUPER_ADMIN 任意部门；DEPT_ADMIN 锁定 | `resolveCreateKbDeptId(requestedDeptId)` | DEPT_ADMIN 传其他部门 deptId → 403 |
| R7 | 改 / 删 KB | `canManageKb(kb)` hide 按钮 | `checkManageAccess(kbId)` | DEPT_ADMIN raw 改其他部门 → 403 |
| R8 | 查看文档列表 / 详情 | 自动 | `checkAccess(kbId)` v2（DEPT_ADMIN 同部门放行） | — |
| R9 | 上传 / 触发分块 / 删除 / 改 / 启停文档 | `canManageKb(kb)` hide 按钮 | `checkDocManageAccess(docId)` 或 `checkManageAccess(kbId)` | DEPT_ADMIN raw 打其他部门 → 403 |
| R10 | 改文档 security_level | `canEditDocSecurityLevel(doc)` hide 按钮 | `checkDocSecurityLevelAccess(docId, newLevel)` | DEPT_ADMIN raw 改其他部门 → 403 |
| R11 | 查看用户列表 | `canSeeMenuItem("users")` | `isSuperAdmin() \|\| isDeptAdmin()` + service 层按 dept 过滤 | USER raw → 403 |
| R12 | 创建用户 | DEPT_ADMIN 部门锁定；过滤 SUPER_ADMIN 角色 | `checkCreateUserAccess(targetDeptId, roleIds)` | DEPT_ADMIN raw 传其他部门或 SUPER_ADMIN roleId → 403 |
| R13 | 改 / 删 / 重置密码用户 | `canManageUser(targetUser)` hide 按钮 | `checkUserManageAccess(targetUserId)` | DEPT_ADMIN raw 改其他部门用户 → 403 |
| R14 | 分配用户角色 | `canAssignRole(role)` filter 下拉 | `checkAssignRolesAccess(targetUserId, newRoleIds)` | DEPT_ADMIN raw 给本部门用户分配 SUPER_ADMIN → 403 |
| R15 | 查看 / 改 / 删角色定义 | 只 SUPER_ADMIN 可见菜单 | 类级 `@SaCheckRole("SUPER_ADMIN")` | DEPT_ADMIN raw → 403 |
| R16 | 绑定角色到 KB | 只 SUPER_ADMIN 可见按钮 | 类级 `@SaCheckRole("SUPER_ADMIN")` | DEPT_ADMIN raw → 403 |
| R17 | RAG 问答检索 | 不过滤 UI | `getAccessibleKbIds()` v2 + OpenSearch `security_level <= user.maxSecurityLevel` 过滤 | carol (max=0) 问包含 level=2 文档的问题 → **检索结果不包含该文档** |
| R18 | 修改自己密码 | — | endpoint 用 current user id | — |
| R19 | 登录 `/auth/login` | 匿名 | 无守护 | — |

### 角色覆盖矩阵

| 操作 | SUPER_ADMIN | DEPT_ADMIN | USER |
|---|---|---|---|
| 进入 /admin | 全 12 项 | 3 项 | ❌ |
| 部门 CRUD | ✅ | ❌ | ❌ |
| 角色 CRUD | ✅ | ❌ | ❌ |
| 用户 CRUD | 跨部门 | 本部门（不含 SUPER_ADMIN 角色） | ❌ |
| KB CRUD | 跨部门 | 本部门 | ❌ |
| 文档 CRUD + security_level | 跨部门 | 本部门 | ❌ |
| Chunk CRUD | ✅ | ❌ | ❌ |
| 问答检索 | level ≤ 3 | level ≤ max | level ≤ max |

---

## 十、测试与验收策略

### 10.1 测试基础设施态度

PR3 **继续跳过 TDD**，原因：
- P3（测试基础设施 PR）尚未落地
- 当前无 MockMvc / Sa-Token mock / Mockito 栈
- 强行写测试会阻塞 PR3 主价值
- 所有测试点记录到下方 §10.5 回填清单，等 P3 完成后补

### 10.2 Phase 0 后端验证手段

- **手工 curl matrix**：`docs/dev/pr3-curl-matrix.http`，覆盖 §九 所有有真实 HTTP 边界的规则
- 用不同角色的 token 打相同 endpoint
- 期望：授权内 200，授权外 403
- 文件作为可重放的验证脚本（JetBrains / VSCode HTTP Client 格式）

### 10.3 重建协议（验收前必做）

**Step 1-3：数据清理 + 前端 rebuild**（bash / Git Bash / WSL）
```bash
# 1. 停后端（在运行 mvn 的窗口按 Ctrl+C）

# 2. 清数据
docker exec postgres psql -U postgres -c "DROP DATABASE ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/fixture_pr3_demo.sql
curl -X DELETE "http://localhost:9200/_all"
docker exec redis redis-cli FLUSHDB

# 3. 前端 rebuild
cd frontend && npm run build  # 或 npm run dev（dev 服务器）
```

**Step 4：启后端**（Windows PowerShell —— `$env:` 是 PowerShell 语法，不要在 bash 里执行）
```powershell
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'
mvn -pl bootstrap spring-boot:run
```

**Step 5：按 `pr3-demo-walkthrough.md` 走 12 条 checklist**

### 10.4 12 条验收 checklist

1. admin 登录 → /admin → 见 **12 项** 完整菜单（11 项原有 + 新增"部门管理"）+ roleLabel 显示"超级管理员"
2. admin 在 /admin/departments 创建 "研发部"、"法务部"
3. admin 在 /admin/users 创建 alice（dept=研发部）、bob（dept=法务部）、carol（dept=研发部）——用户创建表单只选择部门和角色，**max_security_level 不是用户字段**，它由角色派生
4. admin 在 /admin/roles 可见 role_type 下拉和 max_security_level 下拉；创建 "研发部管理员"（DEPT_ADMIN / max=3）、"法务部管理员"（DEPT_ADMIN / max=3）、"普通研发员"（USER / max=0）
5. admin 把"研发部管理员"挂给 alice；"法务部管理员"挂给 bob；**"普通研发员"挂给 carol（这一步决定 carol 的 max=0）**
6. admin 退出 → alice 登录 → 只见 **3 项** 菜单 + roleLabel 显示"研发部管理员"
7. alice 的 /admin/knowledge 只见本部门 KB；建新 KB 时 dept_id 锁定研发部
8. alice 的 /admin/users 只见研发部用户（alice 自己 + carol），能增删改；bob 不可见
9. alice 上传 "机密架构.md" → security_level 下拉设 2 → 提交成功
10. carol 登录 chat → 问涉及 "机密架构.md" 的问题 → **检索结果不包含该文档**（结构性断言）
11. alice 退出，bob 登录 → 只见法务部 KB 和用户；研发部任何资源不可见；raw curl 尝试改研发 KB → 403
12. 代码 grep 清零：`"admin".equals`、`LoginUser.role`、`authStore.user.role`、`user.role === "admin"`

### 10.5 未来测试回填清单（交给 P3）

- `KbAccessServiceTests` — SUPER_ADMIN / DEPT_ADMIN / USER 三分支 × 所有 check 方法
- `UserProfileLoaderTests` — JOIN 结果正确性 + MAX 聚合正确性
- `SysDeptServiceTests` — GLOBAL 保护 + 挂载计数校验
- `KnowledgeBaseControllerTests` — @WebMvcTest checkManageAccess 路径
- `KnowledgeDocumentControllerTests` — @WebMvcTest 5 写接口 + security-level endpoint
- `UserControllerTests` — @WebMvcTest 4 写接口 DEPT_ADMIN 过滤
- 前端 `permissions.test.ts` — `getPermissions()` 纯函数的所有分支组合
- 前端 `RoleListPage.test.tsx` / `UserListPage.test.tsx` — 角色过滤 + 部门锁定

---

## 十一、风险与未决项

### 11.1 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | `UserProfileLoader` 每请求 JOIN，高并发下是热点 | 潜在性能回退 | Phase 0 先不加缓存；用日志观察；瓶颈时做 `UserProfileCache` 独立组件 |
| R2 | Fixture 只能 seed PostgreSQL 数据，无法 seed 向量数据 | 验收步 10 需手工上传文档 | `pr3-demo-walkthrough.md` 写清楚手动步骤 |
| R3 | PR3 无自动化测试，回归靠手工 | 未来 slice 可能破坏已过 checklist | 每次 slice 完成后跑完整 12 条 + bypass 矩阵 |
| R4 | `updateSecurityLevel` 从现有 `update` 抽出可能触及其他调用点 | 抽取导致原有流程退化 | 抽出前 grep 所有调用点；抽出后 rebuild + smoke test |
| R5 | `KbAccessServiceImpl` Redis cache 失效面窄 | dept 改名、角色 role_type 变更后 cache 可能短暂陈旧 | PR3 已把 DEPT_ADMIN 的 `getAccessibleKbIds` 改为**不走缓存**（每次 JOIN，见 §5.1），规避 KB create/delete/dept 变更的失效面问题；USER 缓存失效触发保持 PR1 原状；TTL 30min 兜底 |
| R6 | SaToken `getRoleList` 已在 PR1 切到 roleTypes；PR3 风险是后续重构误伤 `@SaCheckRole("SUPER_ADMIN")` 的匹配行为 | 现有 8 处 `@SaCheckRole` 可能回归失效 | Phase 0 完成后重点测试所有 `@SaCheckRole` 标注的接口仍只 SUPER_ADMIN 可通过 |
| R7 | `LoginUser.role` 字段删除是 framework 模块 breaking change | 下游引用需要全量切 | Phase 0 一次性改完所有引用，编译器兜底；Decision 3-F 铁律配合 |
| R8 | Fixture SQL 重跑时 PK 冲突 | 本地重建流程卡住 | SQL 用 `DELETE FROM ... WHERE` 基于业务键清理 + 固定 ID 插入，可重复执行 |

### 11.2 Out-of-Scope / Follow-up

| # | 未决项 | 建议归属 |
|---|---|---|
| 1 | `t_user.role` 列最终删除 | Post-PR3 cleanup |
| 2 | `UserProfileCache` Redis 层 | 视性能监控结果 |
| 3 | 细粒度 security_level 规则 | 业务需求出现时再做 |
| 4 | RocketMQ `SecurityLevelRefreshEvent` DLQ 监控 | P4 任务 |
| 5 | 测试基础设施 + PR0/PR1/PR3 测试回填 | P3 任务 |
| 6 | Windows surefire argline 修复 | P2 任务 |
| 7 | 部门层级树（parent_id） | 暂不做 |
| 8 | GLOBAL 部门下的 DEPT_ADMIN | 暂不允许 |
| 9 | 用户调岗（修改 dept_id）的专用 endpoint | 本 PR 内由 `checkUserManageAccess` 隐式禁止；未来可拆独立 endpoint |

---

## 十二、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-04-12 | v1.0 | brainstorming session 初稿，8 个议题用户全部过审 |
