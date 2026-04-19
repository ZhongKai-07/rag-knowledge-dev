# 权限中心 Follow-up（P3.x）

> 日期：2026-04-20
> 目标分支：`feature/access-center-followup-p3`（建议新开，保留 `feature/access-center-redesign` 的收尾状态）
> 预计改动：~600 LOC + 1 DB migration
> 交付方式：按 P3.1 → P3.8 顺序小步切片，每片独立 PR

---

## 一、背景

2026-04-20 对 `feature/access-center-redesign`（P0 → P2.2，13 commits）做了设计-实现对照审计，确认 5 条主设计偏差 + 3 条残留 gap：

| # | 分类 | 事实 | 风险 |
|--|--|--|--|
| #1 | 功能偏差 | 后端 POST/PUT/DELETE `/role` + `/role/{id}/delete-preview` 全锁 `@SaCheckRole("SUPER_ADMIN")`；前端 `RolesTab` 对本部门 DEPT_ADMIN 展示新建/编辑/删除入口 | 🟥 UI 可点、API 必拒；设计 §4.1 / §4.3 明确 DEPT_ADMIN 应可 own-dept CRUD |
| #2 | 功能偏差 | `OrgTree.tsx:53-57` 对 DEPT_ADMIN 把所有 tab 都裁成"仅本部门"；Tab 3 丢了"跨部门只读可见" | 🟨 Tab 3 的"看市场上有哪些角色"场景完全失效 |
| #3 | 契约违反 | `SharingPage` 用通用 `/knowledge-base` 列表，DEPT_ADMIN 被显式共享的外部门 KB 会进 Tab 2 | 🟥 违反 §九 (L662) 验收断言 `response.kbs.every(kb => kb.deptId === self.deptId)` |
| #4 | 交付风险 | `upgrade_v1.5_to_v1.6.sql` 硬编码本地 snowflake ID 但文件名没带 `.<env>.` | 🟥 staging/prod 盲跑：Step 2 全部 0 行，pre-check 前无信号 |
| #5 | 代码漂移 | `AccessServiceImpl.computeTargetUserAccessibleKbIds` 复制了一套精简版 `computeRbacKbIds`，漏了 `permissionSatisfies` 过滤 | 🟩 `permission=null` 脏数据会漏进 kb-grants 返回；实际 RAG 检索仍拦截，UI 误导 |
| A | 残留 | `KbSharingSummary.tsx:47` `<Link to="/admin/sharing">` 不带 `?kb=` | 🟩 KB 上下文丢失 |
| B | 残留 | `KbSharingCard.tsx:219` 角色名 `<span>` 不可点；RolesTab 详情面板 users/kbs 列表不可点 | 🟩 §5.5 四条跨 Tab 链路只落地 1/4 |
| C | 数据隐患 | `schema_pg.sql:214` + `full_schema_pg.sql:461` `t_knowledge_base.dept_id NOT NULL DEFAULT 'GLOBAL'`，与 `SysDeptServiceImpl.GLOBAL_DEPT_ID='1'` 同字段空间不一致 | 🟨 latent：任何非 Java 写路径省略 `dept_id` 都会写入孤立值 |

本计划按风险降序 + 依赖顺序拆成 8 个切片。

---

## 二、范围

**做**：

- 修复 #1 / #3：授权 + 契约正确性
- 修复 #4：部署安全
- 修复 #C：数据完整性
- 修复 #2：恢复设计的可见性分层
- 收敛 #5：代码质量
- 补齐 A / B：UX

**不做**：

- 新的 Schema migration 拆分为 `upgrade_*.<env>.sql` 的通用流程改造 —— 只处理已交付的 v1.5→v1.6，不引入工程规范讨论。
- `SharingPage` 的 per-KB `getKbRoleBindings` N+1（来自 P0.3，后续独立 issue；若 P3.3 需要后端新增端点，可顺手合并）。
- 角色继承 / 审批流 / 审计日志 —— 设计 §十 未决项，延后。

---

## 三、切片计划

**依赖图**：

```
P3.1 migration 拆分  ←  独立；staging 发布前必做
P3.2 后端 role CRUD ←  独立；解锁 P3.4
P3.3 sharing owner filter ← 独立
P3.4 Tab 3 全部可见  ← 依赖 P3.2（避免放开可见的同时 own-dept 按钮仍死链）
P3.5 schema DEFAULT 修正 ← 独立
P3.6 kb-grants 收敛  ← 独立；纯重构
P3.7 Summary 深链补 kb= ← 独立；trivial
P3.8 跨 Tab 联动    ← 独立；UX
```

建议合并节奏：P3.1 + P3.7 一起（都 ≤ 20 行，低风险）；P3.2 → P3.4 同一 PR 或连 PR；P3.3 / P3.5 / P3.6 / P3.8 各自独立。

---

### P3.1 — migration 脚本环境拆分（高优，1-2 h）

**目标**：让 `upgrade_v1.5_to_v1.6.sql` 不再是一个可以盲跑的通用文件。

**变更**：

- 改名：`upgrade_v1.5_to_v1.6.sql` → `upgrade_v1.5_to_v1.6.local-dev.sql`（git mv，保留 blame）。
- 新增 `resources/database/upgrade_v1.5_to_v1.6.template.sql` —— 骨架 + 注释说明每行需要替换什么。
- 新增 `resources/database/README.md`（若已有就追加一节）：
  - 说明本迁移的 backfill 需要环境专属映射
  - 列出其他环境上线前的 checklist（跑 `SELECT id,name,role_type FROM t_role;` + `SELECT id,dept_code,dept_name FROM sys_dept;` 对账）
  - 指向 `upgrade_v1.6_to_v1.7.sql` 的 pre-check DO block 作为"跑漏了的兜底"
- 顶部加大字报注释，提醒"此文件仅本地开发用；其他环境必须先起一个 `.<env>.sql`"

**验收**：

- 文件名包含 `local-dev` 后缀
- 模板文件注释明确写"执行前替换每一处 `<…>`"
- README 能让一个对项目陌生的 SRE 照做不踩坑

**风险/回滚**：纯文档 + 文件重命名，无运行时影响。若已有其他部署指向老文件名，加一个 `upgrade_v1.5_to_v1.6.sql` → 新文件的 symlink 或占位注释文件告知。

**STOP & ASK**：完成后 review README 可执行性。

---

### P3.2 — 角色 CRUD 放开 DEPT_ADMIN own-dept（安全关键，半天）

**目标**：把 #1 的"UI/API 矛盾"扯平。向 DEPT_ADMIN 开放自己部门下的角色 CRUD + delete-preview，GLOBAL 与他部门仍拒。

**变更**：

**后端（`user/controller/RoleController.java`）**：

- 去掉 `POST /role`、`PUT /role/{roleId}`、`DELETE /role/{roleId}`、`GET /role/{roleId}/delete-preview` 上的 `@SaCheckRole("SUPER_ADMIN")`。
- 在各方法首行调用 `kbAccessService.checkRoleMutation(...)`：

```java
void checkRoleMutation(String targetRoleDeptId);
// SUPER: 直接通过
// DEPT_ADMIN: 要求 targetRoleDeptId == self.deptId 且 targetRoleDeptId != SysDeptServiceImpl.GLOBAL_DEPT_ID
// 其他: throw ClientException
```

- `createRole` 传 `request.getDeptId()`；`updateRole` / `deleteRole` / `getRoleDeletePreview` 先 `roleService.loadById(roleId)` 取出现角色的 `deptId` 再校验（避免 DEPT_ADMIN 改别人部门角色时路径参数绕过）。
- `updateRole`：**禁止** DEPT_ADMIN 修改 `roleType=SUPER_ADMIN` 的角色（已在 RolesTab UI 屏蔽，但后端必须也拒）。

**后端（`user/service/KbAccessService.java` + Impl）**：

- 新方法 `checkRoleMutation(String roleDeptId)` 写在 `KbAccessServiceImpl`，引用 `SysDeptServiceImpl.GLOBAL_DEPT_ID` 常量。
- 单测 `RoleControllerMutationAuthzTest`（新建）：
  - `opsadmin` 创建 `deptId=OPS_ID` 的 USER 角色 → success
  - `opsadmin` 创建 `deptId=PWM_ID` 的 USER 角色 → ClientException
  - `opsadmin` 创建 `deptId='1'`（GLOBAL）→ ClientException
  - `opsadmin` 更新 own-dept 的 SUPER_ADMIN 类型角色 → ClientException（防 priv-esc）
  - `opsadmin` 删除 own-dept USER 角色 → success；删 GLOBAL 角色 → reject
  - admin(SUPER) 任意组合均通过

**前端**：无需改动（RolesTab 已有正确的 `canCrudAtSelected` 判断；本片只是让后端接住）。

**验收**：

- 单测通过
- chrome-devtools 双视角：opsadmin 在 Operation 节点「+ 新建角色」→ 弹框填写 → 保存 → 真入库
- opsadmin 手工构造 `POST /role body.deptId="<PWM_ID>"` → 返回 `code!="0"`（CLAUDE.md Sa-Token gotcha：HTTP 200 + code 非 "0"）
- admin 视角无回归（超管任何 dept 都能写）

**风险/回滚**：仅是放开授权，不是收紧，回滚 = 恢复 `@SaCheckRole` 注解。一个 revert commit 足够。

**STOP & ASK**：单测绿灯后跑人工验收再合。

---

### P3.3 — Sharing Tab owner-scope 过滤（契约，半天）

**目标**：DEPT_ADMIN 的 Tab 2 只显示 `kb.dept_id == self.dept_id` 的 KB。满足 §九 验收断言。

**两条路径选一条**（建议 A）：

**路径 A**：`KnowledgeBaseController` 的 `page` 接口加可选 `scope=owner` 参数：

- `scope=owner`：DEPT_ADMIN 时只返回 `kb.dept_id == self.dept_id`；SUPER 时行为不变（全量）。
- `scope=access`（默认）：现行语义（RBAC 授权 ∪ 同部门），不动。
- 前端 `SharingPage` `getKnowledgeBases` 调用改为传 `scope=owner`；其他所有调用点（KnowledgeListPage、UserListPage 等）不传参 → 走默认。

**路径 B**：新增 `GET /access/kbs-owned` 专供 Tab 2 用。语义清晰但新增面更大。

**选 A 的理由**：复用 KnowledgeBaseServiceImpl.pageQuery 已有的 accessibleKbIds 注入，不引入第二条路径；SUPER 行为不变；单点改动。

**变更**：

- `KnowledgeBasePageRequest` 加 `String scope`
- `KnowledgeBaseController.page` 解析 `scope`：传 `"owner"` 且 `!isSuperAdmin()` 时，用 `kbMetadataReader.listKbIdsByDeptId(currentUser.getDeptId())` 覆盖注入到 `accessibleKbIds`
- 前端 `knowledgeService.getKnowledgeBases(current, size, name, scope?)` 加参数
- `SharingPage.tsx:50` 改 `getKnowledgeBases(1, 200, undefined, 'owner')`
- 单测：`KnowledgeBaseServicePageQueryScopeTest`：
  - SUPER + scope=owner → 全量（SUPER 兜底）
  - DEPT_ADMIN + scope=owner → 只返回自己部门 KB，即使该用户被显式共享其他部门 KB
  - DEPT_ADMIN + scope=access（默认） → 现行语义，RBAC ∪ same-dept

**验收**：

- opsadmin 视角：Tab 2 只剩 `OPS-COB知识库`（OPS dept）；`OPS-COB`(GLOBAL, 之前因 RBAC 共享过来的) 不再出现。
- admin 视角：看到全部 3 个 KB。
- RAG 检索 / KnowledgeListPage 等其他入口行为不变。

**风险/回滚**：scope 参数 optional + 默认 access，旧调用方完全兼容。回滚 = 前端 `SharingPage` 去掉 `scope='owner'` 参数。

**STOP & ASK**：双视角验收后合。

---

### P3.4 — Tab 3 对 DEPT_ADMIN 放开全部部门只读可见（UX，半天）

**目标**：DEPT_ADMIN 在 Tab 3 能看到全公司部门树，GLOBAL + 其他部门节点只读，自己部门仍可 CRUD。

**依赖**：P3.2 必须先合（否则放开可见 + own-dept 按钮 click → 403 的死链更明显）。

**变更**：

**`pages/admin/access/components/OrgTree.tsx`**：

- `Props` 新增 `restrictToOwnDept?: boolean`（默认 `true`，不破坏 Members/Departments 现行行为）
- `visibleNodes` 逻辑：
  - `scope.isSuperAdmin` → 返回所有
  - `scope.isDeptAdmin && restrictToOwnDept` → 只返回 own-dept 节点（旧行为）
  - `scope.isDeptAdmin && !restrictToOwnDept` → 返回全部节点（新行为）
- `showAllNode` 不变（仍然只有 SUPER 显示"全公司"聚合）

**`RolesTab.tsx:234`** 左栏 `<OrgTree>` 传 `restrictToOwnDept={false}`。MembersTab / DepartmentsTab 保持默认。

**前端额外**：`OrgTree` 对"非写权限节点"可以考虑打个浅色+🔒 标记（nice-to-have，不阻塞合并）。

**验收**：

- opsadmin 在 Tab 3：树显示 4 个部门节点（全局 / Operation / 固收 / PWM / IT）
- 点「Operation」→ 可 CRUD；点「固定收益部」→ 按钮全消失，列表只读展示 `FICC User` 角色 + 详情面板照常显示 usage
- 点「全局部门 GLOBAL」→ 按钮全消失，列表显示超管 + 普通用户

**风险/回滚**：纯前端，改一个 prop + 可见性判断，回滚简单。

**STOP & ASK**：opsadmin 视角的"跨部门只读可见"符合预期再合。

---

### P3.5 — 修正 `t_knowledge_base.dept_id` 默认值（数据完整性，2-3 h）

**目标**：消除 `DEFAULT 'GLOBAL'` 这个孤立字符串，让 schema 与 `SysDeptServiceImpl.GLOBAL_DEPT_ID` 对齐。

**变更**：

**Schema 两份同步**：

- `schema_pg.sql:214` `DEFAULT 'GLOBAL'` → `DEFAULT '1'`
- `full_schema_pg.sql:461` 同上

**新 migration `upgrade_v1.7_to_v1.8.sql`**：

```sql
-- 修复历史数据：任何 dept_id='GLOBAL' 的 KB 行归位到 sys_dept.id='1'
UPDATE t_knowledge_base SET dept_id = '1' WHERE dept_id = 'GLOBAL';
-- 改 DEFAULT 对存量行无影响；只防未来的裸 INSERT
ALTER TABLE t_knowledge_base ALTER COLUMN dept_id SET DEFAULT '1';
```

- 前面加 DO $$ 安全检查，打印"本次 UPDATE 影响 X 行"日志。
- 执行前必跑 `SELECT COUNT(*) FROM t_knowledge_base WHERE dept_id NOT IN (SELECT id FROM sys_dept);` 应为 0，否则说明还有其他孤立值。

**后端**：`KbAccessServiceImpl.resolveCreateKbDeptId` 保持不变（它兜底，不依赖 DEFAULT）。

**验收**：

- 本地 DB：`\d t_knowledge_base` 显示 `dept_id character varying(20) DEFAULT '1'::character varying NOT NULL`
- 跑一次 `INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, created_by) VALUES (...)` 省略 dept_id → 落 `'1'` 而非 `'GLOBAL'`
- 现有 fixture / init_data 不受影响（已经 dept_id 设值）
- 回归：`mvn test` 全绿

**风险/回滚**：DDL 改 DEFAULT 是可逆的（`ALTER COLUMN SET DEFAULT 'GLOBAL'` 回滚）；UPDATE 不可逆但目标值是正确的。

**STOP & ASK**：migration 在 dev DB 跑通后，确认影响行数再合。

---

### P3.6 — kb-grants 授权逻辑收敛（代码质量，半天）

**目标**：消除 `AccessServiceImpl.computeTargetUserAccessibleKbIds` 和 `KbAccessServiceImpl.computeRbacKbIds` 之间的"两套相似但不等价"代码。

**变更**：

**`KbAccessServiceImpl.java`**：把 `computeRbacKbIds` 抽成包可见静态 helper：

```java
static Set<String> computeRbacKbIdsFor(
        String userId, Permission minPermission,
        UserRoleMapper userRoleMapper, RoleKbRelationMapper relMapper,
        KbMetadataReader kbMetadataReader);
```

- `AccessServiceImpl.computeTargetUserAccessibleKbIds` 调用新 helper
- 对 `targetIsSuper` → `kbMetadataReader.listAllKbIds()`（跟 `getAccessibleKbIds` 超管分支一致）
- 对 `targetIsDeptAdmin` → 新 helper 结果 ∪ `kbMetadataReader.listKbIdsByDeptId(userDeptId)`

**单测**：在 `AccessServiceImplTest` 新增：

- `kbGrants_skipsRelationWithNullPermission`：插入一条 `permission=null` 的 `t_role_kb_relation` 行 → 预期结果不包含该 KB
- `kbGrants_skipsRelationWithUnknownPermission`：`permission='FOO'` → 同上
- `kbGrants_skipsSoftDeletedKb`：验证跟 `getAccessibleKbIds` 行为一致

**验收**：

- 原有 `AccessServiceImplTest` 全部绿灯
- 新增 3 条单测通过
- chrome-devtools：Tab 1 选一个用户 → KB 列表行为与之前一致（手工脏数据回归不做，代码一致性保证）

**风险/回滚**：纯 Java 重构，无行为变更（除了补齐漏过滤的边界）。

**STOP & ASK**：单测 + 手工回归通过再合。

---

### P3.7 — `KbSharingSummary` 深链带 `kbId`（UX，5 min）

**目标**：从 KB 详情页「在共享管理页修改」链接跳转到 Tab 2 时自动 scroll + highlight 该 KB。

**变更**：

- `KbSharingSummary.tsx:47-48`：`to="/admin/sharing"` → `to={`/admin/access?tab=sharing&kb=${kbId}`}`
- 可选：改文案「在权限中心 > 知识库共享 修改」，强调目的地。

**验收**：

- 进 `/admin/knowledge/<kbId>/docs` 页面 → 点「在共享管理页修改」→ 落到 `/admin/access?tab=sharing&kb=<kbId>` 并看到 KB 卡片带蓝色 ring。

**风险/回滚**：一行改动，零风险。

**STOP & ASK**：可以不 ASK，顺手合入 P3.1 一起。

---

### P3.8 — 跨 Tab 联动补齐 3 条（UX，半天）

**目标**：落地 §5.5 剩下的 3 条跨 Tab 溯源。

**变更**：

**路径一：Sharing → Roles（点角色名）**：

- `KbSharingCard.tsx:219` 角色名从 `<span>` 变成 `<button>` 或 `<Link>`
- 点击：跳 `/admin/access?tab=roles&roleId=<roleId>`
- `RolesTab` 读 `searchParams.roleId` + 预定位 `selectedDeptId = role.deptId` + `selectedRoleId`
- 实现：挂一个 `useEffect(() => { if (roleIdParam) preselectFromRoleId(roleIdParam) }, [roleIdParam, roles])`

**路径二：Roles → Sharing（点 KB 名）**：

- `RolesTab.tsx` 右栏「共享了 N 个 KB」列表每行 KB 名包装 `<Link to={`/admin/access?tab=sharing&kb=${k.kbId}`}>`

**路径三：Roles → Members（点 user 名）**：

- `RolesTab.tsx` 右栏「已分配给 N 个用户」列表每行 user 名包装 `<Link to={`/admin/access?tab=members&userId=${u.userId}&deptId=${u.deptId}`}>`
- `MembersTab` 读 `userId` + `deptId` → 预定位 `selectedDeptId` + `selectedUserId`
- 注意：已有 `?kb=` 参数处理，不要搞冲突；`kb/userId/roleId` 按 tab 语义各自独立。

**验收**：

- 每条路径端到端跑一次（chrome-devtools）
- admin 视角：从 Tab 2 `FICC User` → Tab 3 选中固定收益部/FICC User → Tab 3 点 `OPS-COB知识库` → Tab 2 定位到 OPS-COB知识库
- 每个 tab 切换保持 URL 可分享（刷新后状态不丢）

**风险/回滚**：前端 UX，出错只影响跳转体验。

**STOP & ASK**：三条路径手动跑通后再合。

---

## 四、执行节奏建议

| 批次 | 内容 | 切片 | 工作量 | 风险 |
|---|---|---|---|---|
| 批 1 | 安全关键 | P3.1 + P3.2 + P3.7 | 1 day | 🟥→🟩 |
| 批 2 | 契约正确性 | P3.3 | 半天 | 🟥→🟩 |
| 批 3 | 数据完整性 | P3.5 | 半天 | 🟨→🟩 |
| 批 4 | 可见性 + 代码质量 | P3.4 + P3.6 | 1 day | 🟨 |
| 批 5 | UX polish | P3.8 | 半天 | 🟩 |

**总估时**：~3 个工作日（含单测 + chrome-devtools 双视角验收）

**建议上线顺序**：批 1 先上（含 P3.1 必须先发给 SRE）→ 批 2 → 批 3。批 4 / 批 5 可合并发一版 release note。

---

## 五、验收总表（合批后必过）

| 断言 | 验证方式 | 对应切片 |
|---|---|---|
| opsadmin 在 Tab 3 Operation 节点可创建/编辑/删除 USER 或 DEPT_ADMIN 角色 | chrome-devtools + DB 验证 | P3.2 |
| opsadmin `POST /role body.deptId="<PWM_ID>"` 返回 code != "0" | curl | P3.2 |
| opsadmin 在 Tab 2 看到的 KB 全部 `kb.deptId === 'OPS_ID'` | `evaluate_script` 断言 | P3.3 |
| opsadmin 在 Tab 3 看到全部部门节点 | snapshot | P3.4 |
| `\d t_knowledge_base` 显示 `DEFAULT '1'` | `docker exec psql` | P3.5 |
| kb-grants 接口不会返回 permission=null 的 KB | AccessServiceImplTest 新单测 | P3.6 |
| KB 详情页「在共享管理页修改」跳转后 KB 卡片带 ring 高亮 | chrome-devtools | P3.7 |
| Tab 2 → Tab 3 → Tab 2 / Tab 1 三条跳转路径均保持定位 + URL 可分享 | chrome-devtools | P3.8 |
| 所有路径 SUPER 视角均无回归 | 双视角交叉跑 | 全部 |

---

## 六、附录：未采纳的更大改动

- **`OrgTree` 改成 data provider 缓存 `/access/departments/tree`**：当前 3 个 tab 切换时各发一次请求。改完能节省 2 次请求但引入 Context 层，等 Tab 数变多或发现用户主诉再做。
- **`SharingPage` 的 per-KB `getKbRoleBindings` N+1**：需后端加批量 `GET /access/role-bindings?kbIds=…` 端点。规模 > 200 KB 时再做，独立 issue。
- **角色审批流（跨部门共享需要对方 admin 同意）**：设计 §十 明确延后到 P4，当前 ⚡ badge 仅提示。
- **审计日志（谁何时给谁分配了什么）**：设计 §十，P4 再议。

---

## 七、关联文档

- `docs/dev/design/2026-04-19-access-center-redesign.md` —— 原设计（本计划指向其中的条目）
- 审计过程：2026-04-20 会话 transcript（commits `91af05c..038dc50` 实施 + `71e1e3d` simplify + 本文档对照审查）
- CLAUDE.md gotchas 相关：
  - Sa-Token 鉴权返回 HTTP 200 + code（不是 403/409）
  - `SysDeptServiceImpl.GLOBAL_DEPT_ID = "1"`（值，不是字面量 `'GLOBAL'`）
  - 两份 schema 文件独立维护，改一个另一个必须同步
