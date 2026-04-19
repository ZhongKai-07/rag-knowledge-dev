# 权限中心改造计划（Access Center Redesign）

> 日期：2026-04-19
> 目标分支：`feature/access-center-redesign`
> 预计改动：\~2500 LOC（含前后端 + 测试）
> 交付方式：分 P0 / P1 / P2 三个 PR

***

## 一、背景

### 1.1 现状问题

后台 RBAC 管理 UI 当前散落在 4 个入口：

- `/admin/users`（用户管理）
- `/admin/roles`（角色管理）
- `/admin/departments`（部门管理）
- `/admin/knowledge/{kbId}` 页面底部「共享管理」widget

实地走查（admin / opsadmin 双视角）发现的具体问题：

| #  | 问题                                                                                                                                                                       | 影响                       |
| -- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------ |
| 1  | "用户名 / 角色名 / 角色类型" 散落在 4 页，admin 要在脑里拼"谁能看哪个 KB"                                                                                                                         | 学习成本高                    |
| 2  | 角色 ≈ 用户 1:1（pwmuser 角色给 pwmuser 用户），用户/角色页几乎重复                                                                                                                           | 认知噪声                     |
| 3  | KB 共享面板埋在 KB 详情页底部，必须先点进每个 KB 才能管                                                                                                                                        | 效率差                      |
| 4  | 共享面板用行内 dropdown + 显式 "保存" 按钮，改完不知道改了啥、容易漏点保存                                                                                                                            | 易出错                      |
| 5  | DEPT\_ADMIN (opsadmin) 在 `/admin/roles` 看到全部 6 个角色（含 SUPER\_ADMIN、其他部门角色），且"可见 KB 数=0"                                                                                   | 信息泄露 + 误导                |
| 6  | DEPT\_ADMIN 在 `/admin/users` 没有"管理范围 = Operation 部门" 的明确提示                                                                                                               | 看不到只有 1 人是因为过滤还是真的只有 1 人 |
| 7  | DEPT\_ADMIN 给本部门用户分配角色时，**后端无 dept 校验**（`KbAccessServiceImpl.validateRoleAssignment` line 495-513 仅校验 `roleType` 和 `maxSecurityLevel`），可分配其他部门的 USER 角色 → 间接获得跨部门 KB 访问权 | 安全漏洞                     |
| 7' | （非问题）DEPT\_ADMIN 创建用户时，前端 `UserListPage.tsx:102` 已经把部门字段锁定为本部门，背景描述基线已修正                                                                                                 | —                        |

### 1.2 设计目标

1. **单一入口** —— 所有权限管理收敛到 `/admin/access`
2. **双视角统一** —— SUPER 与 DEPT\_ADMIN 进同一个页面，差异仅在 scope（管理范围）
3. **闭环授权模型** —— 用户 ↔ 角色 ↔ 知识库 三条边，每条边由对应部门 admin 把关
4. **不可绕过的不对称** —— "谁能进我的 KB" 始终由 KB 所属部门 admin 决定

***

## 一·五、Pre-work：必须先验证 + 落地的现状假设

执行者开工第一件事是**验证**下列假设，如有偏差先报告再决定怎么办：

| #  | 假设                                                    | 当前实际                                                                                      | 含义                                                                      |
| -- | ----------------------------------------------------- | ----------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| A1 | `t_role` 已有 `dept_id` 列                               | ❌ **不存在**（schema\_pg.sql:48-60 只有 `id/name/description/role_type/max_security_level/...`） | 本计划所有"角色按部门归属"的设计是**新增基础能力**，不是修补                                       |
| A2 | `RoleDO` / `RoleVO` / `roleService.ts` 暴露 `deptId` 字段 | ❌ 都没有                                                                                     | 需要 schema → 实体 → VO → API → 前端类型一整条改动                                   |
| A3 | `KbRoleBindingVO` 返回角色部门信息                            | ❌ 仅有 `roleId/roleName/roleType/permission/maxSecurityLevel`                               | Tab 2 ⚡ 跨部门徽章无数据可渲染                                                     |
| A4 | `validateRoleAssignment` 校验角色 dept                    | ❌ 仅校验 `roleType + maxSecurityLevel`                                                       | D5 不对称规则后端无 hard reject，前端 dropdown 隐藏不算数                               |
| A5 | `KbSharingTab.tsx` 角色下拉来源                             | ✅ 直接用 `roleService.ts` 的 `GET /role`                                                      | 如 P0 把 `/role` 改成"按 DEPT\_ADMIN 部门过滤"，共享面板会立即坏掉                         |
| A6 | `UserListPage` 创建用户时部门字段对 DEPT\_ADMIN 是否锁定            | ✅ 已锁定（line 102 `deptLocked`）                                                              | 设计稿不要再把这个列为问题                                                           |
| A7 | `/user/me`（`CurrentUserVO`）返回信息                       | ✅ 已含 `userId/username/role/deptId/maxSecurityLevel`                                       | `/access/scope` 必要性需重新评估，见下 D10                                         |
| A8 | `KbAccessService.getAccessibleKbIds(userId)` 计算口径     | ✅ 包含 DEPT\_ADMIN 对**本部门全部 KB 的隐式权限**（line 91/146/329）                                     | Tab 1 派生 KB 列表必须复用此方法，不能自己 `JOIN t_user_role + t_role_kb_relation` 否则少算 |

**结论**：A1/A2/A3/A4 都不成立 → 本计划的"角色按部门归属"必须由一次 schema migration 启动；不能仅靠 controller 层改动落地。

***

## 一·六、Schema Migration（Pre-work 必须先于一切代码改动）

**值域定义**（review #1 修正，关键）：

- `t_role.dept_id` 存的是 **`sys_dept.id`**（`VARCHAR(20)`，与 `t_user.dept_id` / `t_knowledge_base.dept_id` 完全同一字段空间），**不是** **`sys_dept.dept_code`**。
- "GLOBAL 部门" 在数据中是 `sys_dept(id='1', dept_code='GLOBAL', dept_name='全局部门')`（见 `init_data_pg.sql:4-5`），所以 GLOBAL 角色的 `dept_id = '1'`，不是字面量 `'GLOBAL'`。
- D11 hard reject 比较 `role.dept_id == self.dept_id` 时两侧都是 `sys_dept.id`，与现有 `sameDept(user, kbDeptId)` 在 `KbAccessServiceImpl` 用同一比较语义。

**新增迁移文件**：`resources/database/upgrade_v1.5_to_v1.6.sql`

```sql
-- 给 t_role 加 dept_id（外键到 sys_dept.id，不是 dept_code）
ALTER TABLE t_role ADD COLUMN dept_id VARCHAR(20);
COMMENT ON COLUMN t_role.dept_id IS '角色归属部门 ID（sys_dept.id），值 = ''1'' 时为 GLOBAL 角色（仅 SUPER 可创建）';
CREATE INDEX idx_role_dept_id ON t_role(dept_id);

-- 数据 backfill：⚠️ 不做基于角色名的前缀推断
-- review #2 指出仓库 fixture 角色名为 "研发部管理员/法务部管理员/普通研发员/普通法务员"（fixture_pr3_demo.sql:27-31），
-- 不存在 OPS/PWM/FICC 这种可前缀匹配的命名。LIKE 'XXX%' 前缀推断在不同环境完全不可执行。
--
-- 强制做法：每个部署环境必须先人工列出"现有角色 → 应归属部门"映射表，写成显式 UPDATE。
-- 模板（执行前必须按本环境实际填写）：

-- Step 1: SUPER_ADMIN / 默认通用角色 → GLOBAL（id='1'）
UPDATE t_role SET dept_id = '1' WHERE role_type = 'SUPER_ADMIN';
-- 默认"普通用户"角色（init_data_pg.sql 自带）
UPDATE t_role SET dept_id = '1' WHERE name = '普通用户';

-- Step 2: 业务角色 → 部门 id（必须人工核对，每行一对一）
-- 示例（仅做格式参考，部署时请替换为本环境真实角色 id 和部门 id）：
--   UPDATE t_role SET dept_id = '<研发部 sys_dept.id>' WHERE id = '100'; -- 研发部管理员
--   UPDATE t_role SET dept_id = '<法务部 sys_dept.id>' WHERE id = '101'; -- 法务部管理员
--   UPDATE t_role SET dept_id = '<研发部 sys_dept.id>' WHERE id = '102'; -- 普通研发员
--   UPDATE t_role SET dept_id = '<法务部 sys_dept.id>' WHERE id = '103'; -- 普通法务员

-- Step 3: 验证 + 报警
-- SELECT id, name, role_type FROM t_role WHERE dept_id IS NULL;  -- 必须为空才能上 D11
-- 若有残留，由 SUPER 在 P1 上线后通过 PUT /role 手动归位

-- P1 暂不加 NOT NULL，避免遗漏角色阻塞启动；P2 验证全部归位后追加约束：
--   ALTER TABLE t_role ALTER COLUMN dept_id SET NOT NULL;
```

**两份 schema 文件同步**（CLAUDE.md gotcha "Two schema files maintained independently"）：`schema_pg.sql` + `full_schema_pg.sql` 都加 `dept_id` 列 + COMMENT。

**部署 checklist**（每个环境上 P1 前必跑）：

1. 列出所有 `sys_dept.id / dept_code / dept_name` 三元组
2. 列出所有 `t_role.id / name / role_type`
3. 人工出"角色 → 部门 id" 映射表，作为 PR 评审依据
4. 写本环境专属的 `upgrade_v1.5_to_v1.6.<env>.sql` 含具体 UPDATE
5. 跑完执行 `SELECT * FROM t_role WHERE dept_id IS NULL` 必须为 0 行才能上 D11 hard reject

***

## 二、已锁定的设计决定

| 决定                                        | 选择                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | 依据                                            |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| D1 入口结构                                   | 单一菜单「权限中心」(`/admin/access`)，4 Tab 容器                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | 减少导航层级，统一心智模型                                 |
| D2 个人 KB 直授                               | **不支持**。所有 KB 授权必须经过角色                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | 保持 RBAC 模型纯净；当前"角色 ≈ 用户 1:1" 的现状即是 RBAC 的正确用法 |
| D3 GLOBAL 角色创建权                           | 仅 SUPER\_ADMIN                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | 防止跨部门角色泛滥                                     |
| D4 DEPT\_ADMIN 角色管理范围                     | 可在本部门下 CRUD 角色，DEPT\_ADMIN 创建的角色对所有 admin 可见                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | 跨部门共享通过"对方建角色 → 我共享 KB 给该角色"自然达成，无需 SUPER 介入  |
| D5 不对称规则（核心）                              | **共享 (Tab 2)** 角色下拉 = 全部角色；**分配 (Tab 1)** 角色下拉 = 本部门 + GLOBAL                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | 详见 §4.2                                       |
| D6 删除角色                                   | 级联删除 `t_user_role` + `t_role_kb_relation`，删除前显示影响面预览，要求二次确认                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | 防误删；可审计                                       |
| D7 共享面板编辑模式                               | 改 Modal，立即落库；去掉显式"保存"按钮                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | 行内 dropdown 易漏保存                              |
| D8 跨部门共享                                  | 后端不拦（保留当前行为），UI 用 ⚡ badge 实时提示                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | 业务需要灵活；P3 阶段再考虑双签流程                           |
| D9 旧路由                                    | `/admin/users`、`/admin/roles`、`/admin/departments` 保留 30 天，加顶部"已迁移"提示 + 下方仍可用                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | 不打断现有用户                                       |
| D10 `/access/scope` 是否新增独立接口              | **暂不新增**。前端改用现有 `authStore.user`（来自 `/user/me`）派生 scope label。仅当未来 P3 上"SUPER 代入某部门视角"时再单独建 endpoint                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | 避免引入两套易漂移的"身份/范围"状态（review #6 采纳）             |
| D11 D5 后端 hard reject 实现位置                | 扩展 `KbAccessServiceImpl.validateRoleAssignment`：DEPT\_ADMIN 分配时强制 `role.dept_id IN (self.dept_id, SysDeptServiceImpl.GLOBAL_DEPT_ID)`，否则 `ClientException`。⚠️ 用现有常量，**禁止字面量** `'GLOBAL'` 或 `'1'`                                                                                                                                                                                                                                                                                                                                                                          | 前端 dropdown 隐藏不算授权边界（review #2 采纳）            |
| D12 P0 是否动 `/role` 现有签名                   | **不动**。新增 `GET /access/roles` 走新过滤逻辑供新页面用；`/role` 仅修复"DEPT\_ADMIN 不该看到 SUPER\_ADMIN 角色"这一条信息泄露（按 role\_type 黑名单过滤，不按 dept），保持 KbSharingTab 持续可用                                                                                                                                                                                                                                                                                                                                                                                                                           | 切片不互相打架（review #3 采纳）                         |
| D13 `/access/users/{userId}/kb-grants` 实现 | **四步走，`implicit`** **独立判定（review v3 #2 修正）**：(1) `getAccessibleKbIds` 锁定真相范围；(2) 范围内 LEFT JOIN `t_user_role + t_role_kb_relation` 得到 `explicitPermission` / `sourceRoleIds`（可能为空）；(3) **独立**判断 `implicit = (user is DEPT_ADMIN) && (kb.dept_id == user.dept_id)`，与步骤 2 并行不互斥；(4) `effectivePermission = implicit ? 'MANAGE' : explicitPermission`（显式存在 READ/WRITE 也被 implicit 提升为 MANAGE，与 `KbAccessServiceImpl.checkManageAccess:231-253` 真实语义一致）；(5) `getMaxSecurityLevelForKb` 取密级。**关键**：不能把 implicit 当"显式缺失时的兜底"，否则同部门 DEPT\_ADMIN 对一个同时有显式 READ 绑定的 KB 会被错误展示为 READ | review v2 #3 + v3 #2 连续修正                     |

***

## 三、信息架构

### 3.1 顶部公用 Banner（四 Tab 共享）

```
┌──────────────────────────────────────────────────────────────────────┐
│  🔐 权限中心                                                          │
│  当前身份: opsadmin (DEPT_ADMIN)   管理范围: Operation 部门            │
│  [ 团队成员 ] [ 知识库共享 ] [ 角色管理 ] [ 部门组织 (仅超管) ]        │
└──────────────────────────────────────────────────────────────────────┘
```

- SUPER 显示「全公司」（**P1/P2 不做"代入某部门视角"功能；延后到 P3 与** **`GET /access/scope`** **一起规划**）
- DEPT\_ADMIN 显示固定文案「{部门名} 部门」，无下拉
- scope 由前端从 `authStore.user`（已含 `userId/role/deptId/maxSecurityLevel`）派生 `useAccessScope()` hook，注入 React Context；所有 Tab/按钮/下拉读此 Context 收敛
- ⚠️ 与 D10 一致：当前版本范围内**不引入** `GET /access/scope` 接口；review #4 提示已修正

### 3.2 闭环授权模型

```
              ┌─ 用户 ────────┐
              │ 用户所属部门    │
              │ admin 决定分配  │
              └───────┬───────┘
                      │ Tab 1 (分配)
                      ▼
              ┌─ 角色 ────────┐
              │ 角色所属部门    │
              │ admin 决定 CRUD │   ← Tab 3
              │ (GLOBAL 由 SUPER)│
              └───────┬───────┘
                      │ Tab 2 (共享)
                      ▼
              ┌─ 知识库 ──────┐
              │ KB 所属部门     │
              │ admin 决定共享  │
              └───────────────┘
```

每条边由对应部门 admin 独立把关，跨部门协作通过双方各管一边自然交汇。

***

## 四、核心规则

### 4.1 角色可见性 / 操作权限矩阵

| 选中部门节点          | SUPER\_ADMIN | DEPT\_ADMIN（本部门） | DEPT\_ADMIN（其他部门） |
| --------------- | ------------ | ---------------- | ----------------- |
| 🏢 全公司 (GLOBAL) | 全部操作         | 只读               | 只读                |
| 📂 自己部门         | 全部操作         | 全部操作             | —                 |
| 📂 其他部门         | 全部操作         | —                | 只读                |

DEPT\_ADMIN 在 Tab 3 能看到全部部门和角色（用于跨部门共享时知道"市场上有哪些角色"），但只在自己部门节点上有写权限。

### 4.2 不对称规则：共享 ≠ 分配

| <br /> | Tab 2 「共享」（KB owner 视角） | Tab 1 「分配」（用户 owner 视角） |
| ------ | ----------------------- | ----------------------- |
| 决策方    | KB 所属部门 admin           | 用户所属部门 admin            |
| 角色下拉范围 | **全部角色**（含其他部门）         | **仅本部门 + GLOBAL**       |
| 语义     | "谁能进我的 KB"              | "我给我的人加什么角色"            |

**为什么不对称？** 如果两边都允许任意角色，会出现：pwmadmin 把"OPS Contractor"角色分配给一个 PWM 用户 → 该用户立刻获得 OPS-COB 访问权 → **PWM admin 实际上侵入了 OPS 的授权决策**。不对称规则保证 "谁能进我的 KB" 始终由 KB 所属部门 admin 把关。

### 4.3 跨部门共享的标准工作流（方案 C）

> opsadmin 想让 PWM 部门的某分析师能看 OPS-COB

```
1. pwmadmin 在 Tab 3 创建本部门角色「PWM-需OPS数据」
2. opsadmin 在 Tab 2 把 OPS-COB 共享给该角色
3. pwmadmin 在 Tab 1 把该角色分配给该分析师
```

- 全程不需要 SUPER 介入
- pwmadmin 决定"谁拿这个角色"，opsadmin 决定"这个角色看什么"
- 两个 admin 各管一条边，自然汇合

***

## 五、Tab 详细设计

### Tab 1 「团队成员」（默认 Tab）

#### 功能

- 查看组织内（或本部门）成员
- 给成员分配/移除角色
- 新建/删除用户、重置密码
- 查看成员"因角色推导出的可访问 KB"（只读派生视图）

#### 布局

```
┌───────────────────────┬─────────────────────────────────────────────┐
│ 左：组织树 (30%)       │ 右：成员详情 (70%)                           │
│                       │                                              │
│ 🔍 搜索成员           │ ┌────────────────────────────────────────┐   │
│                       │ │ 👤 pwmuser                             │   │
│ 🏢 全公司 (5 人)       │ │ 部门: 私人财富管理部                    │   │
│   📂 Operation (1)    │ │ 创建时间: 2026/04/19 15:30             │   │
│     └ opsadmin        │ │ [重置密码] [删除用户]                   │   │
│   📂 私人财富 (2)      │ └────────────────────────────────────────┘   │
│     ├ pwmadmin        │                                              │
│     └ pwmuser ←选中   │ ─ 已分配角色 ────────── [+ 分配角色] ─       │
│   📂 固收 (1)         │ ┌────────────────────────────────────────┐   │
│     └ ficcuser        │ │ 角色名      类型    所属部门     操作  │   │
│                       │ │ pwmuser    USER    私人财富    [×]   │   │
│ [+ 添加成员]          │ │ FICC User  USER    固收        [×]   │   │
│                       │ └────────────────────────────────────────┘   │
│                       │                                              │
│                       │ ─ 因角色可访问的知识库（只读，派生）──       │
│                       │ ┌────────────────────────────────────────┐   │
│                       │ │ KB 名           权限   密级   来自角色 │   │
│                       │ │ PWM质检        READ   内部   pwmuser  │   │
│                       │ │ OPS-COB知识库  READ   公开   FICC User│   │
│                       │ └────────────────────────────────────────┘   │
└───────────────────────┴─────────────────────────────────────────────┘
```

#### 关键交互

| 动作                    | 交互                                                                    |
| --------------------- | --------------------------------------------------------------------- |
| 选成员                   | 点树节点 → 右侧刷新                                                           |
| 分配角色                  | 点「+ 分配角色」→ Modal（搜索 + 多选）→ 立即落库                                       |
| 移除角色                  | 点 \[×] → 弹确认（"该用户将失去这些 KB: ..."）→ 落库                                  |
| 新建用户                  | 点「+ 添加成员」→ Modal（用户名/密码/部门/初始角色）                                      |
| 点 KB 名                | 跳 Tab 2 并定位该 KB 卡片（溯源）                                                |
| KB "来自角色" 多来源         | 合并显示 `pwmuser +1`，hover 展开（**已确认**）                                   |
| KB 行的 `implicit=true` | 标"部门隐式"badge —— DEPT\_ADMIN 对本部门 KB 的天然权限，无 source role 可显示（D13 + A8） |

#### 双视角差异

| <br />       | SUPER  | DEPT\_ADMIN  |
| ------------ | ------ | ------------ |
| 组织树根         | 🏢 全公司 | 📂 自己部门（单节点） |
| 「+ 分配角色」下拉范围 | 全部角色   | 本部门 + GLOBAL |
| 新建用户时部门字段    | 可选     | 锁定为本部门       |

***

### Tab 2 「知识库共享」

#### 功能

- 查看所有（或本部门）KB 的共享状态
- 给 KB 添加/移除共享角色，配置权限 + 密级
- 反向溯源：点角色查看该角色访问的所有 KB

#### 布局

```
┌───────────────────────────────────────────────────────────────────────┐
│ 🔍 搜索 KB     部门筛选[▼]    排序[最近修改▼]     视图:[卡片|表格]    │
├───────────────────────────────────────────────────────────────────────┤
│ ┌─ OPS-COB知识库 ──────────────── 部门: Operation · 文档 2 ──┐       │
│ │ 已共享 2 个角色                              [+ 添加共享]    │       │
│ │ ┌─────────────────────────────────────────────────────────┐│       │
│ │ │ 角色           类型       权限     密级      归属       ││       │
│ │ │ FICC User      USER       READ    公开      ⚡固收 [×] ││       │
│ │ │ OPS admin      DEPT_ADMIN MANAGE  绝密      本部门 [×] ││       │
│ │ └─────────────────────────────────────────────────────────┘│       │
│ └──────────────────────────────────────────────────────────────┘     │
└───────────────────────────────────────────────────────────────────────┘
```

#### 关键交互

| 动作     | 交互                                       |
| ------ | ---------------------------------------- |
| 添加共享   | 点「+ 添加共享」→ Modal（角色搜索 + 权限 + 密级），立即落库    |
| 改权限/密级 | 点行内字段变下拉 → 失焦即落库（inline edit）            |
| 改密级时下调 | 弹轻提示"确认下调密级？这会限制可见文档"（**已确认**）           |
| 移除共享   | 点 \[×] → 确认弹层 → 落库                       |
| 跨部门标识  | `role.dept_id != kb.dept_id` 时显示 ⚡ + 部门名 |
| 点角色名   | 小弹层列出该角色覆盖的所有 KB（跨 Tab 溯源）               |

#### 双视角差异

| <br />       | SUPER | DEPT\_ADMIN                    |
| ------------ | ----- | ------------------------------ |
| 可见 KB        | 全部    | 仅 `kb.dept_id == self.dept_id` |
| 「+ 添加共享」角色下拉 | 全部角色  | **全部角色**（保留跨部门共享）              |
| 可设置密级上限      | 绝密    | 自身天花板（后端 cap）                  |

***

### Tab 3 「角色管理」

#### 功能

- 按部门组织查看角色列表
- 在选中部门下创建/编辑/删除角色
- 删除角色前显示级联影响预览
- 反向溯源：点角色查看"挂在哪些用户"和"共享给哪些 KB"

#### 布局

```
┌───────────────────────┬─────────────────────────────────────────────┐
│ 左：组织树 (30%)       │ 右：角色列表 (70%)                           │
│                       │                                              │
│ 🔍 搜索角色           │ 📂 Operation 部门的角色 (3)     [+ 新建角色]  │
│                       │                                              │
│ 🏢 全公司 GLOBAL (2)   │ ┌────────────────────────────────────────┐   │
│   └ 超级管理员         │ │ 角色名           类型        密级  操作│   │
│   └ 普通用户           │ │ OPS admin       DEPT_ADMIN  绝密  [改][×]│
│                       │ │ ops_user        USER        公开  [改][×]│
│ 📂 Operation ←选中 (3) │ │ ops_contractor  USER        内部  [改][×]│
│   └ OPS admin         │ └────────────────────────────────────────┘   │
│   └ ops_user          │                                              │
│   └ ops_contractor    │ 选中角色「OPS admin」详情：                  │
│                       │ ┌────────────────────────────────────────┐   │
│ 📂 私人财富 (2)        │ │ 描述: OPS知识库管理员                   │   │
│   └ pwmadmin          │ │ 密级天花板: 3 (绝密)                    │   │
│   └ pwmuser           │ │ 已分配给 1 个用户: opsadmin            │   │
│                       │ │ 共享了 1 个 KB: OPS-COB知识库          │   │
│ 📂 固收 (1)            │ └────────────────────────────────────────┘   │
│   └ FICC User         │                                              │
└───────────────────────┴─────────────────────────────────────────────┘
```

#### 删除级联预览（**已确认需新增 API**）

```
┌────────────────────────────────────────────────────┐
│ ⚠️  删除角色「ops_contractor」                      │
│                                                    │
│ • 3 个用户将失去此角色                              │
│   - opsjunior (Operation)                          │
│   - pwm_audit (私人财富 · 跨部门分配)              │
│   - ficc_junior (固收 · 跨部门分配)                │
│                                                    │
│ • 2 个 KB 共享将被解除                              │
│   - OPS-COB知识库                                  │
│   - OPS运营数据库                                  │
│                                                    │
│ • 受影响用户的 KB 访问差集：                        │
│   - opsjunior: 失去 OPS-COB, OPS运营数据库         │
│                                                    │
│ 此操作不可恢复。                                    │
│              [ 取消 ]  [ 确认删除 ]                │
└────────────────────────────────────────────────────┘
```

#### 双视角差异

| 选中节点      | SUPER  | DEPT\_ADMIN   |
| --------- | ------ | ------------- |
| 🏢 GLOBAL | 可 CRUD | 只读；「+ 新建角色」禁用 |
| 📂 自己部门   | 可 CRUD | 可 CRUD        |
| 📂 其他部门   | 可 CRUD | 只读；「+ 新建角色」禁用 |

***

### Tab 4 「部门组织」（仅 SUPER）

#### 功能

- 部门 CRUD
- 查看每个部门的成员数 / KB 数 / 角色数
- 调整成员部门归属（可选）

#### 布局

```
┌──────────────────────────────────────────────────────────────────────┐
│ [+ 新建部门]     🔍 搜索                                             │
├──────────────────────────────────────────────────────────────────────┤
│ 部门编码  部门名称        用户数  KB 数  角色数  创建时间    操作    │
│ GLOBAL    全局部门          1      1       2     2026/04/13  —      │
│ OPS       Operation         1      1       3     2026/04/14  [改][×]│
│ PWM       私人财富管理部     2      1       2     2026/04/19  [改][×]│
│ FICC      固定收益部         1      0       1     2026/04/19  [改][×]│
└──────────────────────────────────────────────────────────────────────┘
```

#### 关键交互

- 新建部门：Modal（编码 + 名称 + 描述）
- 删除部门：级联预览（影响多少用户/KB/角色），要求先迁移或同时级联
- GLOBAL 部门 \[改]\[×] 永久 disabled（系统保留）

***

### 5.5 四 Tab 互相跳转（链路感）

```
Tab 1 成员 「可访问 KB」列表 ──点 KB──→ Tab 2 定位该 KB 卡片
Tab 2 KB 「共享角色」列表  ──点角色名──→ 弹层 + 链接到 Tab 3
Tab 3 角色 「共享于 M 个 KB」 ──点击──→ Tab 2 筛选该角色
Tab 3 角色 「分配给 N 用户」 ──点击──→ Tab 1 筛选持有该角色的用户
```

***

## 六、后端改动清单

> 复用现有的所有授权 port (framework/security/port/) 和 KbAccessService，不动权限计算核心逻辑。
>
> ⚠️ **前置依赖**：所有 P0 之外的改动都依赖 §一·六 的 schema migration（`t_role.dept_id` 列）落库。

### Pre-work（schema + 实体 + DTO 一条龙）

> ⚠️ 命名对齐现有代码（review v3 Open Question 采纳）：
>
> - 写入 DTO 现为 `RoleController.RoleCreateRequest`（嵌套静态类），不是 `RoleSaveRequest`。扩展此类，不新建。
> - 读取路径现为 `GET /role → Result<List<RoleDO>>` 直接返回实体，没有 `RoleVO` 中间层。P1 不引入新 VO 层，直接在 `RoleDO` 加 `deptId` 即可随序列化下发；如果需要 `deptName`，在 `GET /access/roles` 新接口中返回增强 VO（仅新接口用，旧 `/role` 不改契约）。

| 改动                                                                  | 说明                                                                     |
| ------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `upgrade_v1.5_to_v1.6.sql` + `schema_pg.sql` + `full_schema_pg.sql` | 加 `t_role.dept_id` 列 + 索引 + 数据 backfill；两份 schema 同步（CLAUDE.md gotcha） |
| `RoleDO` 加 `deptId` 字段                                              | MyBatis Plus 实体；既是 DO 也是 `GET /role` 的返回体                              |
| `RoleController.RoleCreateRequest` 加 `deptId` 字段                    | 现有静态嵌套类直接扩展，命名不变                                                       |
| `KnowledgeBaseController.KbRoleBindingVO` 加 `deptId` + `deptName`   | 让前端 ⚡ 跨部门徽章有数据；仅此 VO 是真实存在的，命名沿用                                       |
| **新增** `AccessRoleVO`（仅新接口 `GET /access/roles` 用）                   | 包含 `deptName` / `userCount` / `kbCount` 等衍生字段，不污染现有 `RoleDO` 契约        |
| `roleService.ts` Role 类型同步 + `KbRoleBinding` 类型加 `deptId/deptName`  | 前端类型对齐                                                                 |

### P0 接口（不破坏现有 `/role`）

| 改动                                                                                              | 说明                                                                            |
| ----------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `RoleController.list` **不改签名**，仅加 role\_type 黑名单：DEPT\_ADMIN 不能看到 `role_type='SUPER_ADMIN'` 的角色 | 修复 review #1 信息泄露的最小子集；保持 `KbSharingTab` 仍能拿到全部角色（含其他部门）                      |
| `GET /role/{id}/delete-preview`                                                                 | 返回 `{affectedUsers: [...], affectedKbs: [...], userKbDiff: [...]}`，给前端删除二次确认用 |
| **不再做** `GET /access/scope`                                                                     | D10 决议：前端用 `authStore.user` 派生                                                |

### P1 接口（依赖 schema migration 完成）

| 改动                                                                   | 说明                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /access/roles?dept_id=&include_global=true`                     | **新接口**，专供新页面用。`/role` 老接口不动。SUPER 可查任意 dept；DEPT\_ADMIN 可查任意 dept（用于共享面板）；写校验在 `POST /role` 上把关                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `GET /access/users/{userId}/kb-grants`                               | 四步算法（review v3 #2 修正，`implicit` 独立不互斥）：① `getAccessibleKbIds(userId, READ)` 锁真相范围；② 范围内 LEFT JOIN `t_user_role + t_role_kb_relation` → 每 KB 得 `explicitPermission`（nullable）+ `sourceRoleIds`（可空数组）；③ **并行**判 `implicit = isDeptAdmin(user) && kb.deptId == user.deptId`（不看 join 结果）；④ `effectivePermission = implicit ? 'MANAGE' : explicitPermission`，即使同时有显式 READ/WRITE 也被 implicit 提升（与 `checkManageAccess:231-253` 对齐）；⑤ `getMaxSecurityLevelForKb` 取密级。返回 `[{kbId, kbName, deptId, permission: effectivePermission, explicitPermission, securityLevel, sourceRoleIds, implicit}]` —— 保留 `explicitPermission` 便于前端调试与审计 |
| `GET /access/roles/{roleId}/usage`                                   | 返回 `{users: [...], kbs: [...]}`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `GET /access/departments/tree`                                       | **新接口**，部门树 + 每节点的 `userCount/roleCount/kbCount`，供 Tab 1 / Tab 3 / Tab 4 共用，避免前端自己拉全量分页拼树（review #5 采纳）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `UserPageRequest` 加 `deptId` 过滤                                      | Tab 1 选树节点后按部门拉用户列表（review #5 采纳）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `SysDeptVO` 加 `userCount` / `roleCount` / `kbCount`                  | Tab 4 列表展示用                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `POST /role` / `PUT /role`                                           | 校验 `dept_id`：DEPT\_ADMIN 创建时强制为本部门；`dept_id == SysDeptServiceImpl.GLOBAL_DEPT_ID`（即 `"1"`）仅 SUPER 可设。**实现引用常量，禁止字面量** `'GLOBAL'`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `KbAccessServiceImpl.validateRoleAssignment` 扩展（**D11 hard reject**） | DEPT\_ADMIN 分配角色时强制 `role.dept_id IN (self.dept_id, SysDeptServiceImpl.GLOBAL_DEPT_ID)`，否则 `ClientException`。这是 review #2 的核心修复。⚠️ 全文档（D11 / 验证清单 / 切片 checklist）凡涉及 GLOBAL 部门 ID 的代码片段一律用 `SysDeptServiceImpl.GLOBAL_DEPT_ID`，不写字面量                                                                                                                                                                                                                                                                                                                                                                                         |
| `DELETE /role/{id}` 改为事务级联                                           | 删 `t_user_role` + `t_role_kb_relation` + 失效相关用户的 `kb_access:` / `kb_security_level:` 缓存（事务前先收集 affectedUserIds）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

### 不动

- `GET/PUT /knowledge-base/{kb-id}/role-bindings` 直接被 Tab 2 复用
- `GET /role` 保留旧签名 30 天（与 D9 旧路由生命周期同步），仅做 role\_type 黑名单过滤；新页面一律走 `/access/roles`

### 缓存失效面（关键）

按 `feedback_cache_invalidation_coverage.md` 的教训，所有改动列出失效面：

| 操作           | 需失效的缓存                                             |
| ------------ | -------------------------------------------------- |
| 给用户分配/移除角色   | `kb_access:{userId}`, `kb_security_level:{userId}` |
| 修改角色 ↔ KB 绑定 | 该角色下所有用户的 `kb_access:*`, `kb_security_level:*`     |
| 删除角色         | 同上（在事务前先收集影响用户列表）                                  |
| 修改用户部门归属     | 该用户的两个缓存                                           |

***

## 七、前端改动清单

| 文件                                                                       | 操作                                                                                                                              |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| `frontend/src/pages/admin/access/AccessCenterPage.tsx`                   | **新建**，4 Tab 容器 + Banner + scope Context Provider                                                                               |
| `frontend/src/pages/admin/access/MembersTab.tsx`                         | **新建**，组织树 + 详情面板                                                                                                               |
| `frontend/src/pages/admin/access/SharingTab.tsx`                         | **新建**，吸收并升级 KB 详情页的 SharingPanel                                                                                               |
| `frontend/src/pages/admin/access/RolesTab.tsx`                           | **新建**，组织树 + 角色列表                                                                                                               |
| `frontend/src/pages/admin/access/DepartmentsTab.tsx`                     | **新建**，移植自现 `DepartmentsPage`                                                                                                   |
| `frontend/src/pages/admin/access/components/OrgTree.tsx`                 | **新建**，Tab 1/3 共用的部门组织树                                                                                                         |
| `frontend/src/pages/admin/access/components/RoleDeleteConfirmDialog.tsx` | **新建**，调用 `/role/{id}/delete-preview` 渲染影响面                                                                                     |
| `frontend/src/pages/admin/access/hooks/useAccessScope.ts`                | **新建**，scope Context；不调 `/access/scope`（D10），从 `authStore.user` 派生 `{role, deptId, deptName, isSuper, isDeptAdmin, scopeLabel}` |
| `frontend/src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx`          | **路径修正**（review #补充2）：删除底部 `KbSharingTab` 嵌入（line 38, 615），改只读摘要 + 「→ 在权限中心管理」跳转。原 `KbSharingTab.tsx` 文件本身可删除或转为只读模式            |
| `frontend/src/pages/admin/AdminLayout.tsx`                               | **路径修正**（review #补充2）：侧边栏「设置」组下 4 项合并为单项「权限中心」。注意是 `pages/admin/AdminLayout.tsx`，不是 `components/admin/...`                      |
| `frontend/src/router.tsx`                                                | 新增 `/admin/access`；旧 4 路由保留 30 天，加顶部"已迁移"提示                                                                                     |
| `frontend/src/services/access.ts`                                        | **新建**，封装 `/access/roles`、`/access/users/{id}/kb-grants`、`/access/roles/{id}/usage`、`/access/departments/tree` 等新接口             |
| `frontend/src/services/roleService.ts`                                   | Role 类型加 `deptId/deptName`；`KbRoleBinding` 同步                                                                                   |
| `frontend/src/utils/permissions.ts`                                      | 加 `canAssignRoleToUser(role, targetUser)` 工具方法（前端 dropdown 收敛用，但**不替代后端 D11 hard reject**）                                      |

***

## 八、落地切片

> **重要**：切片顺序经过 review #3 修正——P0 不动 `/role` 现有签名，避免 `KbSharingTab` 立即坏掉；schema migration 是 P1 起步的硬前置。

### P0：紧急修复 + 共享面板搬家（1-2 天）

**目标**：堵安全漏洞 + 共享入口提升，零 schema 改动，零现有 API 签名变更。

- [ ] **修 RoleController.list 信息泄露**：DEPT\_ADMIN 不应看到 `role_type='SUPER_ADMIN'` 的角色（仅 role\_type 黑名单，**不**按 dept 过滤，保持 KbSharingTab 角色下拉仍含跨部门角色）
- [ ] 加 `GET /role/{id}/delete-preview` 接口
- [ ] 新建 `/admin/sharing` 独立页（Tab 2 的极简版），把 `KnowledgeDocumentsPage.tsx` 底部 `KbSharingTab` 内容搬过去；原位置改为只读摘要 + 跳转
- [ ] **不做** `GET /access/scope`（D10 决议）
- [ ] **不做** schema migration（P1 起步再做）
- [ ] **不做** D11 hard reject（依赖 `role.dept_id`，必须 P1 才能落）

> ⚠️ P0 **不修复** review #2 的"DEPT\_ADMIN 跨部门 USER 角色分配"漏洞——该漏洞修复需要 `role.dept_id` 列存在。文档需在 P0 PR 描述里明确"此漏洞 P1 修复，P0 范围不含"，避免误导。

### P1：Schema migration + 主体重构（5-7 天）

**目标**：让"角色按部门归属"成为基础能力，并落地 D11 hard reject。

> ⚠️ **执行铁律**（review v2 #1/#2）：
>
> - `t_role.dept_id` 存的是 `sys_dept.id`，**不是** `dept_code`。所有写入和比较都用 id（如 GLOBAL = `'1'`），跟现有 `t_user.dept_id` / `t_knowledge_base.dept_id` 同一字段空间。
> - backfill **禁止** 用角色名前缀模式匹配 —— 仓库 fixture 角色名为中文 (`研发部管理员`)，不是 `OPS/PWM/FICC`。每个环境必须人工列映射表写显式 UPDATE，跑完 `WHERE dept_id IS NULL` 必须为 0 行才能上 D11。

- [ ] **Pre-work**：按 §一·六 部署 checklist 执行 `upgrade_v1.5_to_v1.6.<env>.sql`（环境专属），更新两份 schema 文件，并跑残留检查
- [ ] 按 §六 Pre-work 命名对齐规则扩展现有类：`RoleDO` 加 `deptId` 字段；`RoleController.RoleCreateRequest` 加 `deptId` 字段；`KnowledgeBaseController.KbRoleBindingVO` 加 `deptId` + `deptName`；新建 `AccessRoleVO`（仅 `GET /access/roles` 用）。**不要搜** `RoleVO` / `RoleSaveRequest`，这两个类不存在
- [ ] 改 `roleService.ts` 类型同步
- [ ] 新建 `GET /access/roles?dept_id=&include_global=true`
- [ ] 新建 `GET /access/users/{userId}/kb-grants`（**复用** **`getAccessibleKbIds`**）
- [ ] 新建 `GET /access/roles/{roleId}/usage`
- [ ] 新建 `GET /access/departments/tree`（带 `userCount/roleCount/kbCount`）
- [ ] `UserPageRequest` 加 `deptId` 过滤；`SysDeptVO` 加三项 count
- [ ] 改 `POST /role` / `PUT /role`：`dept_id` 校验
- [ ] **D11 核心修复**：扩展 `validateRoleAssignment`，DEPT\_ADMIN 分配的角色 `dept_id IN (self.dept_id, SysDeptServiceImpl.GLOBAL_DEPT_ID)`
- [ ] 改 `DELETE /role` 为事务级联 + 缓存失效
- [ ] 新建 `/admin/access` 容器 + Tab 1/2/3
- [ ] 旧 4 页加"已迁移"提示（保留）

### P2：收尾（1-2 天）

- [ ] Tab 4 「部门组织」迁入（依赖 `SysDeptVO` 的 count 字段已落 P1）
- [ ] `t_role.dept_id` 加 `NOT NULL DEFAULT '1'`（即 `SysDeptServiceImpl.GLOBAL_DEPT_ID`；P1 留作可空，等 P1 后所有角色都归位再加约束）
- [ ] 删除 `frontend/src/pages/admin/knowledge/KbSharingTab.tsx` 旧实现
- [ ] 旧 4 路由变 301 重定向到 `/admin/access?tab=...`
- [ ] 删除 `GET /role` 老接口（30 天观察期满）

***

## 八·五、执行方式与会话策略

> 设计文档落档后，按本节策略推进。每个子任务都是**独立 PR + 独立会话**（除 P0.1 外），目的是控上下文、保 review 节奏。

### 8.5.1 PR 拆分总览

| 阶段 | 子 PR | 范围 | 估时 | 会话策略 | chrome-devtools 验收 |
|---|---|---|---|---|---|
| **P0.1** | 后端漏洞修复 | `RoleController.list` 加 `role_type='SUPER_ADMIN'` 黑名单过滤（仅 DEPT_ADMIN 看不到 SUPER_ADMIN 角色）+ 单测 | 30 min | **当前会话执行**（设计上下文新鲜） | ✅ 双角色登录走查 |
| **P0.2** | delete-preview API | 新增 `GET /role/{id}/delete-preview` 返回 `{affectedUsers, affectedKbs, userKbDiff}` + 单测 | 1-2h | **新会话**（后端独立逻辑） | ❌ 后端纯接口，用 `curl` + 单测 |
| **P0.3** | 共享面板搬家 | 新建 `/admin/sharing` 单独页（卡片视图，复用 `PUT /role-bindings`）+ KB 详情页底部 `KbSharingTab` 改只读 + 跳转链接 | 半天 | **新会话** | ✅ 必须，端到端验收 |
| **P1.0** | Schema migration | 写 `upgrade_v1.5_to_v1.6.sql` + 同步 `schema_pg.sql` + `full_schema_pg.sql`；按部署 checklist 跑映射；验证 `WHERE dept_id IS NULL = 0` | 2h | **新会话** | ❌ DB 操作，`docker exec psql` 验收 |
| **P1.1** | 实体/DTO 字段贯通 | `RoleDO` / `RoleController.RoleCreateRequest` / `KbRoleBindingVO` 加 `deptId`；前端类型同步 | 1-2h | **新会话** | ⚠️ 仅冒烟（旧页面不应崩） |
| **P1.2** | D11 hard reject | 扩展 `KbAccessServiceImpl.validateRoleAssignment` + 单测覆盖跨部门 USER 角色拒绝 | 半天 | **新会话**（安全关键，独立 review） | ✅ opsadmin 强制构造跨部门角色分配 → 断言 ClientException |
| **P1.3** | 新接口三件套 | `GET /access/roles`、`/access/users/{id}/kb-grants`（按 D13 四步算法）、`/access/roles/{id}/usage`、`/access/departments/tree` + 单测 | 1.5 天 | **新会话**（接口面较大，建议再拆 a/b 两子 PR） | ❌ 后端接口，`curl` + 单测 |
| **P1.4** | DELETE /role 级联 + 缓存失效 | 事务级联 + Redis `kb_access:` / `kb_security_level:` 失效 | 半天 | **新会话**（缓存失效面是历史教训点，独立 review） | ✅ 删角色后 opsadmin 立即查 KB 列表，断言访问真相已变 |
| **P1.5** | 前端权限中心容器 + Tab 1/2/3 | 新建 `AccessCenterPage` + `MembersTab` + `SharingTab`（升级 P0.3 版本）+ `RolesTab` + `OrgTree` + scope context | 2-3 天 | **新会话**（必须带设计文档进） | ✅ 必须，每 Tab 走查 |
| **P1.6** | 旧路由迁移提示 | `/admin/users` `/admin/roles` `/admin/departments` 顶部加"已迁移"banner，保留可用 | 1h | **新会话** | ✅ 旧路由仍能正常加载 |
| **P2.1** | Tab 4 「部门组织」 | 移植 `DepartmentsPage` 到 Tab 4，依赖 P1.3 的 dept tree 接口 | 半天 | **新会话** | ✅ |
| **P2.2** | NOT NULL 约束追加 + 旧路由重定向 | 跑 `ALTER TABLE t_role ALTER COLUMN dept_id SET NOT NULL`；旧 4 路由变 301；删除 `KbSharingTab.tsx` 旧实现；删除 `GET /role` 老接口 | 1-2h | **新会话**（DB 约束变更要谨慎） | ✅ 旧路由确实重定向 |

### 8.5.2 chrome-devtools-mcp 在前端验收的标准流程

每个带"✅"标记的 PR 都按此模板走：

```
1. 后端改动：mvn clean install -DskipTests + 重启 spring-boot:run（CLAUDE.md gotcha）
2. 前端改动：Vite HMR 自动生效，无需重启
3. chrome-devtools 验收：
   a. mcp_navigate /login（新 page）
   b. mcp_fill_form 登录 admin/123456 → 验收 SUPER 视角
   c. take_snapshot 断言关键元素（角色行数 / 按钮 disable 状态 / dropdown 选项）
   d. 登出（点用户菜单 → 退出登录）
   e. 重新登录 opsadmin/opsadmin → 验收 DEPT_ADMIN 视角
   f. 重复 c 步断言（关键差异：少了哪些项 / 多了哪些 banner）
   g. take_screenshot 关键页面，附在 PR 描述
4. 异常路径：手动构造越权请求（如 fetch API 直接 POST），断言后端拒绝
```

**双视角验收覆盖率要求**：每个 PR 至少跑 admin + opsadmin 两套，遇到 KB 权限相关变动还需补 pwmadmin。所有 P0.1 / P1.2 / P1.4 / P1.5 / P1.6 必须双视角跑完才算 PR 通过。

### 8.5.3 不同阶段的会话指引

**P0.1（30 分钟，当前会话）**
- 不需要新会话，设计上下文已经热。
- 直接：开分支 `feature/access-center-p0-1-role-list-filter` → 改 `RoleController` 或 `RoleService` → 加单测 → chrome-devtools 双视角验收 → 提 PR

**P0.2 / P0.3（独立功能，每个新会话）**
- 新会话开场带：本设计文档路径 + P0.x 章节范围 + 上一个 PR 的 commit hash
- 用 `superpowers:executing-plans` skill 走

**P1 各子 PR（必须新会话）**
- 每个 PR 必须带：本设计文档完整路径 + 该子 PR 在 §八 的 checklist 项 + Pre-work checklist（§一·六）
- 安全关键 PR（P1.2、P1.4）建议用 `superpowers:requesting-code-review` 在合并前再过一遍
- P1.5 前端容器是最大的一个 PR，建议再拆 P1.5a（容器 + scope context）/ P1.5b（Tab 1）/ P1.5c（Tab 2 升级）/ P1.5d（Tab 3）四子 PR，每个独立会话

**P2 收尾（必须新会话）**
- P2.2 涉及不可回滚的 DB 约束变更和接口删除，必须在生产前先在 staging 跑一周

### 8.5.4 PR 描述模板（所有 PR 通用）

```markdown
## 改动范围
- 关联设计文档：docs/dev/design/2026-04-19-access-center-redesign.md §X.X
- 阶段：P0.x / P1.x / P2.x

## 改动内容
- [ ] 后端：xxx
- [ ] 前端：xxx
- [ ] 单测：xxx
- [ ] DB 迁移：xxx（如有）

## 验收清单
- [ ] mvn test 通过
- [ ] chrome-devtools admin 视角验收：[截图链接]
- [ ] chrome-devtools opsadmin 视角验收：[截图链接]
- [ ] 关键越权路径验收：[curl/fetch 命令 + 响应]

## 不在范围
- 显式列出本 PR 不做的相关项（避免 reviewer 误以为漏）

## Rollback 计划
- 若上线后发现问题，按 X 步骤回滚
```

***

## 九、验证清单

### 安全验证（每个 PR 必跑）

- [ ] **D11 hard reject 验收（review #2 核心）**：DEPT\_ADMIN A 即使绕过前端直接 POST `/user/{id}/roles`（body 含其他部门的 USER 角色 ID），后端必须返回 ClientException。仅前端 dropdown 隐藏不算通过
- [ ] DEPT\_ADMIN A 无法在 Tab 1 给本部门用户分配 DEPT\_ADMIN B 部门的角色（前端 dropdown 不显示 + 后端 hard reject）
- [ ] DEPT\_ADMIN 在 Tab 3 GLOBAL 节点下「+ 新建角色」按钮 disabled，且后端拒 `dept_id == SysDeptServiceImpl.GLOBAL_DEPT_ID` 的创建
- [ ] DEPT\_ADMIN 在 Tab 2 仅看到本部门 KB（断言：`response.kbs.every(kb => kb.deptId === self.deptId)`）
- [ ] 删除角色后，原持有该角色的用户立即重新计算 KB 访问列表正确（缓存已失效）
- [ ] DEPT\_ADMIN 设置共享密级超过自身天花板被拒 (`level > selfCeiling`)

### 体验验证

- [ ] Tab 间互相跳转保持选中态（如 Tab 2 点角色名跳 Tab 3 后角色被高亮）
- [ ] Banner 中"管理范围"始终显示，所有 Tab 一致
- [ ] 删除角色弹层显示完整影响面，确认按钮显著
- [ ] 共享 Modal 提交后表格立即更新，无需手动刷新

### 回归验证（旧路由）

- [ ] `/admin/users`、`/admin/roles`、`/admin/departments` 30 天内仍可用
- [ ] 旧 KB 详情页跳转链接正确带 `?kb=xxx` 锚点

***

## 十、未决事项 / P3 backlog

1. **跨部门共享是否需要审批流？** 当前 Tab 2 跨部门 ⚡ badge 仅提示，不拦截。如果业务侧未来要求 "OPS 把 KB 共享给 PWM 角色需要 PWM admin 同意"，新增 `t_kb_sharing_request` + 待审批队列 Tab。本计划暂不做。
2. **角色继承** 是否需要？目前每个角色独立，无 parent。如果出现"Senior Analyst 是 Junior Analyst 的超集"需求，再考虑。
3. **审计日志页** 当前缺失。"谁在何时给谁分配了什么角色 / 共享了什么 KB" 应当有时间线视图，归到 P3。

***

## 附录 A：相关历史文档

- `docs/dev/design/2026-04-13-rbac-permission-fixes-design.md` — RBAC 基础设计
- `docs/dev/design/rbac-and-security-level-implementation.md` — 密级实现细节
- `docs/dev/follow-up/2026-04-18-rbac-refactor-retrospective.md` — 上次 RBAC 重构经验
- `docs/dev/security/2026-04-18-authorization-baseline.md` — 端点级授权基线（本计划改动须更新此文档）

## 附录 B：CLAUDE.md 关联条目（实施时遵守）

- "Every controller needs explicit authorization" — 新增的 `/access/*` 接口必须显式 `@SaCheckRole` 或 port 校验
- "KB-centric sharing API uses hyphenated `kb-id` in path" — 复用时保持一致
- "缓存失效面覆盖" — §6 已逐一列出
- "Frontend permission-gated components must handle backend rejection" — Tab 4 对 DEPT\_ADMIN 应直接不渲染，不靠 disable

