# RBAC 权限体系补全 — 设计规格 v4

> **日期**：2026-04-13
> **状态**：待实施
> **前置文档**：`docs/dev/design/rbac-and-security-level-implementation.md`（决策 2-A、PR3 路线图）
> **分支**：`feature/rbac-pr3-frontend-demo`
> **环境假设**：开发环境，可随时删除重建数据库 + OpenSearch 索引 + Redis，不需要数据迁移脚本

---

## 一、问题概述

| 编号 | 类型 | 描述 |
|------|------|------|
| **A** | 安全漏洞 | `security_level` 检索过滤使用全局 `MAX(所有角色)`，跨 KB 泄漏高密级文档 |
| **B** | 功能阻断 | `GET /role`、`GET /user/{userId}/roles`、`GET /sys-dept` 被 SUPER_ADMIN 锁死，DEPT_ADMIN 弹窗/页面数据加载失败 |
| **C** | 功能缺失 | 跨部门 KB 共享无入口；现有 role-centric API 全量覆盖语义对 DEPT_ADMIN 不安全 |
| **D** | 提权风险 | DEPT_ADMIN 可通过创建/编辑用户分配 DEPT_ADMIN 角色或超自身 ceiling 的角色 |
| **E** | 契约语义 | `LoginUser.maxSecurityLevel` 语义需重定义 |
| **F** | 越权变更 | DEPT_ADMIN 编辑用户时可将 deptId 改到其他部门 |

### 分阶段路线

| Phase | 范围 | 性质 |
|-------|------|------|
| **Phase 1** | A + E + Schema | 纯安全修复 + SUPER_ADMIN 绑定 DTO 补 maxSecurityLevel |
| **Phase 2** | B + C + D + F + 前端 | 功能补全 + 提权封堵 + 前端适配 |

---

## 二、Schema 变更（Phase 1 落地，Phase 2 共用）

### 2.1 `t_role_kb_relation` 新增列

```sql
ALTER TABLE t_role_kb_relation
  ADD COLUMN max_security_level SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN t_role_kb_relation.max_security_level
  IS '该角色对该 KB 可访问的最高安全等级（0-3），检索时按此值过滤';
```

### 2.2 字段语义

| 字段 | 语义 | 用途 |
|------|------|------|
| `t_role.max_security_level` | 角色天花板（能力上限） | 绑定默认值 + 上界校验 + 用户 VO 展示 |
| `t_role_kb_relation.max_security_level` | 角色对该 KB 的实际安全等级 | 检索时按此值过滤 |

**约束**：`role_kb_relation.max_security_level <= role.max_security_level`。

### 2.3 同步更新

- `resources/database/schema_pg.sql`
- `resources/database/full_schema_pg.sql`
- `resources/database/init_data_pg.sql` — 存量绑定写入合理 `max_security_level` 值
- `RoleKbRelationDO.java` — 新增 `maxSecurityLevel` 字段

### 2.4 开发环境处理

删库重建，不需要迁移脚本。

---

## 三、Phase 1 — 安全修复（A + E）

### 3.1 `LoginUser.maxSecurityLevel` 语义重定义（E）

**不删除**。语义从"检索过滤值"变为"角色能力天花板（展示 + 绑定校验用）"。

计算方式不变（取所有角色 MAX），**不再用于检索过滤**。前后端 VO 不改。

### 3.2 SUPER_ADMIN 绑定 DTO 补 maxSecurityLevel

`RoleKbBindingRequest` 增加可选字段：

```java
@Data
public static class RoleKbBindingRequest {
    private String kbId;
    private String permission;
    private Integer maxSecurityLevel; // 可选，默认取 role.max_security_level
}
```

`RoleServiceImpl.setRoleKnowledgeBases()` 写入：

```java
int level = (binding.getMaxSecurityLevel() != null)
    ? binding.getMaxSecurityLevel()
    : role.getMaxSecurityLevel();
relation.setMaxSecurityLevel(level);
```

前端 `roleService.ts` 请求体新增 `maxSecurityLevel`。SUPER_ADMIN 角色管理页 KB 绑定弹窗加 0-3 下拉。

### 3.3 新增 `KbAccessService.getMaxSecurityLevelForKb()`

```java
Integer getMaxSecurityLevelForKb(String userId, String kbId);
```

- `SUPER_ADMIN`：返回 `3`
- `DEPT_ADMIN` 且 `kb.dept_id == self.dept_id`：返回 `MAX(t_role.max_security_level)`（隐式管理权天花板）
- 其他：

```sql
SELECT MAX(rkr.max_security_level)
FROM t_role_kb_relation rkr
JOIN t_user_role ur ON ur.role_id = rkr.role_id
WHERE ur.user_id = :userId AND rkr.kb_id = :kbId AND rkr.deleted = 0
```

- 无记录：返回 `null`

**缓存**：Redis `rag:kb_security_level:{userId}:{kbId}`，TTL 30 分钟。`evictCache(userId)` 时 pattern 清除。

### 3.4 检索链路改造

需要改造的检索路径共三条，每条都必须从 resolver 获取 per-KB 安全等级：

#### 3.4.1 公共变更

**`SearchContext`**：移除 `maxSecurityLevel`，新增 `kbSecurityLevelResolver`（`Function<String, Integer>`）。

**`MultiChannelRetrievalEngine.buildSearchContext()`**：

```java
.kbSecurityLevelResolver(kbId -> {
    if (!UserContext.hasUser()) return null;
    return kbAccessService.getMaxSecurityLevelForKb(UserContext.getUserId(), kbId);
})
```

**`buildMetadataFilters()`** 签名改为 `buildMetadataFilters(SearchContext ctx, String kbId)`，内部通过 `ctx.getKbSecurityLevelResolver().apply(kbId)` 获取安全等级。

#### 3.4.2 路径 A — 单 KB 定向检索（空间锁定聊天）

**位置**：`MultiChannelRetrievalEngine.retrieveKnowledgeChannels()` 第 79-101 行，`knowledgeBaseId != null` 分支。

**现状**：第 89 行 `buildMetadataFilters(context)` 使用旧签名，不传 kbId。

**改为**：`buildMetadataFilters(context, knowledgeBaseId)`，从 resolver 获取该 KB 的 per-KB 安全等级。

#### 3.4.3 路径 B — 多通道并行检索 / VectorGlobalSearchChannel

**位置**：`VectorGlobalSearchChannel` 遍历 KB collections 构建 RetrieveRequest。

**改为**：每个 collection 独立调用 `buildMetadataFilters(context, kbId)` 构建该 KB 专属的 MetadataFilter。

#### 3.4.4 路径 C — 多通道并行检索 / IntentDirectedSearchChannel + IntentParallelRetriever

**位置**：`IntentDirectedSearchChannel.retrieveByIntents()` 第 183 行把同一份 `buildMetadataFilters(context)` 传给 `parallelRetriever`。`IntentParallelRetriever.executeParallelRetrieval()` 第 58-63 行将同一份 filters 复制到所有 IntentTask。

**问题**：每个 intent 可能关联不同 KB（不同 `node.getKbId()`），但当前共享同一份 filter 列表。

**改为**：
1. `IntentDirectedSearchChannel.retrieveByIntents()` 不再传 `List<MetadataFilter>`，改为传 `SearchContext`（包含 resolver）
2. `IntentParallelRetriever.executeParallelRetrieval()` 签名改为接收 `SearchContext`
3. 构建 `IntentTask` 时，按 `nodeScore.getNode().getKbId()` 独立调用 `buildMetadataFilters(context, kbId)` 为每个 task 生成专属 filter

```java
// IntentParallelRetriever 改造后
List<IntentTask> intentTasks = targets.stream()
    .map(nodeScore -> new IntentTask(
            nodeScore,
            resolveIntentTopK(nodeScore, fallbackTopK, topKMultiplier),
            MultiChannelRetrievalEngine.buildMetadataFilters(context, nodeScore.getNode().getKbId())
    ))
    .toList();
```

### 3.5 Phase 1 前端改动

仅 SUPER_ADMIN 角色管理页 KB 绑定弹窗新增 `maxSecurityLevel` 下拉（0-3）。

---

## 四、Phase 2 — 功能补全（B + C + D + F）

### 4.1 漏洞 B — DEPT_ADMIN 数据访问权限开放

#### 4.1.1 后端端点权限调整

| 端点 | 改前 | 改后 | 额外校验 |
|------|------|------|---------|
| `GET /role` | SUPER_ADMIN | SUPER_ADMIN + DEPT_ADMIN | 无（角色全局） |
| `GET /user/{userId}/roles` | SUPER_ADMIN | SUPER_ADMIN + DEPT_ADMIN | DEPT_ADMIN 只查本部门用户 |
| `GET /sys-dept` | SUPER_ADMIN（类级） | SUPER_ADMIN + DEPT_ADMIN | 无（只读部门列表） |

**`SysDeptController` 改造**：移除类级 `@SaCheckRole("SUPER_ADMIN")`，改为方法级：
- `GET /sys-dept`（list）：编程式 AnyAdmin 检查
- `GET /sys-dept/{id}`（detail）：编程式 AnyAdmin 检查
- `POST /sys-dept`、`PUT /sys-dept/{id}`、`DELETE /sys-dept/{id}`：方法级 `@SaCheckRole("SUPER_ADMIN")`

**不变的端点**：
- `GET /role/{roleId}/knowledge-bases` — 保持 SUPER_ADMIN 独占（DEPT_ADMIN 从 KB 详情页管共享）
- 角色 CRUD（POST / PUT / DELETE `/role`）— 保持 SUPER_ADMIN 独占

### 4.2 漏洞 C — KB-centric 共享管理接口

**新增两个端点**：

**读取**：

```
GET /knowledge-base/{kbId}/role-bindings
```

- SUPER_ADMIN：任意 KB
- DEPT_ADMIN：仅 `kb.dept_id == self.dept_id`
- 返回：`[{ roleId, roleName, roleType, permission, maxSecurityLevel }]`

**写入（该 KB 维度的全量覆盖）**：

```
PUT /knowledge-base/{kbId}/role-bindings
Body: [{ roleId, permission, maxSecurityLevel }]
```

作用域限定在这一个 KB，不影响其他 KB 绑定。

DEPT_ADMIN 校验链：
1. `kb.dept_id == currentUser.dept_id`
2. `binding.maxSecurityLevel <= role.max_security_level`（不超角色天花板）
3. `binding.maxSecurityLevel <= DEPT_ADMIN 自身角色天花板`（不超自身能力）

默认值：未指定 `maxSecurityLevel` 时取 `role.max_security_level`。

现有 role-centric API（`PUT /role/{roleId}/knowledge-bases`）不变，保持 SUPER_ADMIN 独占。

### 4.3 漏洞 D — DEPT_ADMIN 角色分配提权修复

两条路径都要修，抽取共用方法 `validateRoleAssignment(List<String> roleIds)`：

```java
private void validateRoleAssignment(List<String> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) return;
    int currentCeiling = UserContext.get().getMaxSecurityLevel();
    List<RoleDO> roles = roleMapper.selectList(
        Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds));
    for (RoleDO role : roles) {
        if (RoleType.SUPER_ADMIN.name().equals(role.getRoleType())) {
            throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
        }
        if (RoleType.DEPT_ADMIN.name().equals(role.getRoleType())) {
            throw new ClientException("DEPT_ADMIN 不可分配 DEPT_ADMIN 角色");
        }
        if (role.getMaxSecurityLevel() != null && role.getMaxSecurityLevel() > currentCeiling) {
            throw new ClientException("不可分配超过自身安全等级上限的角色");
        }
    }
}
```

**调用点**：
- `checkCreateUserAccess()` — 创建用户带角色
- `checkAssignRolesAccess()` — 编辑用户分配角色

### 4.4 漏洞 F — deptId 变更 SUPER_ADMIN 独占

`UserServiceImpl.update()`：

```java
if (requestParam.getDeptId() != null
        && !requestParam.getDeptId().equals(existingUser.getDeptId())
        && !kbAccessService.isSuperAdmin()) {
    throw new ClientException("部门变更仅超级管理员可操作");
}
```

### 4.5 前端适配

#### 4.5.1 权限层

**`permissions.ts`**：
- `DEPT_VISIBLE` 加入 `"roles"`
- `canAssignRole` 改为：SUPER_ADMIN 可分配全部；DEPT_ADMIN 排除 `SUPER_ADMIN` + `DEPT_ADMIN` roleType

#### 4.5.2 路由

**`router.tsx`**：
- `/admin/roles` 从 `RequireSuperAdmin` 改为 `RequireAnyAdmin`

#### 4.5.3 角色管理页（`RoleListPage.tsx`）

**DEPT_ADMIN 视角**：
- 隐藏所有变更按钮（创建角色 / 编辑角色 / 删除角色 / KB 绑定编辑）
- 跳过 `GET /role/{roleId}/knowledge-bases` 请求（不展示 KB 绑定详情）
- 只展示角色基本信息：名称、类型、描述、max_security_level

**SUPER_ADMIN 视角**：不变。

#### 4.5.4 用户管理页（`UserListPage.tsx`）

- `GET /role` 和 `GET /sys-dept` 恢复可用后，创建/编辑弹窗自动工作
- 角色选择器已有 `canAssignRole` 过滤（更新后排除 DEPT_ADMIN 类型）
- DEPT_ADMIN 视角：deptId 字段设为只读 disabled

#### 4.5.5 知识库详情页 — 新增"共享管理" tab

- 展示/编辑该 KB 的角色绑定（角色名 + permission + maxSecurityLevel 下拉）
- 调用 KB-centric API：`GET /knowledge-base/{kbId}/role-bindings` + `PUT /knowledge-base/{kbId}/role-bindings`
- 需要角色列表做下拉选择，调用 `GET /role`

#### 4.5.6 SUPER_ADMIN 角色管理页 KB 绑定弹窗

Phase 1 已加 `maxSecurityLevel` 下拉，Phase 2 无额外改动。

---

## 五、验证矩阵

### Phase 1

| # | 场景 | 预期 |
|---|------|------|
| 1 | 用户有 FICC(kb=FICC,level=3) + OPS(kb=OPS,level=1)，访问 OPS 库 | 只看到 level<=1 |
| 2 | 同上用户访问 FICC 库 | 看到 level<=3 |
| 3 | SUPER_ADMIN 访问任意 KB | level=3 |
| 4 | DEPT_ADMIN 访问本部门 KB | level=自身角色天花板 |
| 5 | 缓存失效：修改绑定后 | 下次检索使用新值 |
| 6 | SUPER_ADMIN 在角色管理页设置 KB 绑定的 maxSecurityLevel | 值正确写入 |

### Phase 2

| # | 场景 | 预期 |
|---|------|------|
| 7 | DEPT_ADMIN 打开用户管理，创建新用户 | 弹窗正常加载：角色列表（排除 SUPER_ADMIN + DEPT_ADMIN）+ 部门名称展示 + deptId 锁定 |
| 8 | DEPT_ADMIN 编辑用户，查看已分配角色 | 正常显示已分配角色列表 |
| 9 | DEPT_ADMIN 创建用户时分配 DEPT_ADMIN 角色 | 后端拒绝（前端也不展示） |
| 10 | DEPT_ADMIN 创建用户时分配 ceiling=3 角色，自身 ceiling=2 | 后端拒绝 |
| 11 | DEPT_ADMIN 编辑用户时修改 deptId | 后端拒绝 + 前端 disabled |
| 12 | OPS DEPT_ADMIN 在 COB 库共享管理添加"FICC 分析师"(READ, level=1) | 成功 |
| 13 | OPS DEPT_ADMIN 打开 FICC 部门 KB 的共享管理 | 拒绝 |
| 14 | OPS DEPT_ADMIN 设 binding level=3，自身天花板=2 | 拒绝 |
| 15 | FICC 分析师(level=1)访问 COB 库 | 只看到 level<=1 |
| 16 | DEPT_ADMIN 访问 /admin/roles | 只读角色列表（无 KB 绑定详情，无变更按钮） |
| 17 | DEPT_ADMIN 访问 /admin/departments | 路由拒绝（保持 SUPER_ADMIN 独占） |

---

## 六、不在范围内

- `t_role` 加 `dept_id`（决策 2-A 明确拒绝）
- DEPT_ADMIN 创建/删除角色（保持 SUPER_ADMIN 独占）
- 系统角色/业务角色拆分（技术债）
- WRITE 权限独立校验
- 存量 chunk 回填 `security_level` metadata 到 OpenSearch
- 现有 role-centric API 改造（保持 SUPER_ADMIN 独占）
- 部门 CUD 操作开放给 DEPT_ADMIN（保持 SUPER_ADMIN 独占）
