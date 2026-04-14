# RBAC 权限漏洞修复（5 个漏洞 / 13 个任务）

**日期**：2026-04-13
**分支**：`feature/rbac-pr3-frontend-demo`
**状态**：已完成
**设计文档**：`docs/dev/design/2026-04-13-rbac-permission-fixes-design.md`
**实施计划**：`docs/superpowers/plans/2026-04-13-rbac-permission-fixes.md`

---

## 一、修复的漏洞

| 编号 | 严重度 | 漏洞描述 | 根因 |
|------|--------|---------|------|
| **A** | HIGH | `security_level` 检索过滤使用全局 `MAX(所有角色)`，跨 KB 泄漏高密级文档 | `SearchContext.maxSecurityLevel` 是单一标量，不区分 KB |
| **B** | MEDIUM | `GET /role`、`GET /user/{id}/roles`、`GET /sys-dept` 被 SUPER_ADMIN 锁死，DEPT_ADMIN 弹窗数据加载失败 | 类级 `@SaCheckRole("SUPER_ADMIN")` 过粗 |
| **C** | MEDIUM | 跨部门 KB 共享无管理入口 | 仅有 role-centric API（SUPER_ADMIN 独占），缺 KB-centric API |
| **D** | MEDIUM | DEPT_ADMIN 可分配 DEPT_ADMIN / SUPER_ADMIN 角色或超自身 ceiling 的角色 | `checkCreateUserAccess` / `checkAssignRolesAccess` 只拦 SUPER_ADMIN 类型 |
| **F** | MEDIUM | DEPT_ADMIN 编辑用户时可将 deptId 改到其他部门 | `UserServiceImpl.update()` 无 deptId 变更校验 |

---

## 二、架构决策

### 2.1 per-KB security_level 方案（漏洞 A）

**问题**：`LoginUser.maxSecurityLevel` = `MAX(所有角色.max_security_level)`，检索时所有 KB 共用同一个值。用户有 FICC(level=3) + OPS(level=1) 时，访问 OPS 也能看到 level=3 的文档。

**方案**：
1. `t_role_kb_relation` 新增 `max_security_level SMALLINT NOT NULL DEFAULT 0`
2. `SearchContext.maxSecurityLevel` (Integer) → `kbSecurityLevelResolver` (Function<String, Integer>)
3. `KbAccessService.getMaxSecurityLevelForKb(userId, kbId)` 按 KB 解析
4. 三条检索路径（单 KB 定向 / VectorGlobal / IntentDirected）各自按 kbId 调用 resolver

**语义重定义**：`LoginUser.maxSecurityLevel` 从"检索过滤值"变为"角色能力天花板（展示 + 绑定校验用）"，不再用于检索。

### 2.2 KB-centric vs Role-centric 共享 API（漏洞 C）

保留现有 role-centric API（`PUT /role/{roleId}/knowledge-bases`，SUPER_ADMIN 独占），新增 KB-centric API：
- `GET /knowledge-base/{kb-id}/role-bindings`
- `PUT /knowledge-base/{kb-id}/role-bindings`

作用域限定在单个 KB，DEPT_ADMIN 只能操作本部门 KB。

### 2.3 缓存策略

| 缓存 | Key 格式 | TTL | 驱逐时机 |
|------|---------|-----|---------|
| KB 访问权限 | `kb_access:{userId}` | 30min | 角色/绑定变更时 |
| Per-KB 安全等级 | `kb_security_level:{userId}` | 30min | 同上，统一驱逐 |

`setKbRoleBindings()` 的缓存驱逐：**删除旧绑定前**先收集旧 role 涉及的用户，与新绑定用户合并后统一驱逐。

---

## 三、实施记录

### Phase 1：安全修复（Task 1-7）

| Commit | Task | 内容 |
|--------|------|------|
| `80ac1fd` | 1 | Schema + Entity + DTO：`max_security_level` 列（schema_pg + full_schema_pg + RoleKbRelationDO + RoleKbBindingRequest） |
| `e8fb0c0` | 2 | `RoleServiceImpl.setRoleKnowledgeBases` 写入 maxSecurityLevel（默认取角色天花板，上界 clamp） |
| `80470ae` | 3 | `KbAccessService.getMaxSecurityLevelForKb` + Redis Hash 缓存 + `evictCache` 同步清理 |
| `0d46550` | 4 | `SearchContext.maxSecurityLevel` → `kbSecurityLevelResolver`（Function）+ `buildMetadataFilters(ctx, kbId)` 新签名 |
| `e1cb306` | 5 | 三条检索路径改造：VectorGlobal（per-collection filter）/ IntentDirected（per-intent filter）/ 单 KB（直传 kbId） |
| `ddb36bf` | 6 | 前端 KB 绑定弹窗增加 Level 0-3 下拉 |
| — | 7 | `mvn clean install -DskipTests` 全模块 BUILD SUCCESS |

### Phase 2：功能补全（Task 8-13）

| Commit | Task | 内容 |
|--------|------|------|
| `6aa9e6c` | 8 | `GET /role`、`GET /user/{id}/roles` 改 `checkAnyAdminAccess()`；`SysDeptController` 类级→方法级注解（读 AnyAdmin / 写 SUPER_ADMIN） |
| `addbaa1` | 9 | `validateRoleAssignment()` 封堵 DEPT_ADMIN 分配 SUPER_ADMIN / DEPT_ADMIN / 超 ceiling 角色；deptId 变更 SUPER_ADMIN 独占 |
| `8497184` | 10 | KB-centric API：`GET/PUT /knowledge-base/{kb-id}/role-bindings` + `checkKbRoleBindingAccess` + service impl（含缓存驱逐） |
| `1af9f5a` | 11 | `permissions.ts`：DEPT_VISIBLE 加 "roles" + `canAssignRole` 排除 DEPT_ADMIN；`router.tsx` roles 路由改 RequireMenuAccess |
| `bbefec2` | 12 | RoleListPage DEPT_ADMIN 只读（隐藏增删改按钮，跳过 KB count 加载）；UserListPage deptId 已锁定（无需改动） |
| `7a59fed` | 13 | `KbSharingTab.tsx` 组件 + `knowledgeService.ts` API 函数 + KnowledgeDocumentsPage 集成 |

### 修复

| Commit | 内容 |
|--------|------|
| `2f222f0` | `KbSharingTab` 权限不足时静默隐藏（`noAccess=true` → `return null`），不弹 error toast |

---

## 四、关键踩坑记录

| 踩坑 | 教训 |
|------|------|
| `VectorGlobalSearchChannel.getAllKBCollections` 返回 `List<String>`（collection name），丢失 kbId 信息 | 改为返回 `List<KnowledgeBaseDO>`，per-collection 构建 filter 时需要 kbId |
| `IntentParallelRetriever` 接收共享的 `List<MetadataFilter>` | 每个 intent 可能关联不同 KB，改为传 `SearchContext` 让每个 IntentTask 独立 resolve |
| `full_schema_pg.sql` 的 COMMENT 不在 CREATE TABLE 附近 | pg_dump 风格把 COMMENT 聚集在单独的 block 里，新增列 COMMENT 要找到对应 table 的 COMMENT 区域追加 |
| DEPT_ADMIN 浏览其他部门 KB 文档页时 `KbSharingTab` 报错 | `isAnyAdmin` 渲染但后端 `checkKbRoleBindingAccess` 拒绝。前端权限组件必须 catch backend rejection 并 gracefully hide |
| `@Transactional` 未指定 `rollbackFor` | 默认只对 unchecked exception 回滚，`ClientException` 是 checked → 不回滚。加 `rollbackFor = Exception.class` |

---

## 五、文件变更汇总

### 后端（14 文件）

| 文件 | 变更类型 |
|------|---------|
| `resources/database/schema_pg.sql` | modify：t_role_kb_relation 加列 |
| `resources/database/full_schema_pg.sql` | modify：同上 |
| `bootstrap/.../user/dao/entity/RoleKbRelationDO.java` | modify：加 maxSecurityLevel 字段 |
| `bootstrap/.../user/controller/RoleController.java` | modify：DTO 加字段 + GET endpoints 开放 |
| `bootstrap/.../user/controller/SysDeptController.java` | modify：类级→方法级注解 |
| `bootstrap/.../user/service/KbAccessService.java` | modify：+4 方法 |
| `bootstrap/.../user/service/impl/KbAccessServiceImpl.java` | modify：+4 实现 |
| `bootstrap/.../user/service/RoleService.java` | modify：+2 方法 |
| `bootstrap/.../user/service/impl/RoleServiceImpl.java` | modify：+2 实现 + 改造读写 |
| `bootstrap/.../user/service/impl/UserServiceImpl.java` | modify：deptId 变更守卫 |
| `bootstrap/.../knowledge/controller/KnowledgeBaseController.java` | modify：+2 endpoints + 2 inner DTOs |
| `bootstrap/.../rag/core/retrieve/channel/SearchContext.java` | modify：maxSecurityLevel → resolver |
| `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java` | modify：buildMetadataFilters(ctx, kbId) |
| `bootstrap/.../rag/core/retrieve/channel/VectorGlobalSearchChannel.java` | modify：per-KB filter |
| `bootstrap/.../rag/core/retrieve/channel/IntentDirectedSearchChannel.java` | modify：传 context |
| `bootstrap/.../rag/core/retrieve/channel/strategy/IntentParallelRetriever.java` | modify：per-intent filter |

### 前端（7 文件）

| 文件 | 变更类型 |
|------|---------|
| `frontend/src/services/roleService.ts` | modify：RoleKbBinding 加 maxSecurityLevel |
| `frontend/src/services/knowledgeService.ts` | modify：+2 API 函数 + 2 interface |
| `frontend/src/utils/permissions.ts` | modify：DEPT_VISIBLE 加 roles + canAssignRole 加 DEPT_ADMIN 排除 |
| `frontend/src/router.tsx` | modify：roles 路由 RequireSuperAdmin → RequireMenuAccess |
| `frontend/src/pages/admin/roles/RoleListPage.tsx` | modify：KB 绑定弹窗 level 下拉 + DEPT_ADMIN 只读 |
| `frontend/src/pages/admin/knowledge/KbSharingTab.tsx` | **create**：KB 共享管理组件 |
| `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx` | modify：集成 KbSharingTab |

---

## 六、CLAUDE.md 更新

| 文件 | 新增条目 |
|------|---------|
| `CLAUDE.md`（root） | +5：full_schema_pg COMMENT 格式 / per-KB security_level / KB sharing API / DEPT_ADMIN 隐式 MANAGE / 前端权限组件 graceful hide |
| `frontend/CLAUDE.md` | +2：DEPT_ADMIN 菜单数 3→4 / permission-gated API 模式 |
