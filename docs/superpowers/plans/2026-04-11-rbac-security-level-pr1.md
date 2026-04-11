# PR1: RBAC 骨架 + security_level 流水线 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在代码库里落地"SUPER_ADMIN / DEPT_ADMIN / USER 三级角色 + 文档 security_level 过滤"的数据层与检索层基础设施，完成后通过整体 wipe + rebuild + smoke test 验证。

**Architecture:** 新增 `sys_dept` 表承载部门；扩列 `t_role` / `t_role_kb_relation` / `t_knowledge_base` / `t_knowledge_document` / `t_user` 承载角色类型、权限、归属、安全等级；`LoginUser` 升级为多角色容器；`KbAccessService` 把 admin 放行逻辑下沉到 service 内部；`RetrieveRequest.metadataFilters` 从字符串 Map 升级为类型化 `List<MetadataFilter>`，激活现有死管道；OpenSearch index mapping 显式声明 `security_level` 字段，避免 `dynamic:false` 静默吞字段；文档安全等级修改走 RocketMQ 事务消息 + `_update_by_query` 异步刷新。

**Tech Stack:** Java 17, Spring Boot 3.5.7, MyBatis-Plus 3.5.14, PostgreSQL, OpenSearch 2.18, RocketMQ 5.x, Sa-Token 1.43, Redis + Redisson, Maven, Spotless.

**前置条件：**
- PR0 热修复已合并（commit `aea50bc`）
- 设计文档 v1.1 已合并（commit `5727812`，位于 `docs/dev/design/rbac-and-security-level-implementation.md`）
- 开发试用环境，允许执行最终的整体数据库 + OpenSearch 索引 + S3 桶 + Redis 清理重建
- 测试策略：TDD 在单元测试可写的地方严格执行；需要 Spring 全上下文 / MockMvc / Sa-Token mock 的场景经用户授权跳过自动化测试，改用手动 smoke test 验证，后续由 follow-up task #9 （建测试基础设施 PR）回填

---

## 一、约定与项目特有踩坑点（实施前必读）

这些是前置条件里不够突出的隐式约定，每次改到相关文件都要记得：

1. **两份 schema 文件必须同步**：`resources/database/schema_pg.sql`（干净 DDL）和 `resources/database/full_schema_pg.sql`（pg_dump 格式）。改任何表结构都必须两份一起改。
2. **Spotless 自动格式化**：`mvn -pl bootstrap spring-boot:run` 或 `mvn -pl bootstrap install` 会自动跑 `spotless:apply` 把代码格式化。提交前跑 `mvn -pl bootstrap spotless:check` 确认没问题。若 check 失败，跑 `mvn spotless:apply` 一键修正。
3. **PostgreSQL 小写折叠**：`selectMaps` 中 `.select("kb_id AS kbId")` 产生的 map key 是 `kbid`（小写），不是 `kbId`。始终用 snake_case 别名（`AS kb_id`）并用 `row.get("kb_id")` 取值。
4. **`@TableLogic` 自动过滤**：带 `@TableLogic` 的实体 MyBatis-Plus 自动追加 `WHERE deleted=0`，不要再手动 `.eq(::getDeleted, 0)`。
5. **Sa-Token 角色框架是字符串驱动**：`@SaCheckRole("X")` 匹配的是 `StpInterface.getRoleList()` 返回 `List<String>` 的元素。本 PR 把返回值从 `["admin"]` 改为 `["SUPER_ADMIN"]`（或更多）后，所有 `@SaCheckRole("admin")` 都要同步改成 `@SaCheckRole("SUPER_ADMIN")`。
6. **数据库访问**：`docker exec postgres psql -U postgres -d ragent -c "SQL"`。用户是 `postgres`，不是 `ragent`。
7. **SSE 流式 + UserContext ThreadLocal**：必须在请求线程里捕获 `UserContext.maxSecurityLevel` 等值，不能在 `StreamCallback` 里懒取 —— 因为 `ChatRateLimitAspect.finally` 会提前清空 ThreadLocal。
8. **framework 模块禁反向依赖**：framework 只能被 bootstrap 等业务模块依赖，不能依赖 bootstrap 中任何类。新类型如 `RoleType`、`Permission` 如果要在 framework 的 `LoginUser` 里用，必须定义在 framework 模块内部。
9. **commit 粒度**：每个 task 完成后立即 commit（不等整个 PR 全写完），commit 前跑 `mvn -pl bootstrap spotless:apply` + `mvn -pl bootstrap compile`。
10. **进行中后端验证**：改完 Java 代码后必须手动重启 `mvn -pl bootstrap spring-boot:run`（前端热更新不适用于后端）。

---

## 二、最终产出物清单

本 PR 结束时，以下文件会被**新增或修改**（约 35 个文件）：

### Schema / 初始化

- `resources/database/schema_pg.sql`（M）
- `resources/database/full_schema_pg.sql`（M）
- `resources/database/init_data_pg.sql`（M）
- `resources/database/init_data_full_pg.sql`（M）

### framework 模块

- `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/RoleType.java`（C）
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/Permission.java`（C）
- `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java`（M）

### bootstrap 实体

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/SysDeptDO.java`（C）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/UserDO.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleDO.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeBaseDO.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java`（M）

### bootstrap mapper

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/SysDeptMapper.java`（C）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleMapper.java`（M，加自定义 SQL 方法）

### bootstrap context / 拦截器

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenStpInterfaceImpl.java`（M）

### RBAC 服务

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`（M）

### 字符串替换（PR1 同步把 PR0 的 `@SaCheckRole("admin")` 换成 `"SUPER_ADMIN"`）

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeChunkController.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java`（M）

### 向量层

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`（M，加接口方法）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java`（M）
- （Milvus / pgvector impl 加 `UnsupportedOperationException` 占位）

### Ingestion

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IndexerNode.java`（M）

### 检索类型 + 链路

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MetadataFilter.java`（C）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrieveRequest.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/CollectionParallelRetriever.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`（M）

### APIs + RocketMQ

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUpdateRequest.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBaseCreateRequest.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`（M）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/SecurityLevelRefreshEvent.java`（C）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentSecurityLevelRefreshConsumer.java`（C）

---

## 三、任务列表

**依赖关系图（高层）：**

```
Task 1 (schema)
  ↓
Task 2 (enums) ────┐
  ↓                │
Task 3 (MetadataFilter)
  │                │
  ↓                ↓
Task 4 (LoginUser) ─→ Task 5 (Entity DOs) ─→ Task 6 (SysDept + mappers) ─→ Task 7 (RoleMapper enhancement)
  │                                                                             │
  ↓                                                                             ↓
Task 8 (UserContextInterceptor + SaTokenStpInterfaceImpl)                      │
  ↓                                                                             │
Task 9 (KbAccessService upgrade) ←──────────────────────────────────────────────┘
  ↓
Task 10 (@SaCheckRole string replacement 16 处)
  ↓
Task 11 (OpenSearch mapping + IndexerNode)
  ↓
Task 12 (VectorStoreService.updateChunksMetadata)
  ↓
Task 13 (RetrieveRequest + SearchContext type upgrade)
  ↓
Task 14 (3 retriever call-site 注入 metadataFilters)
  ↓
Task 15 (OpenSearchRetrieverService.buildFilterClause 重写)
  ↓
Task 16 (Document upload/update API 加 securityLevel)
  ↓
Task 17 (RocketMQ async refresh: Event + Consumer)
  ↓
Task 18 (KB create API 加 deptId + DEPT_ADMIN 约束)
  ↓
Task 19 (init_data_pg.sql 插入默认部门/角色/admin)
  ↓
Task 20 (Rebuild + smoke test)
```

---

### Task 1: 数据库 schema 变更

**Files:**
- Modify: `resources/database/schema_pg.sql`
- Modify: `resources/database/full_schema_pg.sql`

**Context:** 新建 `sys_dept` 表，扩 5 张现有表加新列。**两份 schema 文件必须同步修改**（约定 #1）。

- [ ] **Step 1: 在 `schema_pg.sql` 中新建 `sys_dept` 表**

追加到合适位置（建议 `t_user` 表之前，因为 `t_user.dept_id` 引用它）：

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
COMMENT ON COLUMN sys_dept.id IS '主键ID';
COMMENT ON COLUMN sys_dept.dept_code IS '部门编码，全局唯一';
COMMENT ON COLUMN sys_dept.dept_name IS '部门名称';
```

- [ ] **Step 2: 在 `schema_pg.sql` 中扩 `t_user` 列**

找到 `CREATE TABLE t_user (...)` 的定义，在 `avatar` 之后（或其他合适位置）加 `dept_id`：

```sql
CREATE TABLE t_user (
    id           VARCHAR(20)  NOT NULL PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    password     VARCHAR(128) NOT NULL,
    role         VARCHAR(32)  NOT NULL,
    avatar       VARCHAR(128),
    dept_id      VARCHAR(20),           -- ← 新增
    create_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     DEFAULT 0,
    CONSTRAINT uk_user_username UNIQUE (username)
);
```

追加对应 COMMENT：
```sql
COMMENT ON COLUMN t_user.dept_id IS '所属部门ID';
```

注意 `t_user.role` 列**保留** —— Sa-Token `getRoleList` 兼容层继续用它兜底（虽然后续会改成走 `t_user_role` join），这样 PR 中途若回滚到某步也不会让登录接口挂掉。

- [ ] **Step 3: 在 `schema_pg.sql` 中扩 `t_role` 列**

```sql
CREATE TABLE t_role (
    id                  VARCHAR(20) NOT NULL PRIMARY KEY,
    name                VARCHAR(64) NOT NULL,
    description         VARCHAR(256),
    role_type           VARCHAR(32) NOT NULL DEFAULT 'USER',    -- ← 新增
    max_security_level  SMALLINT    NOT NULL DEFAULT 0,         -- ← 新增
    created_by          VARCHAR(20),
    updated_by          VARCHAR(20),
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT  DEFAULT 0
);
```

追加 COMMENT：
```sql
COMMENT ON COLUMN t_role.role_type IS 'SUPER_ADMIN/DEPT_ADMIN/USER';
COMMENT ON COLUMN t_role.max_security_level IS '该角色可访问的最高安全等级（0-3）';
```

注意：如果原始 `t_role` 定义的列顺序和上面不同，**保留原顺序**，只在末尾（业务字段之后、审计字段之前或之后）追加 `role_type` 和 `max_security_level`。不要重排现有列。

- [ ] **Step 4: 在 `schema_pg.sql` 中扩 `t_role_kb_relation` 列**

```sql
ALTER TABLE 表级：在定义处加 permission 列
```

在 `CREATE TABLE t_role_kb_relation (...)` 中加：
```sql
    permission VARCHAR(16) NOT NULL DEFAULT 'READ',
```

追加 COMMENT：
```sql
COMMENT ON COLUMN t_role_kb_relation.permission IS 'READ/WRITE/MANAGE';
```

- [ ] **Step 5: 在 `schema_pg.sql` 中扩 `t_knowledge_base` 列**

在 `CREATE TABLE t_knowledge_base (...)` 中加：
```sql
    dept_id VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
```

追加 COMMENT：
```sql
COMMENT ON COLUMN t_knowledge_base.dept_id IS '归属部门ID（决定哪个 DEPT_ADMIN 能管理此知识库）';
```

- [ ] **Step 6: 在 `schema_pg.sql` 中扩 `t_knowledge_document` 列**

在 `CREATE TABLE t_knowledge_document (...)` 中加：
```sql
    security_level SMALLINT NOT NULL DEFAULT 0,
```

追加 COMMENT：
```sql
COMMENT ON COLUMN t_knowledge_document.security_level IS '文档安全等级：0=PUBLIC, 1=INTERNAL, 2=CONFIDENTIAL, 3=RESTRICTED';
```

- [ ] **Step 7: 同步以上 6 处修改到 `full_schema_pg.sql`**

打开 `resources/database/full_schema_pg.sql`，对同样的表做同样的改动。`full_schema_pg.sql` 用 pg_dump 格式（有 `CREATE TABLE public.t_user (...)` 等，列定义块相同）。

**关键校验**：保存后执行
```bash
diff <(grep -E 'dept_id|role_type|max_security_level|permission|security_level' resources/database/schema_pg.sql) \
     <(grep -E 'dept_id|role_type|max_security_level|permission|security_level' resources/database/full_schema_pg.sql)
```
两边的新列定义应该一致（行数一致、内容语义一致）。

- [ ] **Step 8: Dry-run schema 文件（不影响当前 DB）**

```bash
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent_dryrun;"
docker exec -i postgres psql -U postgres -d ragent_dryrun < resources/database/schema_pg.sql
docker exec postgres psql -U postgres -d ragent_dryrun -c "\d sys_dept"
docker exec postgres psql -U postgres -d ragent_dryrun -c "\d t_knowledge_document" | grep security_level
docker exec postgres psql -U postgres -d ragent_dryrun -c "\d t_role" | grep -E 'role_type|max_security_level'
docker exec postgres psql -U postgres -c "DROP DATABASE ragent_dryrun;"
```

Expected 输出：`sys_dept` 表展示 5 列 + audit 列；`t_knowledge_document` 展示 `security_level` 列为 `smallint NOT NULL DEFAULT 0`；`t_role` 展示 `role_type` 和 `max_security_level`。

- [ ] **Step 9: Commit**

```bash
git add resources/database/schema_pg.sql resources/database/full_schema_pg.sql
git commit -m "feat(schema): add sys_dept, role_type, security_level, permission, kb.dept_id"
```

---

### Task 2: 框架层枚举 `RoleType` 与 `Permission`

**Files:**
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/RoleType.java`
- Create: `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/Permission.java`
- Create (test): `framework/src/test/java/com/nageoffer/ai/ragent/framework/context/RoleTypeTests.java`

**Context:** 为 `LoginUser` 和 `KbAccessService` 准备枚举类型。必须在 framework 模块内部，因为 `LoginUser` 在 framework 且 framework 不能反向依赖 bootstrap（约定 #8）。

- [ ] **Step 1: 写失败测试 `RoleTypeTests`**

```java
package com.nageoffer.ai.ragent.framework.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoleTypeTests {

    @Test
    void valueOf_recognizes_all_three_roles() {
        assertEquals(RoleType.SUPER_ADMIN, RoleType.valueOf("SUPER_ADMIN"));
        assertEquals(RoleType.DEPT_ADMIN, RoleType.valueOf("DEPT_ADMIN"));
        assertEquals(RoleType.USER, RoleType.valueOf("USER"));
    }

    @Test
    void permission_ordering_READ_less_than_WRITE_less_than_MANAGE() {
        // READ(0) < WRITE(1) < MANAGE(2) —— 用 ordinal() 方便比较"至少是 READ"
        assertTrue(Permission.READ.ordinal() < Permission.WRITE.ordinal());
        assertTrue(Permission.WRITE.ordinal() < Permission.MANAGE.ordinal());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl framework test -Dtest=RoleTypeTests
```

Expected: 编译失败（`RoleType` 和 `Permission` 类不存在）。

- [ ] **Step 3: 创建 `RoleType.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.context;

/**
 * 用户角色类型。
 *
 * <p>三种角色类型按权限范围从大到小：
 * <ul>
 *     <li>{@link #SUPER_ADMIN}：全局超管，绕过所有 RBAC 过滤</li>
 *     <li>{@link #DEPT_ADMIN}：部门管理员，可管理 {@code kb.dept_id == user.dept_id} 的知识库</li>
 *     <li>{@link #USER}：普通用户，仅能访问被授权的知识库</li>
 * </ul>
 *
 * <p>一个用户可同时挂载多个角色（例如既是 USER 也是某部门的 DEPT_ADMIN）。
 */
public enum RoleType {
    SUPER_ADMIN,
    DEPT_ADMIN,
    USER
}
```

- [ ] **Step 4: 创建 `Permission.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.context;

/**
 * 知识库访问权限级别。
 *
 * <p>按照权限大小单调递增：
 * <ol start="0">
 *     <li>{@link #READ}：可对 KB 做问答/读取</li>
 *     <li>{@link #WRITE}：可上传/删除文档（DEPT_ADMIN 默认拥有）</li>
 *     <li>{@link #MANAGE}：可删除 KB 本身、管理 KB 的角色授权（SUPER_ADMIN 默认拥有）</li>
 * </ol>
 *
 * <p>{@link #ordinal()} 的顺序反映权限大小，可用于 {@code minPermission} 比较。
 */
public enum Permission {
    READ,
    WRITE,
    MANAGE
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
mvn -pl framework test -Dtest=RoleTypeTests
```

Expected: BUILD SUCCESS, 2 tests run, 0 failures.

- [ ] **Step 6: Commit**

```bash
mvn -pl framework spotless:apply
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/context/RoleType.java \
        framework/src/main/java/com/nageoffer/ai/ragent/framework/context/Permission.java \
        framework/src/test/java/com/nageoffer/ai/ragent/framework/context/RoleTypeTests.java
git commit -m "feat(framework): add RoleType and Permission enums"
```

---

### Task 3: `MetadataFilter` 类型化检索过滤器

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MetadataFilter.java`
- Create (test): `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/MetadataFilterTests.java`

**Context:** 取代现有字符串后缀约定（如 `security_level_lte`），避免字段名本身以 `_lte` 结尾时误识别 range。

- [ ] **Step 1: 写失败测试**

```java
package com.nageoffer.ai.ragent.rag.core.retrieve;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetadataFilterTests {

    @Test
    void record_captures_field_op_and_value() {
        MetadataFilter f = new MetadataFilter("security_level", MetadataFilter.FilterOp.LTE, 2);
        assertEquals("security_level", f.field());
        assertEquals(MetadataFilter.FilterOp.LTE, f.op());
        assertEquals(2, f.value());
    }

    @Test
    void all_filter_ops_valueOf() {
        assertEquals(MetadataFilter.FilterOp.EQ,  MetadataFilter.FilterOp.valueOf("EQ"));
        assertEquals(MetadataFilter.FilterOp.LTE, MetadataFilter.FilterOp.valueOf("LTE"));
        assertEquals(MetadataFilter.FilterOp.GTE, MetadataFilter.FilterOp.valueOf("GTE"));
        assertEquals(MetadataFilter.FilterOp.LT,  MetadataFilter.FilterOp.valueOf("LT"));
        assertEquals(MetadataFilter.FilterOp.GT,  MetadataFilter.FilterOp.valueOf("GT"));
        assertEquals(MetadataFilter.FilterOp.IN,  MetadataFilter.FilterOp.valueOf("IN"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=MetadataFilterTests
```

Expected: 编译失败（类不存在）。

- [ ] **Step 3: 创建 `MetadataFilter.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.retrieve;

/**
 * 向量检索的 metadata 过滤条件（类型化版本）。
 *
 * <p>用于在 {@code RetrieveRequest.metadataFilters} 里表达"metadata.&lt;field&gt; &lt;op&gt; &lt;value&gt;"
 * 的原子条件。由下游 {@code RetrieverService} 的具体实现（OpenSearch/Milvus/pgvector）各自把它翻译
 * 成对应存储的过滤语法。
 *
 * <p>示例：
 * <pre>{@code
 * // "security_level <= 2"
 * new MetadataFilter("security_level", FilterOp.LTE, 2)
 *
 * // "doc_id == 'abc123'"
 * new MetadataFilter("doc_id", FilterOp.EQ, "abc123")
 *
 * // "source_type in ('file', 'url')"
 * new MetadataFilter("source_type", FilterOp.IN, List.of("file", "url"))
 * }</pre>
 *
 * <p><strong>为何不用字符串 key 后缀约定</strong>（例如 {@code security_level_lte}）：若将来 metadata
 * 字段名本身以 {@code _lte} 结尾（例如 {@code estimated_lte}），会被误识别为 range 操作。使用类型化
 * enum 消除这种歧义。
 */
public record MetadataFilter(String field, FilterOp op, Object value) {

    /**
     * 过滤操作类型。
     *
     * <ul>
     *     <li>{@link #EQ}：精确匹配（term）</li>
     *     <li>{@link #LTE}/{@link #GTE}/{@link #LT}/{@link #GT}：范围比较（range）</li>
     *     <li>{@link #IN}：多值匹配（terms），{@link #value} 需为 {@link java.util.Collection}</li>
     * </ul>
     */
    public enum FilterOp {
        EQ,
        LTE,
        GTE,
        LT,
        GT,
        IN
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl bootstrap test -Dtest=MetadataFilterTests
```

Expected: BUILD SUCCESS, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MetadataFilter.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/retrieve/MetadataFilterTests.java
git commit -m "feat(retrieve): introduce typed MetadataFilter record"
```

---

### Task 4: `LoginUser` 加 `deptId` / `roleTypes` / `maxSecurityLevel`

**Files:**
- Modify: `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java`
- Create (test): `framework/src/test/java/com/nageoffer/ai/ragent/framework/context/LoginUserTests.java`

**Context:** 让 `LoginUser` 承载多角色信息。legacy `role` 字段保留（PR3 才移除），新增 3 个字段。

- [ ] **Step 1: 写失败测试**

```java
package com.nageoffer.ai.ragent.framework.context;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LoginUserTests {

    @Test
    void builder_creates_user_with_multi_role() {
        LoginUser user = LoginUser.builder()
                .userId("1")
                .username("alice")
                .role("admin")            // legacy
                .avatar("https://x.com/a.png")
                .deptId("OPS")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN, RoleType.USER))
                .maxSecurityLevel(3)
                .build();

        assertEquals("1", user.getUserId());
        assertEquals("alice", user.getUsername());
        assertEquals("OPS", user.getDeptId());
        assertEquals(Set.of(RoleType.SUPER_ADMIN, RoleType.USER), user.getRoleTypes());
        assertEquals(3, user.getMaxSecurityLevel());
    }

    @Test
    void default_values_for_optional_fields() {
        LoginUser user = LoginUser.builder()
                .userId("2")
                .username("bob")
                .role("user")
                .build();

        assertNull(user.getDeptId());
        assertNull(user.getRoleTypes());
        assertEquals(0, user.getMaxSecurityLevel());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl framework test -Dtest=LoginUserTests
```

Expected: 编译失败（setter/getter 不存在）。

- [ ] **Step 3: 修改 `LoginUser.java` 增加新字段**

完整的 `LoginUser.java`：

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前登录用户的上下文快照。
 *
 * <p>由 {@code UserContextInterceptor} 在请求进入时一次性从数据库装载，塞进
 * {@code UserContext}（TTL ThreadLocal）。业务代码通过静态方法访问。
 *
 * <p><strong>字段分两代：</strong>
 * <ul>
 *     <li>legacy：{@code role} —— 单字符串角色，保留给 Sa-Token 兼容层。PR3 移除。</li>
 *     <li>新增：{@code deptId} / {@code roleTypes} / {@code maxSecurityLevel} —— 支持多角色 RBAC。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser {

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * Legacy 单字符串角色（admin/user）。
     *
     * @deprecated PR3 里移除，用 {@link #roleTypes} 代替
     */
    @Deprecated
    private String role;

    /**
     * 用户头像
     */
    private String avatar;

    /**
     * 所属部门 ID。
     */
    private String deptId;

    /**
     * 用户挂载的所有角色类型（跨 {@code t_user_role} 的所有关联去重）。
     */
    private Set<RoleType> roleTypes;

    /**
     * 用户跨所有角色的最大 {@code security_level}。
     * 用于向量检索过滤：{@code metadata.security_level <= maxSecurityLevel}。
     */
    private int maxSecurityLevel;
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -pl framework test -Dtest=LoginUserTests
```

Expected: 2 tests pass.

- [ ] **Step 5: 编译整个项目确认没人坏掉**

`LoginUser` 是 framework 模块的对外类。bootstrap 里有多处调用（例如 `UserContextInterceptor`、`AuthServiceImpl`、`UserController`）可能通过 setter/getter 读它 —— 新增字段不会坏旧的，删字段才会。因此只要编译通过即可：

```bash
mvn -pl framework,bootstrap compile -q
```

Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
mvn -pl framework spotless:apply
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java \
        framework/src/test/java/com/nageoffer/ai/ragent/framework/context/LoginUserTests.java
git commit -m "feat(framework): add deptId, roleTypes, maxSecurityLevel to LoginUser"
```

---

### Task 5: 现有实体 DO 扩字段

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/UserDO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleDO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeBaseDO.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java`

**Context:** 纯加字段，和 Task 1 的 schema 对齐。Lombok `@Data` 自动生成 getter/setter。

- [ ] **Step 1: 修改 `UserDO.java` 加 `deptId`**

在 existing fields 之后（但在 `create_time` 之前或同一审计字段块）加：

```java
    /**
     * 所属部门 ID
     */
    private String deptId;
```

- [ ] **Step 2: 修改 `RoleDO.java` 加 `roleType` 和 `maxSecurityLevel`**

在 `description` 之后加：

```java
    /**
     * 角色类型：SUPER_ADMIN / DEPT_ADMIN / USER
     */
    private String roleType;

    /**
     * 该角色可访问的最高安全等级（0-3）
     */
    private Integer maxSecurityLevel;
```

- [ ] **Step 3: 修改 `RoleKbRelationDO.java` 加 `permission`**

在 `kbId` 之后加：

```java
    /**
     * 权限级别：READ / WRITE / MANAGE
     */
    private String permission;
```

- [ ] **Step 4: 修改 `KnowledgeBaseDO.java` 加 `deptId`**

在 `collectionName` 之后加：

```java
    /**
     * 归属部门 ID（决定哪个 DEPT_ADMIN 能管理此知识库）
     */
    private String deptId;
```

- [ ] **Step 5: 修改 `KnowledgeDocumentDO.java` 加 `securityLevel`**

在 `status` 之后加：

```java
    /**
     * 文档安全等级：0=PUBLIC, 1=INTERNAL, 2=CONFIDENTIAL, 3=RESTRICTED
     */
    private Integer securityLevel;
```

- [ ] **Step 6: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS。注意：即使现存代码没有引用新字段，MyBatis-Plus 也不会因为新列报错（MP 的 `underScoreToCamelCase` 会自动映射 `dept_id` ↔ `deptId`）。

- [ ] **Step 7: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/UserDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeBaseDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/dao/entity/KnowledgeDocumentDO.java
git commit -m "feat(entity): add RBAC and security_level fields to existing DOs"
```

---

### Task 6: `SysDeptDO` 新实体 + mapper

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/SysDeptDO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/SysDeptMapper.java`

**Context:** 标准 MyBatis-Plus 实体 + mapper。和 `RoleDO` / `RoleMapper` 同结构。

- [ ] **Step 1: 创建 `SysDeptDO.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 部门实体（sys_dept 表）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("sys_dept")
public class SysDeptDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 部门编码，全局唯一
     */
    private String deptCode;

    /**
     * 部门名称
     */
    private String deptName;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 2: 创建 `SysDeptMapper.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门表 Mapper
 */
@Mapper
public interface SysDeptMapper extends BaseMapper<SysDeptDO> {
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/SysDeptDO.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/SysDeptMapper.java
git commit -m "feat(user): add SysDeptDO and mapper"
```

---

### Task 7: `RoleMapper` 加 `selectRolesByUserId` / `selectRoleTypesByUserId`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleMapper.java`

**Context:** `UserContextInterceptor` 和 `SaTokenStpInterfaceImpl` 都需要一次查出用户的所有角色（或只是 role_type）。用 MP 注解式 SQL 最简。

- [ ] **Step 1: 先看当前 `RoleMapper.java` 长什么样**

```bash
# 不是 step 的动作，只是阅读参考
```

预期它形如：
```java
@Mapper
public interface RoleMapper extends BaseMapper<RoleDO> {
}
```

- [ ] **Step 2: 加两个自定义 SQL 方法**

完整的 `RoleMapper.java`：

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色表 Mapper
 */
@Mapper
public interface RoleMapper extends BaseMapper<RoleDO> {

    /**
     * 查询用户挂载的所有角色（完整 RoleDO，用于拿 max_security_level 等字段）。
     *
     * <p>注意：走 {@code t_user_role → t_role} 内连接，不做 deleted 过滤 ——
     * {@code @TableLogic} 只对基类的 {@code BaseMapper} 自动生效，自定义 SQL 里
     * 必须显式加 {@code AND t_role.deleted = 0}。
     */
    @Select("SELECT r.* FROM t_user_role ur "
          + "JOIN t_role r ON ur.role_id = r.id "
          + "WHERE ur.user_id = #{userId} "
          + "AND r.deleted = 0")
    List<RoleDO> selectRolesByUserId(@Param("userId") String userId);

    /**
     * 查询用户挂载的所有角色类型字符串（仅 {@code role_type} 列，Sa-Token 用）。
     */
    @Select("SELECT DISTINCT r.role_type FROM t_user_role ur "
          + "JOIN t_role r ON ur.role_id = r.id "
          + "WHERE ur.user_id = #{userId} "
          + "AND r.deleted = 0 "
          + "AND r.role_type IS NOT NULL")
    List<String> selectRoleTypesByUserId(@Param("userId") String userId);
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/mapper/RoleMapper.java
git commit -m "feat(user): add RoleMapper.selectRolesByUserId and selectRoleTypesByUserId"
```

---

### Task 8: `UserContextInterceptor` 和 `SaTokenStpInterfaceImpl` 改用多角色

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenStpInterfaceImpl.java`

**Context:** 两个文件同一个关注点 —— "请求进来时一次性装载多角色"。放一个 task 里。

- [ ] **Step 1: 修改 `UserContextInterceptor.preHandle` 装载多角色**

完整的 `UserContextInterceptor.java`：

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.config;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 用户上下文拦截器。
 *
 * <p>在请求进入时一次性从数据库 join 查出 {@code user + roles}，封装成 {@link LoginUser}
 * 塞进 {@link UserContext} ThreadLocal。后续业务代码无需再走 DB。
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        // 异步调度请求跳过（SSE 完成回调会触发 asyncDispatch，此时 SaToken 上下文已丢失）
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        // 预检请求放行，避免 CORS 阻断
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String loginId = StpUtil.getLoginIdAsString();
        UserDO user = userMapper.selectById(loginId);

        // 一次性 join 查所有关联角色
        List<RoleDO> roles = roleMapper.selectRolesByUserId(loginId);
        Set<RoleType> roleTypes = roles.stream()
                .map(RoleDO::getRoleType)
                .filter(StrUtil::isNotBlank)
                .map(rt -> {
                    try {
                        return RoleType.valueOf(rt);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(RoleType.class)));
        int maxSecurityLevel = roles.stream()
                .mapToInt(r -> r.getMaxSecurityLevel() == null ? 0 : r.getMaxSecurityLevel())
                .max()
                .orElse(0);

        UserContext.set(
                LoginUser.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .role(user.getRole())
                        .avatar(StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar())
                        .deptId(user.getDeptId())
                        .roleTypes(roleTypes)
                        .maxSecurityLevel(maxSecurityLevel)
                        .build()
        );
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
        UserContext.clear();
    }
}
```

注意：原文里 `user.getId().toString()`，新版本改为 `user.getId()` —— 检查一下 `UserDO.getId()` 的类型，如果是 `String` 就用直接用；如果是 `Long` 则保持 `.toString()`。

- [ ] **Step 2: 修改 `SaTokenStpInterfaceImpl.getRoleList` 返回多角色**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限认证接口实现。
 *
 * <p>{@code @SaCheckRole("X")} 注解匹配的是这里 {@link #getRoleList} 返回的字符串列表元素。
 * PR1 后返回值是 {@code List<RoleType.name()>}，所以注解里的字符串也从 {@code "admin"}
 * 替换成 {@code "SUPER_ADMIN"}（见 Task 10）。
 */
@Component
@RequiredArgsConstructor
public class SaTokenStpInterfaceImpl implements StpInterface {

    private final RoleMapper roleMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return Collections.emptyList();
        }
        String loginIdStr = loginId.toString();
        if (!StrUtil.isNumeric(loginIdStr)) {
            return Collections.emptyList();
        }

        List<String> roleTypes = roleMapper.selectRoleTypesByUserId(loginIdStr);
        return roleTypes == null ? Collections.emptyList() : roleTypes;
    }
}
```

**重要变化**：构造器注入从 `UserMapper` 改为 `RoleMapper`。确保其他地方没有用 `SaTokenStpInterfaceImpl.userMapper`（它没 public 字段，只是私有注入，所以改造安全）。

- [ ] **Step 3: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/UserContextInterceptor.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/config/SaTokenStpInterfaceImpl.java
git commit -m "feat(user): load multi-role on request entry via RoleMapper"
```

---

### Task 9: `KbAccessService` 接口升级 + 实现

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

**Context:** 把 admin 放行逻辑下沉到 service，新增 `getMaxSecurityLevel`、`checkManageAccess`、`isSuperAdmin` 方法。保留旧签名做兼容。

- [ ] **Step 1: 修改 `KbAccessService` 接口**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.service;

import com.nageoffer.ai.ragent.framework.context.Permission;

import java.util.Set;

/**
 * 知识库访问权限服务。
 *
 * <p>统一承载三种判断：
 * <ul>
 *     <li>"当前用户能访问哪些知识库"（{@link #getAccessibleKbIds}）</li>
 *     <li>"当前用户对某 KB 有读权限吗"（{@link #checkAccess}）</li>
 *     <li>"当前用户对某 KB 有管理权吗"（{@link #checkManageAccess}）</li>
 * </ul>
 *
 * <p>所有方法都从 {@code UserContext} 读取当前用户。{@link com.nageoffer.ai.ragent.framework.context.RoleType#SUPER_ADMIN}
 * 在实现里统一放行，调用方不用再手动判 {@code "admin".equals(...)}。
 */
public interface KbAccessService {

    /**
     * 获取当前用户对指定最低权限可访问的所有知识库 ID。
     * SUPER_ADMIN 返回全量可见 KB。
     */
    Set<String> getAccessibleKbIds(String userId, Permission minPermission);

    /**
     * 等价于 {@code getAccessibleKbIds(userId, READ)}，保持旧调用点兼容。
     */
    default Set<String> getAccessibleKbIds(String userId) {
        return getAccessibleKbIds(userId, Permission.READ);
    }

    /**
     * 校验当前用户对指定知识库的 READ 权限，无权抛 {@code ClientException}。
     * SUPER_ADMIN 直接放行。系统态（无登录态）也直接放行。
     */
    void checkAccess(String kbId);

    /**
     * 校验当前用户对指定知识库的 MANAGE 权限（写/删/授权）。
     * SUPER_ADMIN 放行；DEPT_ADMIN 仅当 {@code kb.dept_id == user.dept_id} 放行；其他抛 {@code ClientException}。
     */
    void checkManageAccess(String kbId);

    /**
     * 当前上下文是否是 SUPER_ADMIN。展示层用（例如列表是否全量）。
     */
    boolean isSuperAdmin();

    /**
     * 清除指定用户的权限缓存
     */
    void evictCache(String userId);
}
```

- [ ] **Step 2: 修改 `KbAccessServiceImpl` 实现**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbAccessServiceImpl implements KbAccessService {

    private static final String CACHE_PREFIX = "kb_access:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RedissonClient redissonClient;

    @Override
    public Set<String> getAccessibleKbIds(String userId, Permission minPermission) {
        // SUPER_ADMIN 全量放行
        if (isSuperAdmin()) {
            return knowledgeBaseMapper.selectList(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .select(KnowledgeBaseDO::getId)
            ).stream().map(KnowledgeBaseDO::getId).collect(Collectors.toSet());
        }

        // Cache 仅对默认 READ 级别生效，更高级别的查询不走缓存（避免 key 爆炸）
        boolean cacheable = minPermission == Permission.READ;
        String cacheKey = CACHE_PREFIX + userId;
        if (cacheable) {
            RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
            Set<String> cached = bucket.get();
            if (cached != null) {
                return cached;
            }
        }

        // user → roles
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId));
        if (userRoles.isEmpty()) {
            if (cacheable) {
                redissonClient.getBucket(cacheKey).<Set<String>>set(Set.of(), CACHE_TTL);
            }
            return Set.of();
        }

        List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();

        // roles → kb_relations 过滤 permission >= minPermission
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .in(RoleKbRelationDO::getRoleId, roleIds));
        Set<String> kbIds = relations.stream()
                .filter(r -> permissionSatisfies(r.getPermission(), minPermission))
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toSet());

        // 过滤已删除的 KB
        if (!kbIds.isEmpty()) {
            List<KnowledgeBaseDO> validKbs = knowledgeBaseMapper.selectList(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .in(KnowledgeBaseDO::getId, kbIds)
                            .select(KnowledgeBaseDO::getId));
            kbIds = validKbs.stream().map(KnowledgeBaseDO::getId).collect(Collectors.toSet());
        }

        if (cacheable) {
            redissonClient.getBucket(cacheKey).<Set<String>>set(kbIds, CACHE_TTL);
        }
        return kbIds;
    }

    private boolean permissionSatisfies(String actual, Permission required) {
        if (actual == null) return false;
        try {
            return Permission.valueOf(actual).ordinal() >= required.ordinal();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown permission value in DB: {}", actual);
            return false;
        }
    }

    @Override
    public void checkAccess(String kbId) {
        // 系统态（MQ 消费者、定时任务）—— 没有登录态，直接放行
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            return;
        }
        // SUPER_ADMIN 直接放行
        if (isSuperAdmin()) {
            return;
        }
        // 普通用户
        Set<String> accessible = getAccessibleKbIds(UserContext.getUserId(), Permission.READ);
        if (!accessible.contains(kbId)) {
            throw new ClientException("无权访问该知识库: " + kbId);
        }
    }

    @Override
    public void checkManageAccess(String kbId) {
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            // 系统态允许通过（DEPT_ADMIN 不会在 MQ 消费者里触发写接口）
            return;
        }
        if (isSuperAdmin()) {
            return;
        }
        LoginUser user = UserContext.get();
        if (user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.DEPT_ADMIN)) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
            if (kb == null) {
                throw new ClientException("知识库不存在: " + kbId);
            }
            if (user.getDeptId() != null && user.getDeptId().equals(kb.getDeptId())) {
                return;
            }
            throw new ClientException("无权管理其他部门知识库: " + kbId);
        }
        throw new ClientException("无管理权限: " + kbId);
    }

    @Override
    public boolean isSuperAdmin() {
        if (!UserContext.hasUser()) {
            return false;
        }
        LoginUser user = UserContext.get();
        return user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.SUPER_ADMIN);
    }

    @Override
    public void evictCache(String userId) {
        redissonClient.getBucket(CACHE_PREFIX + userId).delete();
    }
}
```

**关键变化**：
1. `"admin".equals(UserContext.getRole())` → `isSuperAdmin()` + roleTypes 检查
2. `getAccessibleKbIds` 里 SUPER_ADMIN 真的返回全量 KB（修复原接口 javadoc 与实现不一致的历史 bug）
3. 新增 `permissionSatisfies` helper 按 permission 级别过滤 `role_kb_relation`
4. `checkManageAccess` 增加 DEPT_ADMIN 分支
5. cache 只对 READ 级别生效，避免 `(userId, minPermission)` 组合爆炸

- [ ] **Step 3: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS。如果 bootstrap 里其他类实现了 `KbAccessService` 接口（例如测试 double），会因新方法缺实现而编译失败。用 `grep -r "implements KbAccessService"` 确认只有 `KbAccessServiceImpl` 实现了。

- [ ] **Step 4: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "feat(user): sink SUPER_ADMIN bypass into KbAccessService, add checkManageAccess"
```

---

### Task 10: `@SaCheckRole("admin")` → `@SaCheckRole("SUPER_ADMIN")` 替换（16 处）

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`（3 处）
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeChunkController.java`（1 处，类级）
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java`（8 处）
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java`（4 处）

**Context:** Task 8 改了 `SaTokenStpInterfaceImpl.getRoleList` 后返回 `["SUPER_ADMIN"]`，现有的 `@SaCheckRole("admin")` 全部失效 —— 必须同步替换。见设计文档 4.5.1 节的完整清单。

- [ ] **Step 1: 先 grep 确认当前命中数**

```bash
cd E:/AIProject/ragent
grep -rn '@SaCheckRole("admin")' bootstrap/src/main/java/
grep -rn 'StpUtil\.checkRole("admin")' bootstrap/src/main/java/
```

Expected: 12 + 4 = 16 处。

- [ ] **Step 2: 用 `sed` 或手动 edit 批量替换**

由于 PR0 已经在 `KnowledgeBaseController.java` / `KnowledgeChunkController.java` 加了 `"admin"` 字符串，和 `RoleController.java` / `UserController.java` 原有的 `"admin"` 混在一起，一次性替换所有就行：

```bash
cd E:/AIProject/ragent
# 替换 @SaCheckRole("admin") → @SaCheckRole("SUPER_ADMIN")
find bootstrap/src/main/java -name '*.java' -exec sed -i 's|@SaCheckRole("admin")|@SaCheckRole("SUPER_ADMIN")|g' {} \;
# 替换 StpUtil.checkRole("admin") → StpUtil.checkRole("SUPER_ADMIN")
find bootstrap/src/main/java -name '*.java' -exec sed -i 's|StpUtil\.checkRole("admin")|StpUtil.checkRole("SUPER_ADMIN")|g' {} \;
```

Windows bash（Git bash / WSL）里 `sed -i` 可能有 BOM 问题 —— 如果跑出来有 UTF-8 编码问题，改用 IDE 的全局替换或逐文件 `Edit`。

- [ ] **Step 3: Grep 确认归零**

```bash
grep -rn '@SaCheckRole("admin")' bootstrap/src/main/java/
grep -rn 'StpUtil\.checkRole("admin")' bootstrap/src/main/java/
```

Expected: 两条命令都 **无输出**（0 命中）。

反向确认新字符串存在：
```bash
grep -rn '@SaCheckRole("SUPER_ADMIN")' bootstrap/src/main/java/ | wc -l
grep -rn 'StpUtil\.checkRole("SUPER_ADMIN")' bootstrap/src/main/java/ | wc -l
```

Expected: `12` + `4`。

- [ ] **Step 4: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeChunkController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/UserController.java
git commit -m "refactor: rename Sa-Token role 'admin' to 'SUPER_ADMIN' (16 sites)"
```

---

### Task 11: OpenSearch index mapping 加 `security_level` + `IndexerNode` 写入

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IndexerNode.java`

**Context:** 这是整个 PR 里最大的安全陷阱 —— 不加 mapping 就写入 `security_level` 会被静默丢弃。必须先声明 mapping 再让 IndexerNode 开始写。

- [ ] **Step 1: 修改 `OpenSearchVectorStoreAdmin.buildMappingJson` 加 `security_level`**

在 `metadata.properties` 块里加一行：

```java
// OpenSearchVectorStoreAdmin.java buildMappingJson 方法内
private String buildMappingJson(String analyzer, String searchAnalyzer, int dimension) {
    return """
            {
              "dynamic": false,
              "properties": {
                "id": { "type": "keyword" },
                "content": { "type": "text", "analyzer": "%s", "search_analyzer": "%s" },
                "embedding": {
                  "type": "knn_vector",
                  "dimension": %d,
                  "method": {
                    "name": "hnsw",
                    "space_type": "cosinesimil",
                    "engine": "lucene",
                    "parameters": { "ef_construction": 200, "m": 48 }
                  }
                },
                "metadata": {
                  "dynamic": false,
                  "properties": {
                    "doc_id": { "type": "keyword" },
                    "chunk_index": { "type": "integer" },
                    "task_id": { "type": "keyword", "index": false },
                    "pipeline_id": { "type": "keyword" },
                    "source_type": { "type": "keyword" },
                    "source_location": { "type": "keyword", "index": false },
                    "security_level": { "type": "integer" },
                    "keywords": {
                      "type": "text",
                      "analyzer": "%s",
                      "fields": { "raw": { "type": "keyword" } }
                    },
                    "summary": { "type": "text", "analyzer": "%s" }
                  }
                }
              }
            }
            """.formatted(analyzer, searchAnalyzer, dimension, searchAnalyzer, analyzer);
}
```

注意字段顺序：把 `security_level` 放在 `source_location` 和 `keywords` 之间，和现有字段风格一致。

- [ ] **Step 2: 修改 `IndexerNode` 写入 `security_level`**

先找 `IndexerNode.java` 中构建 metadata Map 的代码（大致是 `Map<String, Object> metadata = new HashMap<>();` 这种结构）。具体可能在一个辅助方法里，也可能内联在主流程。

加一行：

```java
// IndexerNode.java 构建 metadata 的位置
Map<String, Object> metadata = new HashMap<>();
metadata.put("doc_id", ctx.getDocId());
metadata.put("chunk_index", chunk.getIndex());
metadata.put("task_id", ctx.getTaskId());
metadata.put("pipeline_id", ctx.getPipelineId());
metadata.put("source_type", ctx.getSourceType());
metadata.put("source_location", ctx.getSourceLocation());
// ← 新增
Integer securityLevel = ctx.getDocument() != null ? ctx.getDocument().getSecurityLevel() : null;
metadata.put("security_level", securityLevel != null ? securityLevel : 0);
// ...
```

**关键**：`ctx.getDocument()` 拿到的是 `KnowledgeDocumentDO`，它现在有 `securityLevel` 字段了（Task 5）。如果 `IngestionContext` 没有 `getDocument()` 方法而是其他名字，请 grep `KnowledgeDocumentDO` 在 `ingestion` 包下的使用方式来适配。

默认值 `0`（PUBLIC）是兜底 —— 理论上 Task 16 后上传接口会让 `security_level` 非 null，但作为防御性编程写 0。

- [ ] **Step 3: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreAdmin.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/ingestion/node/IndexerNode.java
git commit -m "feat(vector): declare security_level in OS mapping and write it in IndexerNode"
```

---

### Task 12: `VectorStoreService.updateChunksMetadata` 接口 + OS 实现

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/VectorStoreService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/OpenSearchVectorStoreService.java`
- Modify (Milvus / pgvector impl，加 `UnsupportedOperationException` 占位)

**Context:** 为 Task 17 的异步 refresh consumer 准备接口。

- [ ] **Step 1: 在 `VectorStoreService` 接口里加方法**

```java
// VectorStoreService.java 追加
/**
 * 批量更新指定文档的所有 chunk 在向量库里的 metadata 字段（不动 vector）。
 *
 * <p>用于 {@code security_level} 等 document 级字段变更后的 chunk metadata 刷新。
 * OpenSearch 实现走 {@code POST {collection}/_update_by_query} 带 Painless script；
 * Milvus / pgvector 实现当前抛 {@code UnsupportedOperationException}（本 PR 外的 follow-up 补齐）。
 *
 * @param collectionName 目标向量集合/索引名
 * @param docId          文档 ID（用于 {@code metadata.doc_id} 过滤）
 * @param fields         要更新的字段名 → 新值
 */
void updateChunksMetadata(String collectionName, String docId, Map<String, Object> fields);
```

注意这需要在接口文件顶部加 `import java.util.Map;`（如果还没有）。

- [ ] **Step 2: `OpenSearchVectorStoreService` 实现**

```java
@Override
public void updateChunksMetadata(String collectionName, String docId, Map<String, Object> fields) {
    if (fields == null || fields.isEmpty()) {
        return;
    }
    // 构造 Painless script: ctx._source.metadata[key] = value
    // Painless 的 params 里可以直接传 Map，循环 set
    String paramsJson = buildParamsJson(fields);
    String requestBody = """
            {
              "script": {
                "source": "for (entry in params.fields.entrySet()) { ctx._source.metadata[entry.getKey()] = entry.getValue(); }",
                "params": { "fields": %s },
                "lang": "painless"
              },
              "query": {
                "term": { "metadata.doc_id": "%s" }
              }
            }
            """.formatted(paramsJson, escapeJson(docId));

    try (var response = client.generic().execute(
            Requests.builder()
                    .method("POST")
                    .endpoint(collectionName + "/_update_by_query?refresh=true&wait_for_completion=true")
                    .json(requestBody)
                    .build())) {
        if (response.getStatus() >= 300) {
            throw new RuntimeException("OpenSearch _update_by_query failed with status " + response.getStatus()
                    + " for collection=" + collectionName + ", docId=" + docId);
        }
    } catch (Exception e) {
        throw new RuntimeException("Failed to updateChunksMetadata on OpenSearch", e);
    }
}

private String buildParamsJson(Map<String, Object> fields) {
    // 手写最小 JSON —— 避免拖 Jackson 的依赖到这里
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : fields.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        sb.append('"').append(escapeJson(e.getKey())).append('"').append(':');
        Object v = e.getValue();
        if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(v.toString())).append('"');
        }
    }
    sb.append('}');
    return sb.toString();
}
```

如果 `OpenSearchVectorStoreService` 里已经有 `escapeJson` / `OpenSearchClient client` 等依赖，直接复用；如果没有，参照 `OpenSearchRetrieverService` 里的实现 copy-paste 一份（本 PR 为求最小改动可以接受少量重复，PR3 可以抽公共 utility）。

- [ ] **Step 3: Milvus / pgvector 实现加占位**

找到 `MilvusVectorStoreService.java`（或类似名）和 `PgVectorStoreService.java`，追加：

```java
@Override
public void updateChunksMetadata(String collectionName, String docId, Map<String, Object> fields) {
    throw new UnsupportedOperationException(
        "updateChunksMetadata is not supported by this vector store (current PR only targets OpenSearch)");
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/*.java
git commit -m "feat(vector): add VectorStoreService.updateChunksMetadata (OS impl only)"
```

---

### Task 13: `RetrieveRequest` + `SearchContext` 类型升级

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/RetrieveRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java`

**Context:** 把 `metadataFilters` 从 `Map<String, Object>` 换成 `List<MetadataFilter>`（零兼容成本 —— 当前无上游设置过它）。同时给 `SearchContext` 加 `maxSecurityLevel`。

- [ ] **Step 1: 修改 `RetrieveRequest.metadataFilters` 类型**

找到原定义（约在 L66）：
```java
private Map<String, Object> metadataFilters;
```

改为：
```java
private java.util.List<MetadataFilter> metadataFilters;
```

或者在 import 区加 `import java.util.List;` 和 `import com.nageoffer.ai.ragent.rag.core.retrieve.MetadataFilter;` 后：
```java
private List<MetadataFilter> metadataFilters;
```

并把 `import java.util.Map;` 如果不再需要就删除。

- [ ] **Step 2: `SearchContext` 加 `maxSecurityLevel` 字段**

在 builder 类里加：
```java
/**
 * 当前用户的最大安全等级（跨所有挂载角色取最大值）。
 * null 表示"不加 security_level 过滤"（系统调用路径，例如 MQ 消费者）。
 */
private Integer maxSecurityLevel;
```

加对应 `@Builder` 字段，Lombok 自动生成 setter。确认 class 头有 `@Builder`。

- [ ] **Step 3: 编译 — 会发现下游 retriever 的 signature 坏了**

```bash
mvn -pl bootstrap compile -q
```

Expected: **编译失败**。因为：
- `OpenSearchRetrieverService.doSearch(...)` 的参数还是 `Map<String, Object> metadataFilters`
- `MilvusRetrieverService` / `PgRetrieverService` 同理

这些错误会在 Task 14 / 15 里一起修复。Task 13 **不** commit 到这里，要把它和 Task 14 / 15 合并成一个逻辑工作单元。

**替代方案**：如果你想让这一步也能 commit、保持可运行，可以先在 `RetrieveRequest` 里**并行**保留两个字段（旧 Map 加 `@Deprecated`，新 List），下游 retriever 暂时读 Map 直到 Task 14 / 15 切换。但这会引入 "dead field" 临时状态，不干净。

**推荐做法**：把 Task 13 / 14 / 15 **合并成一个 commit**。任务本身分节只是便于阅读。下面的 Task 14 和 Task 15 不单独 commit，等到 Task 15 结尾统一 commit。

---

### Task 14: 3 个 retriever 调用点注入 `metadataFilters`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/CollectionParallelRetriever.java`

**Context:** 从 `SearchContext.maxSecurityLevel` 构造 `List<MetadataFilter>`，注入到 `RetrieveRequest`。接续 Task 13，和 Task 15 一起 commit。

- [ ] **Step 1: `MultiChannelRetrievalEngine.buildSearchContext` 从 UserContext 取 maxSecurityLevel**

找到 `buildSearchContext` 方法（约 L247），加一行：

```java
private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK,
                                          Set<String> accessibleKbIds) {
    String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();

    return SearchContext.builder()
            .originalQuestion(question)
            .rewrittenQuestion(question)
            .intents(subIntents)
            .topK(topK)
            .accessibleKbIds(accessibleKbIds)
            .maxSecurityLevel(resolveMaxSecurityLevel())   // ← 新增
            .build();
}

/**
 * 从 UserContext 解析当前用户的最大 security_level。
 *
 * <p>系统调用路径（MQ 消费者、定时任务等无 UserContext 的场景）返回 null —— 下游
 * {@code buildMetadataFilters} 会据此不加 security_level 过滤条件，等价于"全通"。
 * 未来 security_level 上限变化（比如扩充到 4/5）时不用改这里。
 */
private Integer resolveMaxSecurityLevel() {
    if (UserContext.hasUser()) {
        return UserContext.get().getMaxSecurityLevel();
    }
    return null;
}
```

- [ ] **Step 2: 加 `buildMetadataFilters` helper 并注入到单 KB 定向检索路径**

继续在 `MultiChannelRetrievalEngine` 里加 helper：

```java
/**
 * 根据 SearchContext 构造类型化的 metadata 过滤器列表。
 *
 * <p>当前仅产出 {@code security_level <= maxLevel} 过滤；后续新增字段只需在这里 append。
 */
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

确认 import 区有 `import com.nageoffer.ai.ragent.rag.core.retrieve.MetadataFilter;` 和 `import java.util.ArrayList;`。

然后在 `retrieveKnowledgeChannels` 的单 KB 路径（约 L83）里用它：

```java
// 单知识库定向检索路径
if (knowledgeBaseId != null) {
    KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(knowledgeBaseId);
    if (kb == null || kb.getCollectionName() == null) {
        return List.of();
    }
    RetrieveRequest req = RetrieveRequest.builder()
            .query(context.getMainQuestion())
            .topK(topK)
            .collectionName(kb.getCollectionName())
            .metadataFilters(buildMetadataFilters(context))   // ← 新增
            .build();
    List<RetrievedChunk> chunks = retrieverService.retrieve(req);
    // ...
}
```

- [ ] **Step 3: `IntentParallelRetriever.createRetrievalTask` 接收并注入 metadataFilters**

`IntentParallelRetriever` 目前不感知 SearchContext，要改造让它接收 metadata filters。最小改动：构造器或方法参数加一个 `Supplier<List<MetadataFilter>>` 或直接参数传入。

看 Task 14 的目标是"让 retriever 把 filter 传给 OpenSearch"，最小改动路径：给 `createRetrievalTask` 的签名加 filter 参数。

但 `createRetrievalTask` 是继承自 `AbstractParallelRetriever<T>` 的抽象方法，改 signature 要连基类一起改。

**更简洁的方案**：让具体的 retriever 持有一个 `Supplier<List<MetadataFilter>>`，由 `executeParallelRetrieval` 的调用方（`MultiChannelRetrievalEngine`）在构造 SearchContext 时设置。

实施：

在 `IntentParallelRetriever` 里加一个 ThreadLocal 或字段，持有"当前调用的 filter list"。但 ThreadLocal 在并行执行里有坑。

**最干净**：`IntentParallelRetriever.executeParallelRetrieval` 的签名加一个 `List<MetadataFilter> metadataFilters` 参数，内部 `createRetrievalTask` 从闭包里捕获它。

看当前 `IntentParallelRetriever.executeParallelRetrieval(String question, List<NodeScore> targets, int fallbackTopK, int topKMultiplier)` 签名（L52），加一个参数：

```java
public List<RetrievedChunk> executeParallelRetrieval(String question,
                                                     List<NodeScore> targets,
                                                     int fallbackTopK,
                                                     int topKMultiplier,
                                                     List<MetadataFilter> metadataFilters) {
    this.currentFilters = metadataFilters;   // 字段缓存
    try {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> new IntentTask(
                        nodeScore,
                        resolveIntentTopK(nodeScore, fallbackTopK, topKMultiplier)
                ))
                .toList();
        return super.executeParallelRetrieval(question, intentTasks, fallbackTopK);
    } finally {
        this.currentFilters = null;
    }
}
```

加字段 `private volatile List<MetadataFilter> currentFilters;`。

然后 `createRetrievalTask` 读它：
```java
@Override
protected List<RetrievedChunk> createRetrievalTask(String question, IntentTask task, int ignoredTopK) {
    NodeScore nodeScore = task.nodeScore();
    IntentNode node = nodeScore.getNode();
    try {
        return retrieverService.retrieve(
                RetrieveRequest.builder()
                        .collectionName(node.getCollectionName())
                        .query(question)
                        .topK(task.intentTopK())
                        .metadataFilters(currentFilters)   // ← 新增
                        .build()
        );
    } catch (Exception e) {
        log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collection: {}, 错误: {}",
                node.getId(), node.getName(), node.getCollectionName(), e.getMessage(), e);
        return List.of();
    }
}
```

**并发注意**：`IntentParallelRetriever` 是 Spring 单例，如果多个请求同时调 `executeParallelRetrieval`，`currentFilters` 字段会相互覆盖。**这是 race condition**。

**更安全的做法**：让 `IntentTask` 本身携带 filter：

```java
public record IntentTask(NodeScore nodeScore, int intentTopK, List<MetadataFilter> metadataFilters) {}

// createRetrievalTask：
return retrieverService.retrieve(
        RetrieveRequest.builder()
                .collectionName(node.getCollectionName())
                .query(question)
                .topK(task.intentTopK())
                .metadataFilters(task.metadataFilters())
                .build()
);
```

并把 `IntentTask` 的构造也改：

```java
List<IntentTask> intentTasks = targets.stream()
        .map(nodeScore -> new IntentTask(
                nodeScore,
                resolveIntentTopK(nodeScore, fallbackTopK, topKMultiplier),
                metadataFilters
        ))
        .toList();
```

这是线程安全的方案，用它。

- [ ] **Step 4: `CollectionParallelRetriever` 同样改造**

现在 `CollectionParallelRetriever` 的 `createRetrievalTask(String question, String collectionName, int topK)` 签名只收 collectionName。同样改为让 task 对象携带 filter：

把 `AbstractParallelRetriever<String>` 的类型参数换成 `AbstractParallelRetriever<CollectionTask>`，其中：

```java
public record CollectionTask(String collectionName, List<MetadataFilter> metadataFilters) {}
```

然后所有引用 `String collectionName` 的地方改为 `task.collectionName()`。改动点：
- 类声明 `extends AbstractParallelRetriever<CollectionTask>`
- `createRetrievalTask(String question, CollectionTask task, int topK)` 签名
- `getTargetIdentifier(CollectionTask task)` 返回 `"Collection: " + task.collectionName()`

**实际上**：基类 `AbstractParallelRetriever<T>` 的 `executeParallelRetrieval(question, targets, topK)` 接的是 `List<T>`，调用方需要把 `List<String>` 变成 `List<CollectionTask>`。调用方是 `CollectionSearchChannel` 或类似 —— 找到它并修改。

由于这层链路比较深，以下是具体步骤：

1. 打开 `CollectionParallelRetriever.java`，改类型参数为 `CollectionTask`
2. 新定义 `CollectionTask` record
3. 找 `CollectionParallelRetriever.executeParallelRetrieval` 的调用方（grep `CollectionParallelRetriever` 或 `collectionParallelRetriever`），通常在某个 `SearchChannel` 实现里。让调用方把 `List<String>` 转成 `List<CollectionTask>`，并传入 `metadataFilters`。

```java
// 调用方伪代码（找到实际位置改成真实代码）
List<CollectionTask> tasks = collectionNames.stream()
        .map(name -> new CollectionTask(name, searchContext.getMetadataFilters()))  
        // wait: searchContext 没有 metadataFilters 字段
        // 实际用 buildMetadataFilters helper，或者让 channel 自己构造
        .toList();
```

**简化**：SearchChannel 调用 retriever 时已经持有 `SearchContext`，`SearchContext` 现在有 `maxSecurityLevel`。在 channel 里调 helper：

```java
private List<MetadataFilter> buildMetadataFilters(SearchContext ctx) {
    List<MetadataFilter> filters = new ArrayList<>();
    if (ctx.getMaxSecurityLevel() != null) {
        filters.add(new MetadataFilter("security_level", MetadataFilter.FilterOp.LTE, ctx.getMaxSecurityLevel()));
    }
    return filters;
}
```

这个 helper 会出现在 `MultiChannelRetrievalEngine`、`CollectionSearchChannel`、`IntentDirectedSearchChannel` 三个地方（如果存在）。**DRY**：抽到一个 utility，比如 `MetadataFilterBuilder.fromContext(ctx)`。

**最小实用方案**：先在 `MultiChannelRetrievalEngine` 里定义 public static helper，三处都调用这个 static helper。属于合理的 DRY。

```java
// MultiChannelRetrievalEngine.java
public static List<MetadataFilter> buildMetadataFilters(SearchContext ctx) {
    List<MetadataFilter> filters = new ArrayList<>();
    if (ctx.getMaxSecurityLevel() != null) {
        filters.add(new MetadataFilter("security_level", MetadataFilter.FilterOp.LTE, ctx.getMaxSecurityLevel()));
    }
    return filters;
}
```

然后 Intent/Collection channel 引用 `MultiChannelRetrievalEngine.buildMetadataFilters(ctx)`。

- [ ] **Step 5: 合并后的编译尚未通过 —— 等 Task 15 完成再编译**

Task 14 到此只改了调用方，retriever 本身的 `buildFilterClause` 还没适配新类型。继续到 Task 15。

---

### Task 15: `OpenSearchRetrieverService.buildFilterClause` 类型化重写

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java`
- Modify (若存在): `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java`
- Modify (若存在): `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/PgRetrieverService.java`

**Context:** `RetrieveRequest.metadataFilters` 现在是 `List<MetadataFilter>`，`doSearch` / `buildFilterClause` 等方法的参数类型要同步改。

- [ ] **Step 1: 修改 `OpenSearchRetrieverService.doSearch` 签名**

```java
// 原
private List<RetrievedChunk> doSearch(String collectionName, String query, float[] vector,
                                      int topK, Map<String, Object> metadataFilters) { ... }

// 改
private List<RetrievedChunk> doSearch(String collectionName, String query, float[] vector,
                                      int topK, List<MetadataFilter> metadataFilters) { ... }
```

同步修改 `buildHybridQuery` / `buildKnnOnlyQuery` 签名。

- [ ] **Step 2: 重写 `buildFilterClause` 用 switch 分发**

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

确认 import：
- `java.util.List`
- `com.nageoffer.ai.ragent.rag.core.retrieve.MetadataFilter`
- `java.util.stream.Collectors` (应该已有)

如果原代码里有 `import java.util.Map;` 且不再使用，删除。

- [ ] **Step 3: `retrieveParam.getMetadataFilters()` 引用处类型自动兼容**

因为 `RetrieveRequest.metadataFilters` 在 Task 13 改成 `List<MetadataFilter>` 了，这里 `retrieveParam.getMetadataFilters()` 的返回类型自动变。调用方 `doSearch` 传入 `retrieveParam.getMetadataFilters()` 即可。

- [ ] **Step 4: Milvus / pgvector 的 retriever 同样改**

打开 `MilvusRetrieverService.java` 和 `PgRetrieverService.java`，把它们的 `doSearch` / `buildFilter` 等方法的 `Map<String, Object>` 参数也改成 `List<MetadataFilter>`。

**简化**：如果 Milvus/pg impl 里也有 `buildFilter` 逻辑，可以先全部改成：
```java
if (metadataFilters != null && !metadataFilters.isEmpty()) {
    throw new UnsupportedOperationException(
        "Metadata filter is currently only supported by OpenSearch vector store");
}
```

反正开发环境只用 OS（运营约束）。Milvus/pg 的真正 filter 实现留给后续 PR。

- [ ] **Step 5: 编译 — 到这里整个 Task 13-15 应该一起过**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS. 如果还有错，根据错误定位到相应文件补改。常见可能漏改的点：
- `RetrieverService` 接口有默认方法 `retrieve(String query, int topK)` —— 它构造 RetrieveRequest 时没设 metadataFilters，但这是合法的（null 默认）
- `PgRetrieverService` 如果同样用 `Map` 字段，也要改

- [ ] **Step 6: Commit Task 13 + 14 + 15 合并**

这是三个 task 合并的一个 commit，因为中间状态编译不过：

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/
git commit -m "feat(retrieve): activate metadataFilters pipeline with typed MetadataFilter"
```

---

### Task 16: 文档上传/更新 API 加 `securityLevel` 字段

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUpdateRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

**Context:** 接受 `security_level` 上传参数，默认 0。更新接口先只支持 security_level 字段修改 —— 完整的 RocketMQ 异步刷新在 Task 17。

- [ ] **Step 1: `KnowledgeDocumentUploadRequest` 加字段**

```java
/**
 * 文档安全等级：0=PUBLIC（默认）, 1=INTERNAL, 2=CONFIDENTIAL, 3=RESTRICTED
 */
private Integer securityLevel;
```

- [ ] **Step 2: `KnowledgeDocumentUpdateRequest` 加字段**

```java
/**
 * 新的安全等级。null 表示不修改。
 */
private Integer securityLevel;
```

- [ ] **Step 3: `KnowledgeDocumentServiceImpl.upload` 写入 security_level**

找到 `upload` 方法里构建 `KnowledgeDocumentDO` 的地方（`KnowledgeDocumentDO.builder()...`），加：

```java
.securityLevel(requestParam.getSecurityLevel() != null ? requestParam.getSecurityLevel() : 0)
```

- [ ] **Step 4: `KnowledgeDocumentServiceImpl.update` 处理 security_level 变更**

找到 `update` 方法（在 L500 附近），在最后加一段：

```java
// 处理 security_level 变更 —— 写 PG + 触发 RocketMQ 异步刷新 OpenSearch
if (requestParam.getSecurityLevel() != null) {
    KnowledgeDocumentDO currentDoc = documentMapper.selectById(docId);
    int oldLevel = currentDoc.getSecurityLevel() == null ? 0 : currentDoc.getSecurityLevel();
    int newLevel = requestParam.getSecurityLevel();
    if (oldLevel != newLevel) {
        updateWrapper.set(KnowledgeDocumentDO::getSecurityLevel, newLevel);
        // 记下待发送的事件（Task 17 的 producer 会接住）—— 为了事务性，先在
        // PG 提交后再发 MQ，见 Task 17 的实现
        pendingSecurityLevelRefresh = new SecurityLevelRefreshEvent(
            docId, knowledgeBaseMapper.selectById(currentDoc.getKbId()).getCollectionName(), newLevel);
    }
}
```

注意：这里 `pendingSecurityLevelRefresh` 是一个本地变量；`SecurityLevelRefreshEvent` 类在 Task 17 创建。**本 task 的 update 代码先不 import / 引用 `SecurityLevelRefreshEvent`** —— 把 MQ 发送的占位留给 Task 17。

实际上，最干净的做法：**Task 16 不改 update 方法里的 security_level 分支**，只接受字段但不触发刷新；Task 17 在 `update` 方法里补上 MQ 发送逻辑。

所以本 task 的 Step 4 改为：

```java
// Task 16 只接受字段、不写 PG 里的 security_level（等 Task 17 一起处理）
// 实际上如果想先让 upload 路径 work、update 路径暂时 broken/empty，也 OK
```

实施决定：**Task 16 只改 upload 和两个 Request 类**。update 的 security_level 逻辑全部放到 Task 17。

调整 Step 4：

```java
// Task 16 不改 update 方法 —— 留给 Task 17
```

- [ ] **Step 5: 编译**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUploadRequest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeDocumentUpdateRequest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "feat(knowledge): accept securityLevel in document upload API"
```

---

### Task 17: RocketMQ 异步 `security_level` 刷新

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/SecurityLevelRefreshEvent.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentSecurityLevelRefreshConsumer.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

**Context:** 文档 security_level 修改后，通过 RocketMQ 异步刷新 OpenSearch 里所有 chunk 的 metadata。设计参照 `KnowledgeDocumentChunkConsumer` 的模式。

- [ ] **Step 1: 创建 `SecurityLevelRefreshEvent` record**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.mq;

/**
 * 文档 security_level 变更事件。
 * Consumer 接到该事件后调 {@code VectorStoreService.updateChunksMetadata} 刷新 OpenSearch。
 */
public record SecurityLevelRefreshEvent(
        String docId,
        String collectionName,
        int newSecurityLevel
) {
}
```

- [ ] **Step 2: 创建 Consumer**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消费 {@link SecurityLevelRefreshEvent}，通过 {@code VectorStoreService.updateChunksMetadata}
 * 刷新 OpenSearch 里所有相关 chunk 的 metadata。
 *
 * <p>失败会触发 RocketMQ 标准重试（默认 16 次指数退避），最终失败进 DLQ。DLQ 监控是
 * 本 PR 之外的 follow-up（见设计文档开放问题 5）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge_document_topic",
        selectorExpression = "security_level_refresh",
        consumerGroup = "security_level_refresh_group"
)
public class KnowledgeDocumentSecurityLevelRefreshConsumer
        implements RocketMQListener<SecurityLevelRefreshEvent> {

    private final VectorStoreService vectorStoreService;

    @Override
    public void onMessage(SecurityLevelRefreshEvent event) {
        log.info("收到 security_level 刷新事件: docId={}, newLevel={}", event.docId(), event.newSecurityLevel());
        vectorStoreService.updateChunksMetadata(
                event.collectionName(),
                event.docId(),
                Map.of("security_level", event.newSecurityLevel())
        );
        log.info("security_level 刷新完成: docId={}", event.docId());
    }
}
```

**前置确认**：
- 确认项目里 `org.apache.rocketmq.spring` 依赖存在（看 `bootstrap/pom.xml`），以及有无 `RocketMQListener` 示例可参考（grep `RocketMQListener`）
- 确认 `knowledge_document_topic` 是当前项目用的 topic（如果不是，用别的 topic 名）

如果项目用的是 RocketMQ 5.x 的新 client（不是 `rocketmq-spring`），API 不同，参考 `KnowledgeDocumentChunkConsumer` 的实际风格抄。

- [ ] **Step 3: 在 `KnowledgeDocumentServiceImpl.update` 里发送事件**

打开 `KnowledgeDocumentServiceImpl`，注入 `RocketMQTemplate`（或项目里的等价 MQ Producer），然后在 update 方法里加 security_level 处理逻辑：

```java
// KnowledgeDocumentServiceImpl 类字段
private final RocketMQTemplate rocketMQTemplate;

// update 方法末尾（事务提交后发 MQ）
@Override
@Transactional
public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
    // ... existing update logic ...

    // security_level 变更：写 PG 后发异步刷新事件
    if (requestParam.getSecurityLevel() != null) {
        KnowledgeDocumentDO current = documentMapper.selectById(docId);
        int oldLevel = current.getSecurityLevel() == null ? 0 : current.getSecurityLevel();
        int newLevel = requestParam.getSecurityLevel();
        if (oldLevel != newLevel) {
            documentMapper.update(
                    Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                            .eq(KnowledgeDocumentDO::getId, docId)
                            .set(KnowledgeDocumentDO::getSecurityLevel, newLevel)
            );
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(current.getKbId());
            SecurityLevelRefreshEvent event = new SecurityLevelRefreshEvent(
                    docId, kb.getCollectionName(), newLevel);
            // 走 RocketMQ —— 注意这里需要事务消息保证 PG 提交 + MQ 发送原子
            rocketMQTemplate.syncSend(
                    "knowledge_document_topic:security_level_refresh",
                    event
            );
            log.info("已提交 security_level 刷新事件: docId={}, {} -> {}", docId, oldLevel, newLevel);
        }
    }
}
```

**事务消息的正确实现**：`syncSend` 不是事务消息，会在 PG 事务**失败的时候也发出**。正确做法：
- 用 `rocketMQTemplate.sendMessageInTransaction(...)` +  `RocketMQLocalTransactionListener`，或
- 用 Spring 的 `TransactionSynchronizationManager.registerSynchronization` 在 afterCommit 里发送（更简单）

选后者：

```java
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// ...
if (oldLevel != newLevel) {
    documentMapper.update(
            Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                    .eq(KnowledgeDocumentDO::getId, docId)
                    .set(KnowledgeDocumentDO::getSecurityLevel, newLevel)
    );
    KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(current.getKbId());
    SecurityLevelRefreshEvent event = new SecurityLevelRefreshEvent(
            docId, kb.getCollectionName(), newLevel);

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rocketMQTemplate.syncSend("knowledge_document_topic:security_level_refresh", event);
                log.info("security_level 刷新事件已发出: docId={}", event.docId());
            }
        });
    } else {
        // 非事务上下文直接发
        rocketMQTemplate.syncSend("knowledge_document_topic:security_level_refresh", event);
    }
}
```

这样 PG 回滚时 MQ 也不会发。只要 PG 提交后 MQ 发送失败（消息丢失），我们需要定期对账 —— 暂时不做，放 follow-up。

- [ ] **Step 4: 编译**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS. 常见坑：
- `RocketMQTemplate` 找不到 → 确认 `rocketmq-spring-boot-starter` 依赖已引入，且有 `@Autowired` 合理
- `Wrappers.lambdaUpdate` 的 import 缺 → `com.baomidou.mybatisplus.core.toolkit.Wrappers`

- [ ] **Step 5: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/SecurityLevelRefreshEvent.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentSecurityLevelRefreshConsumer.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java
git commit -m "feat(knowledge): async security_level refresh via RocketMQ"
```

---

### Task 18: 知识库创建 API 加 `deptId` + DEPT_ADMIN 约束

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBaseCreateRequest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`

**Context:** 创建知识库时接受 dept_id；DEPT_ADMIN 被强制设为自己部门；SUPER_ADMIN 可任意指定。

- [ ] **Step 1: `KnowledgeBaseCreateRequest` 加字段**

```java
/**
 * 归属部门 ID。
 * - SUPER_ADMIN 创建时：由请求参数指定
 * - DEPT_ADMIN 创建时：被强制覆盖为 user.dept_id（忽略请求参数）
 */
private String deptId;
```

- [ ] **Step 2: `KnowledgeBaseServiceImpl.create` 校验 + 覆盖**

在 `create` 方法里，在 `KnowledgeBaseDO.builder()` 之前加一段：

```java
// 解析 dept_id
String effectiveDeptId;
LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
if (user == null) {
    effectiveDeptId = requestParam.getDeptId() != null ? requestParam.getDeptId() : "GLOBAL";
} else if (user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.SUPER_ADMIN)) {
    // SUPER_ADMIN 可任意指定；未指定时默认 GLOBAL
    effectiveDeptId = requestParam.getDeptId() != null ? requestParam.getDeptId() : "GLOBAL";
} else if (user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.DEPT_ADMIN)) {
    if (user.getDeptId() == null) {
        throw new ClientException("DEPT_ADMIN 必须有 dept_id 才能创建知识库");
    }
    effectiveDeptId = user.getDeptId();
} else {
    throw new ClientException("无创建知识库权限");
}
```

然后 builder 链里加：
```java
.deptId(effectiveDeptId)
```

添加相应 import：
- `com.nageoffer.ai.ragent.framework.context.LoginUser`
- `com.nageoffer.ai.ragent.framework.context.RoleType`
- `com.nageoffer.ai.ragent.framework.context.UserContext`（已有）
- `com.nageoffer.ai.ragent.framework.exception.ClientException`（已有）

- [ ] **Step 3: 编译**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
mvn -pl bootstrap spotless:apply
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/request/KnowledgeBaseCreateRequest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java
git commit -m "feat(knowledge): KB create accepts deptId, DEPT_ADMIN bound to self.dept"
```

---

### Task 19: `init_data_pg.sql` 插入默认部门 / 角色 / admin

**Files:**
- Modify: `resources/database/init_data_pg.sql`
- Modify: `resources/database/init_data_full_pg.sql`

**Context:** 让 rebuild 后 admin 用户可登录且有 SUPER_ADMIN 角色。

- [ ] **Step 1: 编辑 `init_data_pg.sql`**

找到现有的 `t_user` / `t_role` 初始化 INSERT 语句（如果有），在合适位置加：

```sql
-- 默认部门
INSERT INTO sys_dept (id, dept_code, dept_name) VALUES
    ('1', 'GLOBAL', '全局部门');

-- 默认角色
INSERT INTO t_role (id, name, description, role_type, max_security_level) VALUES
    ('1', '超级管理员', '系统超级管理员，绕过所有 RBAC 过滤', 'SUPER_ADMIN', 3),
    ('2', '普通用户',   '默认普通用户角色',                          'USER',        0);

-- admin 用户（password 是明文 '123456'，和现有 AuthServiceImpl.passwordMatches 的明文比对一致）
INSERT INTO t_user (id, username, password, role, dept_id, avatar) VALUES
    ('1', 'admin', '123456', 'SUPER_ADMIN', '1', 'https://avatars.githubusercontent.com/u/583231?v=4');

-- 用户-角色关联
INSERT INTO t_user_role (id, user_id, role_id) VALUES
    ('1', '1', '1');
```

**注意**：如果 `init_data_pg.sql` 里原本有 admin 用户的 INSERT（带 `role='admin'`），要**替换**而不是重复插入 —— 否则主键冲突。

- [ ] **Step 2: 同步到 `init_data_full_pg.sql`**

同样的 INSERT，用 pg_dump 格式（`INSERT INTO public.xxx ...`）。

- [ ] **Step 3: Dry-run 验证**

```bash
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent_dryrun2;"
docker exec -i postgres psql -U postgres -d ragent_dryrun2 < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent_dryrun2 < resources/database/init_data_pg.sql
docker exec postgres psql -U postgres -d ragent_dryrun2 -c "SELECT u.username, d.dept_code, r.role_type FROM t_user u JOIN sys_dept d ON u.dept_id = d.id JOIN t_user_role ur ON ur.user_id = u.id JOIN t_role r ON r.id = ur.role_id WHERE u.username = 'admin';"
docker exec postgres psql -U postgres -c "DROP DATABASE ragent_dryrun2;"
```

Expected: 1 行 `admin | GLOBAL | SUPER_ADMIN`。

- [ ] **Step 4: Commit**

```bash
git add resources/database/init_data_pg.sql resources/database/init_data_full_pg.sql
git commit -m "feat(db): seed GLOBAL dept, SUPER_ADMIN/USER roles, admin user-role link"
```

---

### Task 20: 整体 rebuild + smoke test

**Files:**（无代码改动）

**Context:** 这是 PR1 的验收核心。按设计文档第六节的 checklist 执行。

- [ ] **Step 1: 停掉当前 Spring Boot 进程**

按 Ctrl+C 停掉后端（如果还在跑）。

- [ ] **Step 2: 清 PG**

```bash
docker exec postgres psql -U postgres -c "DROP DATABASE ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql
```

- [ ] **Step 3: 清 OpenSearch**

```bash
curl -X DELETE "http://localhost:9200/_all"
# Expected: {"acknowledged":true}
```

- [ ] **Step 4: 清 Redis**

```bash
docker exec redis redis-cli FLUSHDB
# Expected: OK
```

- [ ] **Step 5: 清 S3 桶（RustFS）**

方法一：手动通过管理台清空每个 bucket。
方法二：用 `mc` 工具：
```bash
# 示例 —— 具体 endpoint/alias 按项目配置
mc alias set local http://localhost:<rustfs-port> <ak> <sk>
mc ls local
mc rb --force local/<bucket-name>  # 对每个 bucket
```

- [ ] **Step 6: 启动后端**

```bash
$env:NO_PROXY='localhost,127.0.0.1'
$env:no_proxy='localhost,127.0.0.1'
mvn -pl bootstrap spring-boot:run
```

等待 Spring Boot 启动成功（"Started RagentCoreApplication"）。

- [ ] **Step 7: 烟测序列**

执行以下 curl 序列，每一步都要确认预期输出：

```bash
# 1. 登录
TOKEN=$(curl -s -X POST http://localhost:9090/api/ragent/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
echo "TOKEN=$TOKEN"
# Expected: 非空 JWT 字符串

# 2. 创建知识库
curl -s -X POST http://localhost:9090/api/ragent/knowledge-base \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"smoke_test_kb","embeddingModel":"text-embedding-3-small","collectionName":"smoke_test_kb","deptId":"1"}'
# Expected: {"code":"0","data":"<kb_id>","message":"success"}

KB_ID=<填上面返回的 kb_id>

# 3. 上传文档（security_level=0 PUBLIC）
curl -s -X POST "http://localhost:9090/api/ragent/knowledge-base/$KB_ID/docs/upload" \
  -H "Authorization: $TOKEN" \
  -F "file=@/path/to/test.pdf" \
  -F "securityLevel=0"
# Expected: 返回 doc_id

# 4. 分块 + 向量化
curl -s -X POST "http://localhost:9090/api/ragent/knowledge-base/docs/<doc_id>/chunk" \
  -H "Authorization: $TOKEN"
# Expected: success，观察后台日志等分块完成

# 5. 发起问答
curl -N "http://localhost:9090/api/ragent/rag/v3/chat?question=测试问题&kbId=$KB_ID" \
  -H "Authorization: $TOKEN"
# Expected: SSE 流式返回答案

# 6. 把文档提权到 CONFIDENTIAL（level=2）
curl -s -X PUT "http://localhost:9090/api/ragent/knowledge-base/docs/<doc_id>" \
  -H "Authorization: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"securityLevel":2}'
# Expected: success；后台日志应出现 "security_level 刷新事件已发出" 和 "security_level 刷新完成"

# 7. 验证 admin 还能检索到（因为 SUPER_ADMIN 绕过过滤）
curl -N "http://localhost:9090/api/ragent/rag/v3/chat?question=测试问题&kbId=$KB_ID" \
  -H "Authorization: $TOKEN"
# Expected: 依然返回正常答案

# 8. 创建一个 max_security_level=1 的普通用户角色 + 用户（通过 SQL 直接插，或走角色管理 API）
docker exec postgres psql -U postgres -d ragent -c "
  INSERT INTO t_role (id, name, role_type, max_security_level) VALUES ('3', '初级员工', 'USER', 1);
  INSERT INTO t_user (id, username, password, role, dept_id) VALUES ('2', 'junior', '123456', 'USER', '1');
  INSERT INTO t_user_role (id, user_id, role_id) VALUES ('2', '2', '3');
  INSERT INTO t_role_kb_relation (id, role_id, kb_id, permission) VALUES ('1', '3', '$KB_ID', 'READ');
"

# 9. 以 junior 登录
JUNIOR_TOKEN=$(curl -s -X POST http://localhost:9090/api/ragent/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"junior","password":"123456"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

# 10. 以 junior 检索 —— 关键断言：该文档不应出现在检索结果里（security_level=2 > junior.maxLevel=1）
curl -N "http://localhost:9090/api/ragent/rag/v3/chat?question=测试问题&kbId=$KB_ID" \
  -H "Authorization: $JUNIOR_TOKEN"
# Expected: SSE 流结束时显示"未检索到相关内容"或类似 —— 且后台日志中
# buildFilterClause 的 OpenSearch DSL 包含 {"range":{"metadata.security_level":{"lte":1}}}
```

- [ ] **Step 8: 观察 log 确认 `security_level` filter 真的被注入**

查看后端 log 中的 OpenSearch query DSL（如果有 log 了）。期望看到类似：

```
OpenSearch search query: {..."filter":[{"term":{"metadata.kb_id":...}}, {"range":{"metadata.security_level":{"lte":1}}}]}
```

如果没看到这个 filter，说明 SearchContext.maxSecurityLevel 没被注入，或者 buildFilterClause 没正常工作。定位后回到 Task 13 / 14 / 15 修正。

- [ ] **Step 9: 记录烟测结果到 commit**

```bash
git commit --allow-empty -m "test: PR1 smoke test passed (dev rebuild + junior security filter)"
```

或者把 smoke test 步骤留作后续手动执行记录，不 commit。

---

## 四、Self-Review Checklist

**在认为计划完成前，自行走一遍以下检查。**

### 1. Spec coverage

- [ ] 设计文档 section 4.1 Schema 变更 → Task 1
- [ ] 设计文档 section 4.2 领域实体变更 → Task 2 (enums), Task 4 (LoginUser), Task 5 (DOs), Task 6 (SysDept)
- [ ] 设计文档 section 4.3 UserContextInterceptor 多角色装载 → Task 8
- [ ] 设计文档 section 4.4 KbAccessService 接口升级 → Task 9
- [ ] 设计文档 section 4.5 Sa-Token 集成适配 → Task 8
- [ ] 设计文档 section 4.5.1 @SaCheckRole 替换 → Task 10
- [ ] 设计文档 section 4.6 OpenSearch mapping → Task 11
- [ ] 设计文档 section 4.7 IndexerNode → Task 11
- [ ] 设计文档 section 4.8 检索链路 metadataFilters → Task 3 (type), Task 13 (RetrieveRequest), Task 14 (call-sites), Task 15 (buildFilterClause)
- [ ] 设计文档 section 4.9 API 变更 → Task 16 (upload/update), Task 17 (async refresh), Task 18 (KB create)
- [ ] 设计文档 section 4.10 初始化数据 → Task 19
- [ ] 设计文档 section 六 重建 checklist → Task 20

### 2. 占位符扫描（关键词）
- [ ] "TBD" / "TODO" / "implement later" / "fill in details"：无
- [ ] "add appropriate error handling" / "handle edge cases"：无
- [ ] "similar to Task N"：无
- [ ] "write tests for the above" 不带实际测试代码：无
- [ ] 不带代码块的"修改某方法"：Task 14 / 17 里有几处偏描述而非直接给代码 —— 接受因为实现细节需要看到原文件才能写确切代码，但步骤已经给了足够的方向指引

### 3. 类型一致性
- [ ] `MetadataFilter` 在 Task 3 定义，在 Task 13-15 使用 —— 字段签名一致（`field`, `op`, `value`）
- [ ] `MetadataFilter.FilterOp` enum 在 Task 3 有 6 个 case，在 Task 15 的 switch 里全部被覆盖 ✓
- [ ] `Permission` enum 顺序（READ < WRITE < MANAGE） —— Task 2 的 `ordinal()` 用法和 Task 9 的 `permissionSatisfies(actual.ordinal() >= required.ordinal())` 一致 ✓
- [ ] `LoginUser` 新字段在 Task 4 定义，在 Task 8 / 9 使用 —— getter 名称一致（`getDeptId`, `getRoleTypes`, `getMaxSecurityLevel`）✓
- [ ] `SecurityLevelRefreshEvent` 在 Task 17 Step 1 定义为 `(docId, collectionName, newSecurityLevel)`，Step 3 的 `syncSend` 用相同字段 ✓

### 4. 依赖顺序检查
- [ ] Task 1 (schema) → Task 5 (DO 对应列) ✓
- [ ] Task 2 (enums) → Task 4 (LoginUser 引用 RoleType) ✓
- [ ] Task 3 (MetadataFilter) → Task 13-15 (使用它) ✓
- [ ] Task 7 (RoleMapper) → Task 8 (UserContextInterceptor 用) ✓
- [ ] Task 8 (SaTokenStpInterfaceImpl 返回 SUPER_ADMIN) → Task 10 (@SaCheckRole 替换，否则 admin 登录后被自己的注解挡掉) ✓
- [ ] Task 11 (OS mapping) → Task 20 (重建时会用新 mapping) ✓
- [ ] Task 12 (updateChunksMetadata) → Task 17 (consumer 调用它) ✓
- [ ] Task 13 + 14 + 15 是一个逻辑组，必须一起 commit（中间态编译不过）→ 合并为一个 commit ✓

### 5. Scope 检查
- [ ] 本计划覆盖 PR1 的**完整范围**，不包括 PR3 (cleanup, UI, DEPT_ADMIN enforcement on write paths) —— 符合设计文档三 PR 划分 ✓
- [ ] 本计划**不依赖** follow-up task #9 (test infrastructure) —— 测试策略明确说单元测试写 easy 的，integration 用手动 smoke test ✓
- [ ] DLQ 监控明确标注为 PR1 外的 follow-up ✓

---

## 五、执行建议

### 推荐顺序

按 Task 1 → 20 顺序执行。**特别注意**：
- Task 13 + 14 + 15 **必须连续执行**，因为中间状态编译不过
- Task 10 (字符串替换) **必须在 Task 8 之后** —— 否则 admin 用户登录会被自己的注解挡掉
- Task 20 (rebuild + smoke test) 是最终验收，必须能全部通过

### 关键风险点

1. **OpenSearch mapping 变更不被旧索引应用** → 必须 rebuild 干净（Task 20 Step 3 的 `DELETE _all`）
2. **RocketMQ 事务消息实际语义** → Task 17 使用 `afterCommit` hook，不是真正的事务消息；如果 MQ 发送失败会丢事件（文档里已承认，DLQ 监控是 follow-up）
3. **并发测试 maxSecurityLevel 正确取 max 而非 min** → Task 8 的 `roles.stream().mapToInt(...).max()` 必须对
4. **Sa-Token 的角色 getRoleList 结果缓存** → Sa-Token 默认可能会缓存 getRoleList 结果，若缓存则改完后旧 token 还能过注解。如遇此问题，在 Task 20 Step 4 的 Redis FLUSHDB 里也清理 sa-token 的 session

---

**Plan 完整，总任务数：20**