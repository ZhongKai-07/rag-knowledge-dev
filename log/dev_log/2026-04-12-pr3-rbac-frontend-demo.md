# PR3 — RBAC 前端 Demo 闭环 + Legacy 清理

**日期**：2026-04-12 ~ 进行中
**分支**：`feature/rbac-pr3-frontend-demo`（从 `feature/rbac-security-level-pr1` 起）
**状态**：设计完成 + 28 任务 plan 已就绪 + 实施进行中

---

## 一、目标

把 PR1 的 RBAC 数据层能力在前端完整闭环，让用户能通过 UI 完成：
1. **跨部门隔离演示**：两个部门 + 两个 DEPT_ADMIN + 互不可见互不可改
2. **Security_level 隔离演示**：max=0 的用户检索不到 level=2 的文档
3. **DEPT_ADMIN 自管后台**：可管理本部门 KB / 用户 / 文档
4. **彻底清除 Legacy**：`LoginUser.role` 字段删除、`"admin".equals` 清零、前端 `user.role === "admin"` 清零

---

## 二、Brainstorming 阶段（2026-04-12，1 个 session）

### 2.1 议题与决策

| # | 议题 | 决策 | 编号 |
|---|------|------|------|
| 1 | 分支策略 | 从 PR1 分支起（PR1 未 merge 但 PR3 完全依赖其代码） | — |
| 2 | 部门管理范围 | 完整 CRUD，`sys_dept` 表 PR1 已建好（SysDeptDO + SysDeptMapper），PR3 补 Service/Controller/前端页 | — |
| 3 | Chunk controller 降级 | 保持类级 `@SaCheckRole("SUPER_ADMIN")`（尊重设计决策 1-A） | — |
| 4 | Demo 完整性边界 | 全选：基础字段 + 跨部门隔离 + security_level 隔离 + legacy 清除 + DEPT_ADMIN 后台 | — |
| 5 | DEPT_ADMIN 可见菜单 | 矩阵 A：Dashboard / 知识库管理 / 用户管理（3 项），其他 9 项 SUPER_ADMIN 独占 | 3-A |
| 6 | 实施方式 | 方案 C：后端地基 → 前端垂直切片 | — |
| 7 | 登录后 landing | 统一 `/spaces`，不按角色跳转 | 3-B |
| 8 | permissions.ts 架构 | 双层 API：`getPermissions(user)` 纯函数 + `usePermissions()` hook | 3-C |
| 9 | RequireMenuAccess 失败策略 | Navigate("/admin/dashboard") + toast，不做 403 页 | 3-D |
| 10 | Commit hygiene | 不加 tombstone 临时兼容，后端 + 前端打包 commit | 3-F |
| 11 | UserProfileLoader 缓存 | Phase 0 不做 Redis 缓存，每请求 JOIN | 3-G |
| 12 | KB 创建权限 | `resolveCreateKbDeptId(requestedDeptId)` 校验 + 解析合一 | 3-H |
| 13 | security_level 入口 | 专用 endpoint `PUT /docs/{id}/security-level`，从通用 update DTO 删掉该字段 | 3-I |
| 14 | POST /users 原子化 | 一次请求写 user + user_role，前端不再两步调用 | 3-J |
| 15 | 部门硬保护 | GLOBAL 不可删不可改名；删除前校验 user/KB 挂载计数 | 3-K |
| 16 | 验收断言 | 基于结构性存在/缺失，不耦合响应文案 | 3-L |
| 17 | Last SUPER_ADMIN 保护 | post-mutation 模拟器 `simulateActiveSuperAdminCountAfter(intent) < 1` 拒绝 | 3-M |

### 2.2 Review 轮次

| 轮次 | Finding 数量 | 关键发现 |
|------|-------------|---------|
| Round 1 | 6 P1 + 2 Q | 菜单数 11→12 / cache 失效面漏 KB create / 匿名上下文未封 / Last SUPER_ADMIN 缺失 / checklist carol max 归属错误 / 重建协议混用 bash+PowerShell / User.id→userId 对齐 / KB create wording |
| Round 2 | 1 P1 + 1 P2 | delete-role 多超管归零漏洞（pre-mutation count==1 不够→改 post-mutation 模拟） / 前端无全局 activeSuperAdminCount 数据源（改为乐观放行+后端 toast） |
| Round 3 | 1 P2 | simulator 实现说明误引 `t_role_kb_relation`（KB 绑定表与超管身份无关） |

### 2.3 产出文件

| 文件 | 行数 | 用途 |
|------|------|------|
| `docs/superpowers/specs/2026-04-12-pr3-rbac-frontend-demo-design.md` | ~1060 | 设计文档（12 章、13 个决策、19 条权限规则、12 条验收 checklist） |

---

## 三、Writing-Plans 阶段

### 3.1 Plan 结构

28 个任务分两阶段：

**Phase 0（后端地基 + legacy 清理 bundle，16 个任务）：**
- 0.1-0.3：SysDept DTOs/Service/Controller
- 0.4-0.5：UserProfileLoader + LoginVO/CurrentUserVO 扩充
- 0.6-0.8：KbAccessService 11 个新方法（用户管理 / KB 文档管理 / DEPT_ADMIN-aware 列表+cache bypass）
- 0.9：Last SUPER_ADMIN post-mutation 模拟器
- 0.10-0.11：KnowledgeDocument/KnowledgeBase controller 授权修复
- 0.12-0.13：UserController/RoleService 降级 + 原子创建 + Last SUPER_ADMIN pre-check
- 0.14：3 处 `"admin".equals` 清理
- **0.15**：BUNDLE commit（LoginUser.role 删除 + 前端 permissions.ts + guards + AdminLayout + authStore + User type）
- 0.16：集成烟测

**Slices（前端垂直切片，12 个任务）：**
- S1：部门页（greenfield）
- S2.1-S2.2：用户页改造
- S3.1-S3.2：角色页升级
- S4：KB 创建 dept_id
- S5.1-S5.2：文档 security_level 全链路
- S8.1-S8.3：fixture SQL + curl 矩阵 + walkthrough
- S9：12 步验收

### 3.2 Plan Review

| Finding | 修复 |
|---------|------|
| P1：walkthrough 漂移（fixture 代替 UI CRUD） | 拆为两种验收模式：Mode A（UI walkthrough，不加 fixture）/ Mode B（curl 矩阵，加 fixture） |
| P1：curl 协议漂移（Bearer + 403 vs raw token + code!=0） | 全部修正为 raw token header + body code 字段断言 |
| P1：fixture SQL 不可执行（缺 id PK / 错列名） | 补全显式 id + 删除 description 列 + 加 embedding_model/created_by |
| P2：Sidebar 路径错误 | `components/chat/Sidebar.tsx` → `components/layout/Sidebar.tsx` |
| P2：admin smoke 预期错误 | init_data_pg.sql 已 seed dept=GLOBAL + SUPER_ADMIN/max=3，非空白用户 |
| P2：S5.2 详情页未锚定 | 锚定到 `KnowledgeChunksPage.tsx`（路由 knowledge/:kbId/docs/:docId） |

### 3.3 产出文件

| 文件 | 行数 | 用途 |
|------|------|------|
| `docs/superpowers/plans/2026-04-12-pr3-rbac-frontend-demo.md` | ~3870 | 28 任务 plan（每任务含完整代码块 + 编译检查 + commit 命令） |

---

## 四、实施阶段（Commit 日志）

### Phase 0：后端地基

| Commit | Task | 内容 |
|--------|------|------|
| `b24936a` | 0.1 | SysDept DTOs + VO |
| `b9514c0` | 0.2 | SysDeptService（GLOBAL 保护 + 引用计数删除守卫） |
| `591974b` | 0.3 | SysDeptController（类级 SUPER_ADMIN） |
| `6dcea5a` | 0.4 | UserProfileLoader + LoadedUserProfile（单次 JOIN，无缓存） |
| `38b2d29` | 0.5 | LoginVO/CurrentUserVO additive 扩充 + 三处 loader 接入 |
| `67864d1` | 0.6 | KbAccessService 用户管理授权（checkCreateUserAccess / checkUserManageAccess / checkAssignRolesAccess / isDeptAdmin） |
| `3328aca` | 0.7 | KbAccessService KB/文档管理授权（resolveCreateKbDeptId / checkDocManageAccess / checkDocSecurityLevelAccess） |
| `af23933` | 0.8 | getAccessibleKbIds v2（DEPT_ADMIN 同部门 KB 合并 + bypass cache） + checkAccess v2 |
| `d9e5906` | 0.9 | SuperAdminMutationIntent sealed interface + simulateActiveSuperAdminCountAfter 模拟器 |
| `f8801fd` | 0.10 | KnowledgeDocumentController 5 写接口 READ→MANAGE 修复 + security-level 专用 endpoint + updateSecurityLevel 方法抽出 |
| `32db219` | 0.11 | KnowledgeBaseController create→resolveCreateKbDeptId / update+delete→checkManageAccess / list→isSuperAdmin() |
| `0a00a54` | 0.12 | UserController 4 写接口降级 + atomic POST /users + delete Last-SUPER_ADMIN pre-check |
| `3bc6402` | 0.13 | RoleServiceImpl 3 处 Last-SUPER_ADMIN pre-check + setUserRoles→checkAssignRolesAccess |
| `c4602dc` | 0.14 | 清理 RAGChatServiceImpl / SpacesController / KnowledgeDocumentController.search 的 "admin".equals |
| `0cb56b0` | **0.15** | **BUNDLE**：LoginUser.role 删除 + permissions.ts + router guards + AdminLayout sidebar filter + authStore 迁移 + User type 迁移 |

### Slices：前端垂直切片

| Commit | Task | 内容 |
|--------|------|------|
| `ace5d5f` | S1 | 部门管理页（greenfield）：sysDeptService.ts + DepartmentListPage.tsx + 路由 + 菜单项 |
| `442b347` | S2.1 | 用户列表：部门 chip / roleTypes chips / maxSecurityLevel badge |
| `55218c3` | S2.2 | 用户创建/编辑 Dialog：部门锁定 + 角色过滤 + 原子 createUser |
| `a0bf0e5` | S3.1 | 角色列表 + 编辑 Dialog：roleType 下拉 + maxSecurityLevel 下拉 |
| `58f463b` | S3.2 | 角色-KB 绑定 Dialog：permission 下拉（READ/WRITE/MANAGE） |
| `f155b89` | S4 | KB 创建 Dialog dept_id 字段（DEPT_ADMIN 锁定） |
| `d7fd266` | S5.1 | 文档上传 security_level + 列表密级列 |
| `91bf827` | S5.2 | 文档详情（KnowledgeChunksPage）密级 badge + 修改按钮 |
| `3da50c0` | S8.1 | fixture_pr3_demo.sql（幂等，业务键清理） |
| `4a20076` | S8.2 | pr3-curl-matrix.http（19 条权限规则 bypass 套件） |
| `0e197ba` | S8.3 | pr3-demo-walkthrough.md + README 交叉引用 |
| `a787886` | S9 | 验收日志（Mode B curl 矩阵 17/18 PASS） |

### 修复

| Commit | 内容 |
|--------|------|
| `6d762ca` | 移除 `LoginUserTests` 中对已删除 `role` 字段的引用 |

---

## 五、关键踩坑记录

### 5.1 Brainstorming 阶段

| 踩坑 | 教训 |
|------|------|
| grep `t_department` / `Dept*` 找不到部门表 | 表名是 `sys_dept`，实体是 `SysDeptDO`。项目表名前缀不统一（`t_*` vs `sys_*`），搜索时用实际名称 |
| 菜单计数 11 项忘了加 departments 变 12 项 | Brainstorming 阶段新增页面后要立刻更新所有引用数字 |
| DEPT_ADMIN cache 失效面写窄了 | `getAccessibleKbIds` 的结果依赖 `kb.dept_id`，KB 创建/删除/部门变更都是 mutation 源。DEPT_ADMIN 改为 bypass cache |
| Last SUPER_ADMIN 用 pre-mutation count==1 判断 | 被 reviewer 抓到：2 个超管共享同一个 SUPER_ADMIN 角色 → count==2 放行 → 删角色后变 0。改为 post-mutation 模拟器 |
| simulator 实现说明误引 `t_role_kb_relation` | KB 绑定表决定"角色能看哪些 KB"，与"某用户是不是 SUPER_ADMIN" 无关。INNER JOIN 会漏算无 KB 绑定的超管 |

### 5.2 Plan Review 阶段

| 踩坑 | 教训 |
|------|------|
| curl 矩阵用 `Authorization: Bearer {{token}}` | Sa-Token 用 raw token，没有 Bearer 前缀。检查 `application.yaml` 的 `sa-token.token-name` 和 `api.ts` 的 header 设置 |
| curl 矩阵断言 HTTP 403/409 | `GlobalExceptionHandler` 统一包装成 `Result.failure(...)`，HTTP 状态码是 200。断言应基于 `code` 字段 |
| fixture SQL 缺 id 主键 | `t_user_role.id` 和 `t_role_kb_relation.id` 是 `VARCHAR(20) NOT NULL PRIMARY KEY`，无自动生成 |
| fixture SQL `t_knowledge_base` 有 `description` 列 | schema_pg.sql 里没有 `description` 列；`embedding_model` 和 `created_by` 是 NOT NULL |
| walkthrough 改成 fixture 预置验证 | 跳过了 UI CRUD 链路证明。拆为 Mode A（UI walkthrough 无 fixture）+ Mode B（curl 矩阵有 fixture） |
| Sidebar 路径写成 `components/chat/Sidebar.tsx` | 实际是 `components/layout/Sidebar.tsx`，前者不存在 |
| admin 初始 smoke 预期"no dept / empty roleTypes / max=0" | `init_data_pg.sql` 已 seed dept=GLOBAL + SUPER_ADMIN/max=3 |
| S5.2 详情页写"可能在 KnowledgeDocumentsPage.tsx" | 实际路由 `knowledge/:kbId/docs/:docId → KnowledgeChunksPage.tsx`，后者才是详情页 |

---

## 六、产出的 Feedback Memory（跨任务通用原则）

| Memory 文件 | 内容 |
|-------------|------|
| `feedback_permission_enforcement.md` | UI 过滤必须配对后端拦截，前端是体验、后端是授权边界 |
| `feedback_assertion_decoupling.md` | 权限验收断言基于结构性存在/缺失，不绑到"not found"等响应文案 |
| `feedback_last_admin_protection.md` | RBAC 系统必须有"最后一个最高权限用户不可删/不可降级"的系统级硬不变量 |
| `feedback_cache_invalidation_coverage.md` | 缓存 derived data 时必须枚举所有 mutation 源；失效面大时 bypass cache |

---

## 七、CLAUDE.md 更新（本次 session 产出）

| 文件 | 新增条目 |
|------|---------|
| `CLAUDE.md`（root） | +3：Sa-Token raw token 协议 / sys_dept 表名约定 / 种子数据非空白 |
| `bootstrap/CLAUDE.md` | +2：KB 表无 description 列 / 关联表显式 PK |
| `frontend/CLAUDE.md` | +2：ChunksPage 实际是详情页 / Sidebar 在 layout 目录 |

---

## 八、关键文件速查

| 文件 | 用途 |
|------|------|
| `docs/superpowers/specs/2026-04-12-pr3-rbac-frontend-demo-design.md` | PR3 设计文档（权威决策源） |
| `docs/superpowers/plans/2026-04-12-pr3-rbac-frontend-demo.md` | PR3 实施计划（28 任务） |
| `docs/dev/launch.md` | 全新环境搭建指南 |
| `docs/dev/pr3-demo-walkthrough.md` | 12 步 UI 验收脚本 |
| `docs/dev/pr3-curl-matrix.http` | 19 条权限规则 bypass 测试 |
| `resources/database/fixture_pr3_demo.sql` | curl 矩阵专用 demo 数据 |
| `frontend/src/utils/permissions.ts` | 前端权限层（getPermissions + usePermissions） |
| `frontend/src/router/guards.tsx` | 路由守卫（RequireAnyAdmin / RequireSuperAdmin / RequireMenuAccess） |
| `bootstrap/.../user/service/SuperAdminMutationIntent.java` | Last SUPER_ADMIN 模拟器输入类型 |
| `bootstrap/.../user/service/UserProfileLoader.java` | 单次 JOIN 用户身份快照加载器 |
