# PR1 — RBAC 三层角色 + 文档 security_level 数据层

**日期**：2026-04-11
**分支**：`feature/rbac-security-level-pr1`（从 `main` 起，19 commits）
**状态**：已完成，待 merge 到 main

---

## 一、目标

在 PR0（热修复 + `@SaCheckRole("SUPER_ADMIN")` 替换）的基础上，完成 RBAC 三层权限体系的**数据层 + 检索链路**改造：
- 三级角色体系：`SUPER_ADMIN`（全局超管）/ `DEPT_ADMIN`（部门管理员）/ `USER`（普通用户）
- 部门归属：`sys_dept` 表 + `t_user.dept_id` + `t_knowledge_base.dept_id`
- 文档安全等级：`t_knowledge_document.security_level`（0-3），检索时通过 OpenSearch metadata filter 过滤
- 角色级密级上限：`t_role.max_security_level`，多角色取 MAX
- security_level 修改通过 RocketMQ `SecurityLevelRefreshEvent` 异步刷新 OpenSearch chunk metadata

---

## 二、关键技术决策

| 编号 | 决策 | 选择 | 理由 |
|------|------|------|------|
| 1-A | Chunk controller 权限 | 类级 `@SaCheckRole("SUPER_ADMIN")` 保持不变 | chunk 是后台调试级操作，DEPT_ADMIN 通过重新触发文档分块间接影响 |
| 2-A | 角色全局化 | `t_role` 不加 `dept_id`，跨部门共享 | SUPER_ADMIN 独占角色 CRUD，DEPT_ADMIN 从预设角色中选 |
| — | MetadataFilter | 类型化 `MetadataFilter` record + `FilterOp` 枚举 | 替代 `Map<String, Object>` 的弱类型方案 |
| — | security_level 系统路径 | `resolveMaxSecurityLevel` 返回 `null` 而非 3 | 系统态不限制 |
| — | 多角色密级合并 | 取 MAX（任一角色允许就允许） | 用户直觉一致 |

---

## 三、Commit 日志（19 commits）

### Phase 1：Schema + Framework 层

| Commit | 内容 |
|--------|------|
| `c1f2a81` | DDL：`sys_dept` 表 + `t_role` 加 `role_type/max_security_level` + `t_knowledge_document` 加 `security_level` + `t_knowledge_base` 加 `dept_id` + `t_user` 加 `dept_id` |
| `a63c413` | Framework 层：`RoleType` 枚举（SUPER_ADMIN/DEPT_ADMIN/USER）+ `Permission` 枚举（READ/WRITE/MANAGE） |
| `106a19c` | 引入类型化 `MetadataFilter` record，替代弱类型 Map |
| `b3dd402` | `LoginUser` 扩充 `deptId / roleTypes / maxSecurityLevel`（保留 legacy `role` 字段 @Deprecated） |
| `ef669c7` | 已有 DO 实体加 RBAC 字段：`RoleDO.roleType/maxSecurityLevel`、`KnowledgeBaseDO.deptId`、`UserDO.deptId` 等 |
| `99a3542` | 新增 `SysDeptDO` + `SysDeptMapper` |
| `d7dd3ff` | `RoleMapper` 加 `selectRolesByUserId` / `selectRoleTypesByUserId` |

### Phase 2：Interceptor + Service 层

| Commit | 内容 |
|--------|------|
| `a5e1810` | `UserContextInterceptor` 在请求进入时 JOIN 装载多角色到 `LoginUser` |
| `ab82c1a` | `KbAccessService` 重构：`isSuperAdmin()` / `checkAccess()` / `checkManageAccess()` / `getAccessibleKbIds(userId, Permission)`；SUPER_ADMIN bypass 下沉到 service 内部 |
| `c67a61a` | 全局替换 Sa-Token 角色字符串：`"admin"` → `"SUPER_ADMIN"`（16 处） |

### Phase 3：向量存储 + 检索链路

| Commit | 内容 |
|--------|------|
| `0f21d9b` | OpenSearch mapping 声明 `security_level` 字段 + `IndexerNode` 写入 |
| `a9a94b2` | `VectorStoreService.updateChunksMetadata`（OS 实现，按 doc_id 批量更新 metadata） |
| `45b45f1` | 检索链路激活 `metadataFilters` pipeline：`security_level <= user.maxSecurityLevel` |

### Phase 4：业务层

| Commit | 内容 |
|--------|------|
| `e3774be` | 文档上传 API 接受 `securityLevel` 参数 |
| `df77087` | security_level 修改走 RocketMQ 事务消息异步刷新 OpenSearch（`SecurityLevelRefreshEvent` + `KnowledgeDocumentSecurityLevelRefreshConsumer`） |
| `b5d55ff` | KB 创建接受 `deptId`；DEPT_ADMIN 自动锁定到 self.dept |

### Phase 5：种子数据 + 烟测

| Commit | 内容 |
|--------|------|
| `cdb3f8f` | `init_data_pg.sql` seed：GLOBAL 部门 + SUPER_ADMIN/USER 角色 + admin 用户-角色关联 |
| `8c24416` | 修正 admin 密码为 123456（之前是加密的旧密码） |
| `ce60cff` | 烟测通过：dev rebuild + security_level filter 验证 |

---

## 四、遗留问题（交给后续 PR）

| 编号 | 问题 | 归属 |
|------|------|------|
| 1 | 4 处 `"admin".equals(UserContext.getRole())` 散点未清理 | PR3 |
| 2 | Legacy `LoginUser.role` 字段未删除（标记 @Deprecated） | PR3 |
| 3 | 前端无任何 RBAC 配套（用户页/角色页/部门页均无新字段） | PR3 |
| 4 | DEPT_ADMIN 无法进入管理后台 | PR3 |
| 5 | 文档写接口仍走 `checkAccess()`（READ 权限），应走 `checkManageAccess()` | PR3 |
| 6 | `SysDeptService/Controller` 未建（仅有 DO + Mapper） | PR3 |
| 7 | RocketMQ DLQ 监控缺失 | P4 |
| 8 | Windows surefire forked VM crash | P2 |
| 9 | 测试基础设施（MockMvc / Sa-Token mock / Mockito）未搭建 | P3 |

---

## 五、烟测验证记录

**日期**：2026-04-11
**基线**：schema_pg.sql + init_data_pg.sql 完全重建

| 步骤 | 结果 |
|------|------|
| admin / 123456 登录 | PASS |
| 创建 KB（dept_id = GLOBAL） | PASS |
| 上传文档（security_level = 0） | PASS |
| 分块流程跑完 | PASS |
| 问答返回正常 | PASS |
| security_level = 2 的文档，max_security_level = 0 的用户检索不到 | **PASS（核心 E2E 断言）** |

---

## 六、关键文件速查

| 文件 | 用途 |
|------|------|
| `resources/database/schema_pg.sql` | 完整 DDL（含 sys_dept + 所有新字段） |
| `resources/database/init_data_pg.sql` | 种子数据（GLOBAL 部门 + 2 角色 + admin 关联） |
| `framework/.../context/RoleType.java` | 角色类型枚举 |
| `framework/.../context/Permission.java` | 权限级别枚举 |
| `framework/.../context/LoginUser.java` | 扩充后的用户上下文快照 |
| `bootstrap/.../user/service/KbAccessService.java` | RBAC 权限校验核心接口 |
| `bootstrap/.../user/config/UserContextInterceptor.java` | 请求进入时 JOIN 装载 LoginUser |
| `bootstrap/.../user/config/SaTokenStpInterfaceImpl.java` | getRoleList 返回 roleTypes |
| `bootstrap/.../knowledge/mq/SecurityLevelRefreshEvent.java` | RocketMQ 事件定义 |
| `docs/dev/design/rbac-and-security-level-implementation.md` | 完整设计文档（12 章） |
