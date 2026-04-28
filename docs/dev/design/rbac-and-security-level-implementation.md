# RBAC + security_level 实现设计笔记

> **范围**：把 `docs/dev/design/rag-permission-design.md` 的权限体系落地到当前代码库的分阶段实现路线。
>
> **前置文档**：`docs/dev/design/rag-permission-design.md`（权限设计原始规范）
>
> **状态**：PR0 已完成；PR1 / PR3 待实施

---

## 概述

本文档是权限体系设计规范（`rag-permission-design.md`）在当前代码库中的**实现路线图 + 决策记录**。
它不重新定义权限模型，只回答"如何把已定义的模型落到当前代码里"。

核心目标：
1. 立刻堵住已发现的代码层越权漏洞（PR0 紧急热修复）
2. 引入 `SUPER_ADMIN / DEPT_ADMIN / USER` 角色体系和 `security_level` 字段（PR1）
3. 清理历史包袱（`"admin".equals` 散落、单角色字符串），前端管理页对接（PR3）

**开发阶段环境假设**：当前处于开发试用期，允许**完全删除数据库 + OpenSearch 索引 + S3 桶 + Redis 后重建**。
因此本设计**不包含任何数据迁移脚本、存量 reindex、前向兼容适配逻辑**。

---

## 一、关键决策记录

### 决策 1 — PR0 chunk 接口锁法：类级 `@SaCheckRole("admin")`（选项 1-A）

**选择**：在 `KnowledgeChunkController` 类上加一行类级注解，6 个方法一起被守护。

**理由**：
- 当前前端的 chunk 管理 UI 路径是 `/admin/knowledge/:kbId/docs/:docId`（见 `frontend/src/router.tsx`），被 `RequireAdmin` 守护 —— 普通用户本来就进不去这些接口
- 改动最小（1 行），无需注入新依赖到 ChunkController
- 过渡期副作用：DEPT_ADMIN 落地后也会被该注解挡在外面 —— 但 PR1 在 DEPT_ADMIN 落地之前，所以过渡期内不会有实际影响
- PR3 时把 `"admin"` 字符串换成基于部门的判定即可

**拒绝的方案 1-B**（方法级 `checkDocAccess`）：
- 需要给 ChunkController 注入 `KbAccessService` + `KnowledgeDocumentService` 两个新依赖
- 要改 6 处方法签名
- 对当前阶段没有收益 —— chunk 管理始终是管理员行为

---

### 决策 2 — `t_role` 不加 `dept_id`，角色全局化（选项 2-A）

**选择**：`t_role` 表保持只有 `name, description, role_type, max_security_level` 这些业务字段，**不**加 `dept_id` 列。

**理由**：
- **DEPT_ADMIN 的权限边界由 `knowledge_base.dept_id` 定义，不是由角色的 `dept_id` 定义**。
  用户原话："DEPT_ADMIN 只能修改自己 DEPT 的知识库" —— 约束的是 **KB 侧**，不是角色侧
- 跨部门共享在企业内是**常态**（HR 库、合规库、全公司制度库）。如果角色按部门隔离，每次跨部门共享都要 SUPER_ADMIN 审批，流程瓶颈
- 真正敏感的文档靠**纵向 `security_level`** 防护 —— 这是设计文档里两层过滤机制的原意。横向角色只决定"谁能进这个 KB"，细粒度内容可见性由安全等级决定
- Schema 最简单：`t_role` 无需加列

**具体的权限矩阵**：

| 操作 | SUPER_ADMIN | DEPT_ADMIN | USER |
|---|---|---|---|
| **创建 KB** | ✅ `dept_id` 由请求参数指定 | ✅ `dept_id` 强制 = `self.dept_id` | ❌ |
| **更新/删除 KB** | ✅ 任意 KB | ✅ 仅当 `kb.dept_id == self.dept_id` | ❌ |
| **文档 CRUD** | ✅ 任意 KB 的文档 | ✅ 本部门 KB 的文档 | ❌ |
| **Chunk CRUD** | ✅ 任意 KB 的 chunk | ✅ 本部门 KB 的 chunk | ❌ |
| **设置 role-KB 授权** | ✅ 任意 KB ↔ 任意角色 | ✅ 本部门 KB ↔ 任意（全局）角色 | ❌ |
| **角色 CRUD（创建/删除角色本身）** | ✅ | ❌ | ❌ |
| **用户管理** | ✅ | ❌ | ❌ |
| **问答查询** | ✅ 全部（绕过 RBAC） | ✅ 走 RBAC（被授权的 KB） | ✅ 走 RBAC |

**跨部门共享如何实现（场景验证）**：

> OPS DEPT_ADMIN 想把"COB 合规库"开放给 FICC 分析师读。

1. OPS DEPT_ADMIN 拥有 COB 库（`kb.dept_id = OPS`）的管理权
2. OPS DEPT_ADMIN 在角色管理页选择 **已有的全局角色**"FICC 分析师"
3. 把这个角色关联到 COB 库（READ 权限）
4. FICC 分析师用户通过他们在"FICC 分析师"角色里的成员身份访问 COB 库
5. OPS DEPT_ADMIN **不能**反过来去 FICC 的知识库里搞事 —— 因为 FICC 的 KB `dept_id = FICC != OPS`

关键安全性质：**"我能把自己的库开放给别人" ≠ "我能去别人家搞事"**。这个不对称性是 2-A 方案的核心。

#### 2-A 的隐式运营假设（必须显式承认）

2-A 方案的跨部门共享**依赖一个先决条件**：要共享给的"目标角色"（如"FICC 分析师"）**必须在系统里已经存在**。
但角色 CRUD 在 2-A 里被限定为 **SUPER_ADMIN 独占权**（DEPT_ADMIN 不能创建/删除角色）。

这意味着：
- **新增部门上线时**：SUPER_ADMIN 必须先创建该部门的默认角色（员工/主管等），DEPT_ADMIN 之后才能用
- **新增岗位**：SUPER_ADMIN 必须先创建岗位角色，DEPT_ADMIN 才能把它挂到 KB 上
- **DEPT_ADMIN 不能自主细分角色**：如果 FICC 想把"FICC 分析师"细分成"FICC 股票分析师"和"FICC 债券分析师"，必须找 SUPER_ADMIN

**适用范围**：O(10) 级部门数、O(100) 级角色数的组织规模下，这是可接受的运维模式（角色是相对稳定的组织实体，SUPER_ADMIN 作为看门人不会成为瓶颈）。

**升级路径**：如果未来角色爆炸式增长，或 SUPER_ADMIN 确实成为瓶颈，可以升级为 2-B 方案 —— `t_role` 加 `dept_id`，DEPT_ADMIN 拥有本部门角色 CRUD 权，但失去部分跨部门共享的便利。这是显式的技术债，**不是设计漏洞**。

---

### 决策 3 — 3 PR 合并方案（走法 1）

合并原计划的 PR1 / PR2.0 / PR2.1 到**一个大 PR1**，因为：
- 开发环境可以整体删库重建，不需要分步上线
- `security_level` 字段和 RBAC 骨架都是数据层/检索层的**加法**操作，改动隔离性好
- 合并后的 PR 对应一次完整的"停机 → 重建 → 重启"，便于测试

---

## 二、三 PR 路线图

| PR | 名称 | 主要内容 | 状态 |
|---|---|---|---|
| **PR0** | 权限洞热修复 | `@SaCheckRole("admin")` + 空集短路 + getChunkLogs 补 checkDocAccess | ✅ 已完成 |
| **PR1** | RBAC 骨架 + security_level 流水线 | Schema/实体/LoginUser/KbAccessService/OpenSearch mapping/IndexerNode/检索 filter 全部一次落地 | ⏳ 待启动 |
| **PR3** | 清理 + DEPT_ADMIN 落地 + UI | Controller `"admin".equals` 收敛；DEPT_ADMIN 权限判定（写接口从注解降级到编程式 `checkManageAccess`）；前端管理页 | ⏳ 待 PR1 完成 |

**PR 间依赖**：PR1 依赖 PR0（hotfix 先堵洞），PR3 依赖 PR1（角色体系就位后才能 cleanup）。

---

## 三、PR0 — 热修复（已完成）

### 3.1 改动清单

**改动文件**：5 个
**总 Edit 次数**：10 处

| # | 文件 | 改动 | 类型 |
|---|---|---|---|
| 1 | `KnowledgeBaseController.java` | 增加 `import cn.dev33.satoken.annotation.SaCheckRole;` | import |
| 2 | `KnowledgeBaseController.java:60` | `createKnowledgeBase` 加 `@SaCheckRole("admin")` | 注解 |
| 3 | `KnowledgeBaseController.java:69` | `renameKnowledgeBase` 加 `@SaCheckRole("admin")` | 注解 |
| 4 | `KnowledgeBaseController.java:80` | `deleteKnowledgeBase` 加 `@SaCheckRole("admin")` | 注解 |
| 5 | `KnowledgeChunkController.java` | 增加 `import cn.dev33.satoken.annotation.SaCheckRole;` | import |
| 6 | `KnowledgeChunkController.java:48` | 类级 `@SaCheckRole("admin")` | 注解（覆盖 6 方法）|
| 7 | `KnowledgeDocumentController.java:165` | `getChunkLogs` 加 `checkDocAccess(docId)` | 方法调用 |
| 8 | `KnowledgeBaseServiceImpl.java:54` | 增加 `import java.util.Set;` | import |
| 9 | `KnowledgeBaseServiceImpl.java:220` | `pageQuery` 加 empty-set 短路返回空页 | 逻辑 |
| 10 | `KnowledgeDocumentServiceImpl.java:554` | `search` 加 empty-set 短路返回空列表 | 逻辑 |

### 3.2 已修复的问题

**权限洞（改动 1-7）**：
- 之前：任何已登录用户都可创建/重命名/删除知识库；知道 docId 就可读写 chunk 内容或查看分块日志
- 之后：上述接口都要求 Sa-Token 角色 = `admin`

**Fail-open 漏洞（改动 8-10）**：
- 之前：MyBatis-Plus `.in(condition, ...)` 的 condition 为 false 时 `.in()` 子句被完全跳过，导致"非 admin 用户 + 无任何可访问 KB"时查询返回所有记录
- 之后：`accessibleKbIds` 为空集合时显式短路返回空结果；非空时执行 `.in()` 过滤

### 3.3 PR0 的后向不兼容点（PR1 时要处理）

`@SaCheckRole("admin")` 匹配的是 Sa-Token 角色列表里的字符串 `"admin"`。当 PR1 把角色体系改成 `SUPER_ADMIN / DEPT_ADMIN / USER` 后：

- `SaTokenStpInterfaceImpl.getRoleList()` 将返回 `["SUPER_ADMIN"]` 而不是 `["admin"]`
- 上面这些 `@SaCheckRole("admin")` 会**全部失效**

**PR1 的处理方案**：PR1 里把所有 `@SaCheckRole("admin")` 字符串替换为 `@SaCheckRole("SUPER_ADMIN")`。
`UserController.java` 和 `RoleController.java` 的 `"admin"` 字符串（12 处）也要一起替换。

**已跳过的测试**：经用户授权跳过 TDD。现有测试基础设施不支持 MockMvc / Sa-Token mock，搭建成本远超 hotfix 本身。
PR0 的验证方式是视觉 diff 核对 + `mvn compile` 编译检查。
**follow-up task #9**：建立 MockMvc + Sa-Token 测试基础设施作为独立 PR，之后回填 PR0 测试。

---

## 四、PR1 — 骨架 + 检索链路激活（主要工作）

### 4.1 Schema 变更

#### 新增表

**`sys_dept`** —— 部门表：

```sql
CREATE TABLE sys_dept (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    dept_code   VARCHAR(32)  NOT NULL,
    dept_name   VARCHAR(64)  NOT NULL,
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     DEFAULT 0,
    CONSTRAINT uk_dept_code UNIQUE (dept_code)
);
COMMENT ON TABLE sys_dept IS '部门表';
```

#### 扩列

**`t_user`**：
```sql
ALTER TABLE t_user ADD COLUMN dept_id VARCHAR(20);
COMMENT ON COLUMN t_user.dept_id IS '所属部门ID';
-- role 列保留（Sa-Token getRoleList 仍用），但值改为 SUPER_ADMIN/DEPT_ADMIN/USER
```

**`t_role`**：
```sql
ALTER TABLE t_role ADD COLUMN role_type VARCHAR(32) NOT NULL DEFAULT 'USER';
ALTER TABLE t_role ADD COLUMN max_security_level SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN t_role.role_type IS 'SUPER_ADMIN/DEPT_ADMIN/USER';
COMMENT ON COLUMN t_role.max_security_level IS '该角色可访问的最高安全等级（0-3）';
```

**`t_role_kb_relation`**：
```sql
ALTER TABLE t_role_kb_relation ADD COLUMN permission VARCHAR(16) NOT NULL DEFAULT 'READ';
COMMENT ON COLUMN t_role_kb_relation.permission IS 'READ/WRITE/MANAGE';
```

**`t_knowledge_base`**：
```sql
ALTER TABLE t_knowledge_base ADD COLUMN dept_id VARCHAR(20) NOT NULL DEFAULT 'GLOBAL';
COMMENT ON COLUMN t_knowledge_base.dept_id IS '归属部门ID（决定哪个 DEPT_ADMIN 能管理此知识库）';
```

**`t_knowledge_document`**：
```sql
ALTER TABLE t_knowledge_document ADD COLUMN security_level SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN t_knowledge_document.security_level IS '文档安全等级：0=PUBLIC, 1=INTERNAL, 2=CONFIDENTIAL, 3=RESTRICTED';
```

#### 注意事项

- **两份 schema 文件都要改**：`resources/database/schema_pg.sql`（干净 DDL）和 `resources/database/full_schema_pg.sql`（pg_dump 格式）。这是 CLAUDE.md 里点出的踩坑点
- **没有 upgrade 脚本**：开发环境重建 —— 直接 drop 后重跑 `schema_pg.sql`
- **GLOBAL 部门**：`init_data_pg.sql` 里插入一个 `dept_id='GLOBAL'` 的默认部门记录，给存量 KB 兜底

### 4.2 领域实体变更

#### 新增枚举（在 framework 模块）

`framework/src/main/java/com/nageoffer/ai/ragent/framework/context/RoleType.java`：
```java
public enum RoleType {
    SUPER_ADMIN,
    DEPT_ADMIN,
    USER
}
```

`framework/src/main/java/com/nageoffer/ai/ragent/framework/context/Permission.java`：
```java
public enum Permission {
    READ,
    WRITE,
    MANAGE
}
```

**放在 framework 模块的原因**：`LoginUser` 在 framework 模块，引用 RoleType 需要 RoleType 可见。
不能放在 bootstrap（framework 不能反向依赖 bootstrap，见 framework/CLAUDE.md Gotchas）。

#### 修改 `LoginUser`（framework 模块）

```java
@Data
@Builder
public class LoginUser {
    private String userId;
    private String username;
    private String role;              // legacy 单角色字符串，PR3 删除
    private String avatar;
    // --- 新增字段 ---
    private String deptId;
    private Set<RoleType> roleTypes;  // 支持多角色（一个用户挂多个角色时取并集）
    private int maxSecurityLevel;     // 跨所有角色取最大值
}
```

#### 修改 `KnowledgeDocumentDO`

```java
// ...existing fields...
private Integer securityLevel;  // 0-3
```

#### 修改 `KnowledgeBaseDO`

```java
// ...existing fields...
private String deptId;
```

#### 修改 `RoleDO`

```java
// ...existing fields...
private String roleType;         // SUPER_ADMIN/DEPT_ADMIN/USER
private Integer maxSecurityLevel;
```

#### 修改 `RoleKbRelationDO`

```java
// ...existing fields...
private String permission;  // READ/WRITE/MANAGE
```

#### 新增 `SysDeptDO`（user 域）

标准 MyBatis-Plus 实体，不赘述。

### 4.3 `UserContextInterceptor` 一次性装载多角色

当前实现在请求进入时只读取 `user.getRole()` 单字符串。PR1 需要改为联表查询：

```java
// UserContextInterceptor.preHandle 改动：
String loginId = StpUtil.getLoginIdAsString();
UserDO user = userMapper.selectById(loginId);

// 新增：装载多角色 + 最大安全等级
List<RoleDO> roles = roleMapper.selectRolesByUserId(loginId);
Set<RoleType> roleTypes = roles.stream()
    .map(r -> RoleType.valueOf(r.getRoleType()))
    .collect(Collectors.toSet());
int maxSecurityLevel = roles.stream()
    .mapToInt(r -> r.getMaxSecurityLevel() == null ? 0 : r.getMaxSecurityLevel())
    .max()
    .orElse(0);

UserContext.set(LoginUser.builder()
    .userId(user.getId().toString())
    .username(user.getUsername())
    .role(user.getRole())        // legacy
    .avatar(...)
    .deptId(user.getDeptId())
    .roleTypes(roleTypes)
    .maxSecurityLevel(maxSecurityLevel)
    .build());
```

**注意**：`roleMapper.selectRolesByUserId` 是新 SQL（join `t_user_role` 和 `t_role`），需要在 `RoleMapper.xml` 或 MP 注解里写。

### 4.4 `KbAccessService` 接口升级

当前接口：
```java
Set<String> getAccessibleKbIds(String userId);  // 注释说 admin 返回全量，但实现里没有这个分支 —— 接口/实现不一致的历史 bug
void checkAccess(String kbId);
void evictCache(String userId);
```

升级为：
```java
// 按权限级别筛选
Set<String> getAccessibleKbIds(String userId, Permission minPermission);

// 兼容旧签名（等价于 getAccessibleKbIds(userId, READ)）
default Set<String> getAccessibleKbIds(String userId) {
    return getAccessibleKbIds(userId, Permission.READ);
}

// 新增：获取用户最大安全等级（跨所有角色取最大）
int getMaxSecurityLevel(String userId);

// 新增：DEPT_ADMIN 管理边界校验（校验 kb.dept_id == user.dept_id 或 SUPER_ADMIN 绕过）
void checkManageAccess(String kbId);

// checkAccess 语义升级：SUPER_ADMIN 直接放行（不再依赖 controller 层手动判断）
void checkAccess(String kbId);
```

**实现变化**：
- `checkAccess`：先检查 `UserContext.roleTypes.contains(SUPER_ADMIN)`，是则直接放行；否则走 user→roles→kb_relations 链路
- `getAccessibleKbIds`：SUPER_ADMIN 返回**全量 KB**（这个分支本来注释说有但实现里没有，PR1 补上）
- `checkManageAccess`：
  - SUPER_ADMIN → 放行
  - DEPT_ADMIN → 查 `kb.dept_id == user.dept_id`，不匹配抛 `ClientException`
  - 普通 USER → 抛 `ClientException`

### 4.5 Sa-Token 集成适配

`SaTokenStpInterfaceImpl.getRoleList()` 当前返回 `List.of(user.getRole())`（单角色字符串）。PR1 改为：

```java
@Override
public List<String> getRoleList(Object loginId, String loginType) {
    if (loginId == null) return Collections.emptyList();
    String loginIdStr = loginId.toString();
    if (!StrUtil.isNumeric(loginIdStr)) return Collections.emptyList();

    // 联表查 t_user_role → t_role → role_type
    List<String> roleTypes = roleMapper.selectRoleTypesByUserId(loginIdStr);
    return roleTypes == null ? Collections.emptyList() : roleTypes;
}
```

这样 `@SaCheckRole("SUPER_ADMIN")` 注解才能正确匹配。

#### 4.5.1 `"admin"` → `"SUPER_ADMIN"` 字符串替换清单

PR1 必须同步替换下列 **16 处**硬编码 `"admin"` 字符串。遗漏任何一处都会导致 SUPER_ADMIN 登录后被对应接口的角色校验拒绝（因为 `getRoleList()` 返回的是 `["SUPER_ADMIN"]`）。

| # | 文件 | 行号（PR0 后） | 原代码 | 替换为 |
|---|---|---|---|---|
| 1 | `KnowledgeBaseController.java` | 60 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 2 | `KnowledgeBaseController.java` | 69 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 3 | `KnowledgeBaseController.java` | 80 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 4 | `KnowledgeChunkController.java` | 48 | `@SaCheckRole("admin")` *（类级）* | `@SaCheckRole("SUPER_ADMIN")` |
| 5 | `RoleController.java` | 37 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 6 | `RoleController.java` | 44 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 7 | `RoleController.java` | 51 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 8 | `RoleController.java` | 58 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 9 | `RoleController.java` | 64 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 10 | `RoleController.java` | 72 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 11 | `RoleController.java` | 78 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 12 | `RoleController.java` | 85 | `@SaCheckRole("admin")` | `@SaCheckRole("SUPER_ADMIN")` |
| 13 | `UserController.java` | 71 | `StpUtil.checkRole("admin");` | `StpUtil.checkRole("SUPER_ADMIN");` |
| 14 | `UserController.java` | 80 | `StpUtil.checkRole("admin");` | `StpUtil.checkRole("SUPER_ADMIN");` |
| 15 | `UserController.java` | 89 | `StpUtil.checkRole("admin");` | `StpUtil.checkRole("SUPER_ADMIN");` |
| 16 | `UserController.java` | 99 | `StpUtil.checkRole("admin");` | `StpUtil.checkRole("SUPER_ADMIN");` |

**执行建议**：PR1 开发时先跑一次 grep 重新确认行号（PR1 其他改动可能会偏移行号）：
```bash
grep -rn '@SaCheckRole("admin")' bootstrap/src/main/java/
grep -rn 'StpUtil\.checkRole("admin")' bootstrap/src/main/java/
```
预期命中 16 处，全部替换完后再 grep 一次确认归零。

### 4.6 OpenSearch mapping 变更

`OpenSearchVectorStoreAdmin.buildMappingJson` 的 metadata properties 块：

```java
"metadata": {
  "dynamic": false,
  "properties": {
    "doc_id":          { "type": "keyword" },
    "chunk_index":     { "type": "integer" },
    "task_id":         { "type": "keyword", "index": false },
    "pipeline_id":     { "type": "keyword" },
    "source_type":     { "type": "keyword" },
    "source_location": { "type": "keyword", "index": false },
    "security_level":  { "type": "integer" },   // ← 新增
    "keywords": { ... },
    "summary": { ... }
  }
}
```

**为什么必须显式加到 mapping**：当前 `metadata.dynamic = false`，写入文档时任何未在 properties 里声明的字段会被**静默丢弃**（字段进入 `_source` 但不建索引）。`range` 过滤对未索引字段的行为未定义，可能返回全部或全空 —— 这是该改造里最大的安全陷阱。

### 4.7 IndexerNode 改动

当前 `IndexerNode` 写 metadata 时只传 doc_id / chunk_index / task_id / pipeline_id / source_type / source_location 等。PR1 改为额外注入 `security_level`：

```java
// IndexerNode 改动（示意）
Map<String, Object> metadata = new HashMap<>();
metadata.put("doc_id", ctx.getDocId());
metadata.put("chunk_index", chunk.getIndex());
// ... existing fields ...
metadata.put("security_level", ctx.getDocument().getSecurityLevel());  // ← 新增
```

### 4.8 检索链路 metadataFilters 流水线激活

**关键发现**：当前 `RetrieveRequest.metadataFilters` 字段是**完全未被任何上游写入的死字段** ——
`OpenSearchRetrieverService.buildFilterClause` 读它，但上游 4 个调用点（`MultiChannelRetrievalEngine:83` / `IntentParallelRetriever:70` / `CollectionParallelRetriever:46` / `RetrieverService:63`）都没有设置它。

PR1 要**激活这条流水线**：

#### 4.8.1 SearchContext 加字段

```java
// SearchContext 新增字段
private Integer maxSecurityLevel;   // 非 null 才参与过滤
```

#### 4.8.2 MultiChannelRetrievalEngine.buildSearchContext() 改动

```java
SearchContext.builder()
    // ...existing...
    .accessibleKbIds(accessibleKbIds)
    .maxSecurityLevel(resolveMaxSecurityLevel())
    .build();

// helper：
private Integer resolveMaxSecurityLevel() {
    // 有用户态（普通 HTTP 请求）→ 用用户的最大等级
    if (UserContext.hasUser()) {
        return UserContext.get().getMaxSecurityLevel();
    }
    // 无用户态（MQ 消费者、定时任务等系统调用）→ 返回 null
    // null 在下游过滤链路里的语义是"不加 security_level 过滤" —— 系统调用
    // 因此天然"全通"。未来扩充 security_level 到 level 4/5 时不用改这里。
    return null;
}
```

**重要**：必须在**请求线程**里取 `UserContext.maxSecurityLevel`，不能在 `StreamCallback` 里懒取（CLAUDE.md 里点过这个坑：`RagTraceContext ThreadLocal cleared early`）。

#### 4.8.3 引入类型化的 `MetadataFilter`

**不用字符串后缀约定（如 `security_level_lte`）**，因为如果将来有字段名本身就以 `_lte` 结尾（比如 `estimated_lte`），会被误识别为 range 操作。改用类型化的 record + 枚举：

```java
// bootstrap/.../rag/core/retrieve/MetadataFilter.java
package com.knowledgebase.ai.ragent.rag.core.retrieve;

public record MetadataFilter(String field, FilterOp op, Object value) {
    public enum FilterOp { EQ, LTE, GTE, LT, GT, IN }
}
```

同时把 `RetrieveRequest.metadataFilters` 的类型从 `Map<String, Object>` 改成 `List<MetadataFilter>`：

```java
// RetrieveRequest.java
private List<MetadataFilter> metadataFilters;
```

这是一个破坏性改动，但当前没有任何调用者设置过这个字段（死字段），所以零兼容成本。

同样的，`SearchContext` / 下游所有 retriever 的参数类型都改为 `List<MetadataFilter>`。

#### 4.8.4 MultiChannelRetrievalEngine 单 KB 定向检索路径改动

```java
// MultiChannelRetrievalEngine.retrieveKnowledgeChannels 中：
RetrieveRequest req = RetrieveRequest.builder()
    .query(context.getMainQuestion())
    .topK(topK)
    .collectionName(kb.getCollectionName())
    .metadataFilters(buildMetadataFilters(context))   // ← 新增
    .build();
```

其中 `buildMetadataFilters(ctx)`：
```java
private List<MetadataFilter> buildMetadataFilters(SearchContext ctx) {
    List<MetadataFilter> filters = new ArrayList<>();
    if (ctx.getMaxSecurityLevel() != null) {
        filters.add(new MetadataFilter(
            "security_level",
            MetadataFilter.FilterOp.LTE,
            ctx.getMaxSecurityLevel()
        ));
    }
    return filters;
}
```

同样的 `.metadataFilters(...)` 注入也要加到 `IntentParallelRetriever.createRetrievalTask` 和 `CollectionParallelRetriever.createRetrievalTask`。

#### 4.8.5 OpenSearchRetrieverService.buildFilterClause 重写

```java
private String buildFilterClause(List<MetadataFilter> metadataFilters) {
    if (metadataFilters == null || metadataFilters.isEmpty()) {
        return "";
    }
    return metadataFilters.stream()
        .map(this::renderFilter)
        .collect(Collectors.joining(", "));
}

private String renderFilter(MetadataFilter f) {
    String path = "metadata." + escapeJson(f.field());
    return switch (f.op()) {
        case EQ  -> """
            { "term": { "%s": %s } }""".formatted(path, jsonValue(f.value()));
        case LTE -> """
            { "range": { "%s": { "lte": %s } } }""".formatted(path, jsonValue(f.value()));
        case GTE -> """
            { "range": { "%s": { "gte": %s } } }""".formatted(path, jsonValue(f.value()));
        case LT  -> """
            { "range": { "%s": { "lt":  %s } } }""".formatted(path, jsonValue(f.value()));
        case GT  -> """
            { "range": { "%s": { "gt":  %s } } }""".formatted(path, jsonValue(f.value()));
        case IN  -> """
            { "terms": { "%s": %s } }""".formatted(path, jsonArray(f.value()));
    };
}

/** 数字/布尔不加引号，字符串加引号 —— OpenSearch 对类型敏感 */
private String jsonValue(Object v) {
    if (v == null) return "null";
    if (v instanceof Number || v instanceof Boolean) return v.toString();
    return "\"" + escapeJson(v.toString()) + "\"";
}

private String jsonArray(Object v) {
    if (!(v instanceof java.util.Collection<?> c)) {
        throw new IllegalArgumentException("IN filter expects Collection, got " + v);
    }
    return c.stream()
        .map(this::jsonValue)
        .collect(Collectors.joining(", ", "[", "]"));
}
```

**关键点**：类型分发在 `switch (f.op())` 里做一次，`jsonValue()` 集中处理数字 vs 字符串的引号差异。term 和 range 的引号差异不再是"开发者要记得写对的约定"，而是类型系统保证的不变量。

### 4.9 API 变更

#### 文档上传接口 `POST /knowledge-base/{kb-id}/docs/upload`

`KnowledgeDocumentUploadRequest` 加字段：
```java
private Integer securityLevel;  // 0-3，默认 0
```

Controller 传递到 service 层，service 写入 `t_knowledge_document.security_level`。

#### 文档更新接口 `PUT /knowledge-base/docs/{docId}`

`KnowledgeDocumentUpdateRequest` 加 `securityLevel` 字段。

**核心问题**：修改 `security_level` 后需要刷新该文档所有 chunk 的 OpenSearch metadata（chunk metadata 是 document 级字段的副本）。但 PG 和 OpenSearch 之间**不存在分布式事务**。

**决策：走 RocketMQ 异步刷新模式**（和现有 `KnowledgeDocumentChunkConsumer` 同构）。

##### 设计细节

**1. 新消息类型** `SecurityLevelRefreshEvent`：
```java
public record SecurityLevelRefreshEvent(
    String docId,
    String collectionName,
    int newSecurityLevel
) {}
```

**2. 新 topic / tag**：`knowledge_document_topic` 下的 `security_level_refresh` tag（复用现有 topic 省配置）。

**3. Service 层（PG 写 + 事务消息）**：
```java
// KnowledgeDocumentServiceImpl.updateSecurityLevel
@Transactional
public void updateSecurityLevel(String docId, int newLevel) {
    KnowledgeDocumentDO doc = documentMapper.selectById(docId);
    Assert.notNull(doc, () -> new ClientException("文档不存在"));
    KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(doc.getKbId());

    // Step 1: PG 写入（事务内）
    doc.setSecurityLevel(newLevel);
    doc.setUpdatedBy(UserContext.getUsername());
    documentMapper.updateById(doc);

    // Step 2: 发送 RocketMQ 事务消息
    // 事务消息保证 "PG 提交 + MQ 发送" 原子性 —— 要么都成功要么都失败
    SecurityLevelRefreshEvent event = new SecurityLevelRefreshEvent(
        docId, kb.getCollectionName(), newLevel);
    rocketMQProducer.sendTransactionalMessage(
        "knowledge_document_topic",
        "security_level_refresh",
        event);
}
```

**4. 消费者**：
```java
// KnowledgeDocumentSecurityLevelRefreshConsumer
@RocketMQMessageListener(
    topic = "knowledge_document_topic",
    selectorExpression = "security_level_refresh",
    consumerGroup = "security_level_refresh_group"
)
public class KnowledgeDocumentSecurityLevelRefreshConsumer
        implements RocketMQListener<SecurityLevelRefreshEvent> {

    @Override
    public void onMessage(SecurityLevelRefreshEvent event) {
        // 调用 vectorStoreService 执行 _update_by_query
        vectorStoreService.updateChunksMetadata(
            event.collectionName(),
            event.docId(),
            Map.of("security_level", event.newSecurityLevel())
        );
        // 异常会触发 RocketMQ 标准重试（默认 16 次，指数退避），
        // 最终失败进 DLQ，由告警/人工介入
    }
}
```

**5. `VectorStoreService.updateChunksMetadata`** 新接口：
```java
/**
 * 更新指定文档的所有 chunk 在向量库里的 metadata 字段（不动 vector）。
 * OpenSearch 实现：走 POST {collection}/_update_by_query 带 Painless script。
 * Milvus/pg 实现：PR1 只抛 UnsupportedOperationException，后续 PR 补全。
 */
void updateChunksMetadata(String collectionName, String docId, Map<String, Object> fields);
```

**6. OpenSearch 实现示意**：
```java
// OpenSearchVectorStoreService.updateChunksMetadata
String script = """
    {
      "script": {
        "source": "for (entry in params.fields.entrySet()) { ctx._source.metadata[entry.getKey()] = entry.getValue(); }",
        "params": { "fields": { "security_level": %d } }
      },
      "query": { "term": { "metadata.doc_id": "%s" } }
    }
    """.formatted(newLevel, escapeJson(docId));
client.generic().execute(Requests.builder()
    .method("POST")
    .endpoint(collectionName + "/_update_by_query")
    .json(script)
    .build());
```

**7. API 响应**：
```json
{
  "code": 0,
  "data": null,
  "message": "安全等级变更已提交，索引同步可能有秒级延迟"
}
```

##### 权衡与备注

- **为什么不走同步刷新**：OpenSearch 暂时宕机时，同步模式会让整个文档更新接口 500；异步模式允许 PG 先成功、OS 后续补齐
- **一致性窗口**：用户点"保存"后到 OpenSearch 生效之间，通常 < 2 秒。这个窗口内老 `security_level` 仍在检索过滤生效，不会有"既允许又不允许"的诡异中间态
- **`sendTransactionalMessage` 的实现**：复用 `framework` 模块现有的 RocketMQ 事务消息模板 + `KnowledgeDocumentChunkTransactionChecker` 同模式的本地事务检查器
- **失败 DLQ 的处置**：作为 PR1 外的 follow-up，接告警面板即可（task 池加一条）

#### 知识库创建接口 `POST /knowledge-base`

`KnowledgeBaseCreateRequest` 加字段：
```java
private String deptId;  // SUPER_ADMIN 可指定；DEPT_ADMIN 被强制设为 self.dept_id
```

Service 层做校验：
- `currentUser.roleTypes.contains(SUPER_ADMIN)` → `kb.dept_id = requestParam.deptId`
- `currentUser.roleTypes.contains(DEPT_ADMIN)` → `kb.dept_id = currentUser.deptId`（**忽略请求参数**）

### 4.10 初始化数据

`init_data_pg.sql` 改动：
```sql
-- 默认部门
INSERT INTO sys_dept (id, dept_code, dept_name) VALUES ('1', 'GLOBAL', '全局部门');

-- 默认角色
INSERT INTO t_role (id, name, role_type, max_security_level, description) VALUES
    ('1', '超级管理员', 'SUPER_ADMIN', 3, '系统超级管理员'),
    ('2', '普通用户', 'USER', 0, '默认普通用户角色');

-- admin 用户（保留，role 字段只是 legacy 兼容）
INSERT INTO t_user (id, username, password, role, dept_id) VALUES
    ('1', 'admin', '123456', 'SUPER_ADMIN', '1');

-- admin 用户与超级管理员角色关联
INSERT INTO t_user_role (id, user_id, role_id) VALUES ('1', '1', '1');
```

---

## 五、PR3 — 清理 + UI + DEPT_ADMIN 落地

### 5.1 Controller 层 `"admin".equals` 收敛

**现状**（5 处业务代码命中 —— 来自 code review）：
- `KbAccessServiceImpl.java:110`
- `RAGChatServiceImpl.java:114`
- `KnowledgeBaseController.java:98`
- `KnowledgeDocumentController.java:135`
- `SpacesController.java:54`

**改造**：

| 位置 | 语义 | 改造后 |
|---|---|---|
| `KbAccessServiceImpl:110` | 鉴权放行 | `UserContext.get().getRoleTypes().contains(RoleType.SUPER_ADMIN)` |
| `RAGChatServiceImpl:114` | 检索时绕过 RBAC 过滤 | 同上 |
| `KnowledgeBaseController:98`、`KnowledgeDocumentController:135`、`SpacesController:54` | 列表展示"是否全部" | 新增 `kbAccessService.isSuperAdmin()` helper |

**重要**：这 5 处的语义不一样（鉴权 vs 展示），收敛时不能全用同一个 API。
- 鉴权用：`kbAccessService.checkAccess(kbId)`（已收敛到 service 内部）
- 展示用：`kbAccessService.isSuperAdmin()` 或 `kbAccessService.getAccessibleKbIds(null)` 返回全量

### 5.2 DEPT_ADMIN 权限判定

PR3 新增的核心逻辑：写接口不再是简单的"SUPER_ADMIN 放行"，而是"SUPER_ADMIN 或 owning-dept 的 DEPT_ADMIN 放行"。

`KbAccessService.checkManageAccess(String kbId)` 被所有写接口调用：

```java
// KnowledgeBaseController / KnowledgeChunkController / KnowledgeDocumentController 的所有写方法
@PostMapping("/knowledge-base/{kb-id}/...")
public Result<...> someWriteOp(@PathVariable String kbId, ...) {
    kbAccessService.checkManageAccess(kbId);  // 抛 ClientException 如果无权
    // ... existing logic
}
```

**同时移除 PR0 的 `@SaCheckRole` 注解** —— 注解不够表达"SUPER_ADMIN 或 DEPT_ADMIN（需校验部门）"的复合条件。

### 5.3 前端管理页改造

- **用户管理页**：加 `dept_id` 字段；用户详情页显示所属角色列表
- **角色管理页**：
  - 列表加 `role_type`、`max_security_level` 列
  - 编辑弹窗加下拉选择器
- **角色-知识库关联**：加 `permission` 下拉（READ / WRITE / MANAGE）
- **知识库创建/详情页**：加 `dept_id` 选择（SUPER_ADMIN 可选，DEPT_ADMIN 锁定）
- **文档上传/详情页**：加 `security_level` 下拉
- **新增**：`部门管理页`（`/admin/departments`），SUPER_ADMIN 可见

### 5.4 删除 legacy `role` 字段

- `LoginUser.role`（单字符串）标记 `@Deprecated`，PR3 内彻底移除
- `t_user.role` 列保留给 Sa-Token 兼容层用（`getRoleList` 走 `t_user_role` 新逻辑），后续可考虑删除
- 前端 `authStore.user.role` → `authStore.user.roleTypes: string[]`
- `router.tsx` 的 `RequireAdmin` → `RequireSuperAdmin`，检查 `roleTypes.includes('SUPER_ADMIN')`

---

## 六、开发环境重建 checklist

PR1 完成后，执行以下步骤整体重建：

### 6.1 停服

```bash
# 停 Spring Boot
Ctrl+C  # (假设 mvn spring-boot:run 前台运行)
```

### 6.2 清理数据

```bash
# 1. PostgreSQL
docker exec postgres psql -U postgres -c "DROP DATABASE ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql

# 2. OpenSearch
curl -X DELETE "http://localhost:9200/_all"

# 3. RustFS/S3 桶
# (手动通过管理台或 mc 工具清空所有 bucket)

# 4. Redis
docker exec redis redis-cli FLUSHDB

# 5. RocketMQ (可选)
# (只要消费者重启后位点重置即可)
```

### 6.3 重启

```bash
$env:NO_PROXY='localhost,127.0.0.1'
$env:no_proxy='localhost,127.0.0.1'
mvn -pl bootstrap spring-boot:run
```

### 6.4 烟测

- [ ] 用 admin / 123456 登录
- [ ] 创建一个测试 KB，`dept_id = GLOBAL`
- [ ] 上传一篇文档，`security_level = 0`
- [ ] 跑完分块流程
- [ ] 发起一次问答，确认返回正常
- [ ] 创建一个 `max_security_level = 0` 的普通用户
- [ ] 把 security_level 为 2 的文档提权，用普通用户查询，断言**检索不到该文档**（这是最关键的 end-to-end 测试）

**建议：第 6 步是 PR1 的验收核心，没有通过这步就不能合并。**

---

## 七、测试策略

### 7.1 当前现状

- 13 个现存 `@SpringBootTest` 风格测试，多数是"手动集成烟测"（直连真实 DB，只 log 不断言）
- **零** MockMvc / WebMvcTest 基础设施
- **零** Sa-Token 测试配置
- **零** Knowledge{Base,Chunk,Document} 域测试
- **零** Mockito 使用（整个项目搜不到）

### 7.2 PR0 测试

**跳过 TDD（经用户授权）**。验证方式：
- 视觉 diff 核对（已完成）
- `mvn -pl bootstrap compile` 编译检查（已通过，BUILD SUCCESS）
- `mvn -pl bootstrap spotless:check` 格式检查（已通过）
- 重建后的烟测（重建流程的最后一步，等 PR1 完成后做）

### 7.3 PR1 测试

PR1 里会包含以下**最小测试**（不阻塞 TDD 但保证核心不漏）：

| 测试类型 | 范围 | 落地位置 |
|---|---|---|
| **安全 E2E 测试** | "security_level > user.maxLevel 的文档应被过滤" | `src/test/.../rag/core/retrieve/SecurityLevelFilterTests.java`（手动跑）|
| **Schema 回归** | 所有 ALTER 语句跑通 | `init_data_pg.sql` 重跑即通过 |
| **RBAC 黑盒** | DEPT_ADMIN 不能修改其他部门 KB | 手动 curl 验证 |

**完整 TDD 等测试基础设施 PR**（task #9）建成后回填。

### 7.4 测试基础设施 PR（task #9，PR1/PR3 之外的独立工作）

- 引入 `@SpringBootTest + MockMvc` 测试基类
- Sa-Token 测试配置（提供 `@WithMockUser` 等价物 / 手动 `StpUtil.login` 辅助）
- 测试数据库策略（是否用 H2 / testcontainers / 共享开发 DB 带 schema reset）
- Mockito 依赖引入
- 回填 PR0 / PR1 / PR3 核心测试

---

## 八、开放问题 & 后续待办

1. **`GLOBAL` 部门是否允许其他 DEPT_ADMIN 管理**：理论上 GLOBAL 部门没有 DEPT_ADMIN（只 SUPER_ADMIN 可管理）。但如果某公司有"全局运维组"，是否可以把 DEPT_ADMIN 角色挂到 GLOBAL 部门？暂时的默认答案：不允许（PR3 时再定）。

2. **`role.max_security_level` 多角色取 max 还是 min**：当前设计取 max（任一角色允许就允许，用户直觉一致）。代码实现时必须加测试兜底防止取反。

3. **Sa-Token 注解 vs 编程式校验的取舍**：PR1 改完后 `@SaCheckRole("SUPER_ADMIN")` 依然只能表达"必须是 SUPER_ADMIN"，表达不了"SUPER_ADMIN OR owning-dept-of-DEPT_ADMIN"。PR3 时所有写接口会从注解式降级到 `kbAccessService.checkManageAccess(kbId)` 编程式。是否值得自定义一个 `@RequireKbManageAccess` 注解做 AOP？暂时不考虑，保持编程式即可。

4. **`doc.chunk.security_level` 冗余同步**：当前 chunk 在 PG 里（`t_knowledge_chunk`）没有 `security_level` 列，只在 OS metadata 有。这样不能对 PG 做 admin 查询筛选敏感 chunk。PR1 是否要同时在 `t_knowledge_chunk` 加冗余列？**暂时不加**，节省复杂度。

5. **RocketMQ 事务消息的 DLQ 监控**：`SecurityLevelRefreshEvent` 消费失败进 DLQ 后，当前没有告警面板。作为 PR1 外的独立 follow-up：接入现有监控或加一个简单的 DLQ 巡检定时任务。

---

## 九、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-04-11 | v1.0 | 初版 —— PR0 已落地，PR1/PR3 待启动 |
| 2026-04-11 | v1.1 | 用户 review 反馈应用：(1) metadataFilters 改为类型化 `MetadataFilter` record + FilterOp 枚举；(2) `resolveMaxSecurityLevel` 系统路径返回 null 而非 3；(3) security_level 修改的 RocketMQ 异步刷新设计从"开放问题"提升为正式设计节；(4) 2-A 的"SUPER_ADMIN 独占角色 CRUD"隐式运营假设显式化；(5) 4.5.1 新增 16 处 `"admin"`→`"SUPER_ADMIN"` 精确替换清单（含行号） |
