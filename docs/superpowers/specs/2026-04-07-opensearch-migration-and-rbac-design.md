# OpenSearch 迁移 + RBAC 知识库权限体系设计

> 日期：2026-04-07
> 状态：修订中（v2，基于代码审查反馈）

## 1. 概述

### 1.1 现有引擎矩阵

当前项目**同时支持 Milvus 和 PostgreSQL pgvector 两种向量引擎**，通过 `rag.vector.type` 配置切换：

| 引擎 | 配置值 | 实现类 | 状态 |
|------|--------|--------|------|
| PostgreSQL pgvector | `pg` | `PgVectorStoreAdmin` / `PgRetrieverService` | **当前默认**，共享单表 `t_knowledge_vector`，通过 `metadata->>'collection_name'` 区分知识库 |
| Milvus | `milvus` | `MilvusVectorStoreAdmin` / `MilvusRetrieverService` | 已实现 |
| OpenSearch | `opensearch` | 本次新增 | 目标 |

`application.yaml` 当前默认值为 `pg`，项目依赖中已有 pgvector，尚无 opensearch-java 依赖。

### 1.2 目标

1. **向量引擎扩展**：在现有 Milvus/Pg 适配层基础上，新增 OpenSearch 实现，沿用"1 知识库 = 1 索引"模型，引入混合查询（向量 + 关键词）能力
2. **RBAC 权限体系**：用户 → 角色 → 知识库可见性，实现知识库级别的访问隔离

### 1.3 实施策略

采用**接口适配方案**：复用现有 `VectorStoreAdmin` / `VectorStoreService` / `RetrieverService` 三层接口，新增 OpenSearch 实现类，通过 `@ConditionalOnProperty` 配置切换。Milvus 和 Pg 实现保留，可随时回退。

## 2. 环境与技术选型

| 项目 | 选择 |
|------|------|
| 开发环境 | 本地 Docker 部署 OpenSearch |
| 生产环境 | AWS OpenSearch Service |
| Java 客户端 | opensearch-java（官方客户端，`org.opensearch.client:opensearch-java`） |
| 查询模式 | 混合查询（knn 向量 + 关键词全文检索） |
| 配置切换 | `rag.vector.type: opensearch`，`@ConditionalOnProperty` 激活 |

## 3. OpenSearch Index 模型

### 3.1 Index Mapping

每个知识库创建时生成一个 OpenSearch Index，mapping 如下：

```json
{
  "mappings": {
    "dynamic": false,
    "properties": {
      "id":        { "type": "keyword" },
      "content":   { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "embedding": {
        "type": "knn_vector",
        "dimension": 1536,
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
          "doc_id":          { "type": "keyword" },
          "chunk_index":     { "type": "integer" },
          "task_id":         { "type": "keyword", "index": false },
          "pipeline_id":     { "type": "keyword" },
          "source_type":     { "type": "keyword" },
          "source_location": { "type": "keyword", "index": false },
          "keywords":        {
            "type": "text",
            "analyzer": "ik_smart",
            "fields": { "raw": { "type": "keyword" } }
          },
          "summary":         { "type": "text", "analyzer": "ik_max_word" }
        }
      }
    }
  },
  "settings": {
    "index": {
      "knn": true,
      "number_of_shards": 1,
      "number_of_replicas": 0
    }
  }
}
```

### 3.2 metadata 字段策略：白名单 + dynamic: false

当前 `IndexerNode` 允许通过 `metadataFields` 配置写入任意扩展字段。对 OpenSearch 采用 **`"dynamic": false` + 白名单**策略：

- mapping 中 `metadata` 设置 `"dynamic": false`，未在 mapping 中声明的字段**仍可写入但不会被索引**，不会触发 dynamic mapping 失控
- 白名单字段（`doc_id`, `chunk_index`, `task_id`, `pipeline_id`, `source_type`, `source_location`, `keywords`, `summary`）有明确类型定义，支持过滤和检索
- `IndexerNode` 现有的 `metadataFields` 扩展字段仍可正常写入存储，但不可搜索——如需新增可搜索字段，需先更新 mapping

这确保了现有 ingestion pipeline 不会因为 metadata 结构变化而中断。

### 3.3 HNSW 引擎选择：lucene

选择 lucene 引擎而非 nmslib，原因：

- **pre-filtering 支持**：lucene 在同一个 segment 内先执行 metadata filter 再做向量检索，保证 topK 结果数量准确。nmslib 只能 post-filter，先取 topK 再过滤，实际返回数量可能 < topK，导致召回不准
- **AWS 兼容性**：AWS OpenSearch Service 2.x+ 主推 lucene 引擎，兼容性和长期维护有保障
- **混合查询友好**：lucene 引擎与 hybrid query 的 normalization processor 配合更稳定

### 3.4 三种引擎的存储模型对比

| 维度 | Milvus | Pg (pgvector) | OpenSearch |
|------|--------|---------------|-----------|
| 索引粒度 | 1 知识库 = 1 Collection | **共享单表** `t_knowledge_vector`，通过 `metadata->>'collection_name'` 区分 | 1 知识库 = 1 Index |
| id | VarChar(36) | VARCHAR(20) | keyword |
| content | VarChar(65535) | TEXT | text + ik 分词（**新增全文检索**） |
| embedding | FloatVector(1536) | vector(1536) | knn_vector(1536) |
| metadata | JSON blob | JSONB | object, dynamic: false（**白名单结构化**） |
| Admin 职责 | 创建/管理 Collection | **仅管理 HNSW 索引**（不建表，表由 DDL 脚本预创建） | 创建/管理 Index + Mapping |

### 3.5 IK 分词器可用性与降级策略

| 环境 | IK 分词器 | 策略 |
|------|-----------|------|
| 本地 Docker | 通过 Dockerfile 安装 IK 插件 | 正常使用 |
| AWS OpenSearch Service | **不内置 IK 插件**，需要通过自定义包（custom package）上传 | 如不可用，降级为 `standard` 分词器 |

`OpenSearchVectorStoreAdmin` 在创建 index 前检测 IK analyzer 是否可用（调用 `_analyze` API）。不可用时：
- mapping 中 `analyzer` 降级为 `standard`（英文分词）或 `cjk`（CJK 二元分词）
- 记录 WARN 日志，提示安装 IK 插件以获得更好的中文检索效果
- 分词器选择通过 `opensearch.analyzer.default` 配置外化，支持按环境覆盖

### 3.6 混合查询

OpenSearch 2.10+ 原生支持 hybrid query，通过 search pipeline 的 normalization processor 融合向量和关键词得分：

```json
{
  "query": {
    "hybrid": {
      "queries": [
        {
          "knn": {
            "embedding": {
              "vector": [0.1, 0.2, "..."],
              "k": 10
            }
          }
        },
        {
          "match": {
            "content": "用户的查询关键词"
          }
        }
      ]
    }
  }
}
```

### 3.7 Search Pipeline 管理

混合查询依赖 normalization processor 的 search pipeline，管理策略如下：

| 决策点 | 方案 |
|--------|------|
| 级别 | **cluster 级别**，所有知识库共享同一个 pipeline |
| 命名 | `ragent-hybrid-search-pipeline` |
| normalization 策略 | **min_max** — 将向量和关键词得分都归一化到 0-1 |
| 绑定方式 | 创建 index 时设置 `index.search.default_pipeline: ragent-hybrid-search-pipeline`，查询时无需显式指定 |
| 创建时机 | 应用启动时在 `OpenSearchConfig` 初始化阶段检查并幂等创建 |

Pipeline 定义：

```json
{
  "description": "RAGent hybrid search normalization pipeline",
  "phase_results_processors": [
    {
      "normalization-processor": {
        "normalization": { "technique": "min_max" },
        "combination": { "technique": "arithmetic_mean", "parameters": { "weights": [0.5, 0.5] } }
      }
    }
  ]
}
```

向量和关键词的权重初始设为 0.5:0.5，配置外化到 `application.yaml`，可按评测结果调整。

**Pipeline 创建失败的降级策略：**

| 场景 | 处理 |
|------|------|
| 无权限创建 pipeline | 应用正常启动，记录 ERROR 日志。hybrid query 不可用，检索降级为纯 knn 查询 |
| pipeline 已存在但定义不同 | 跳过创建，记录 WARN 日志，使用现有 pipeline |
| OpenSearch 版本不支持 hybrid | 降级为纯 knn 查询，记录 WARN 日志 |

降级时 `OpenSearchRetrieverService` 检测 pipeline 是否绑定成功，未绑定则自动切换为纯 knn 检索路径。

## 4. OpenSearch 实现层

### 4.1 接口 → 实现映射

| 现有接口 | Pg 实现 | Milvus 实现 | 新增 OpenSearch 实现 |
|---------|---------|-------------|---------------------|
| `VectorStoreAdmin` | `PgVectorStoreAdmin` | `MilvusVectorStoreAdmin` | `OpenSearchVectorStoreAdmin` |
| `VectorStoreService` | *(Pg 内嵌在 Retriever 中)* | `MilvusVectorStoreService` | `OpenSearchVectorStoreService` |
| `RetrieverService` | `PgRetrieverService` | `MilvusRetrieverService` | `OpenSearchRetrieverService` |

### 4.2 各实现类职责

**OpenSearchVectorStoreAdmin**
- `ensureVectorSpace()` → 幂等：检测 index 是否存在，不存在则创建 index + mapping（knn_vector + 分词器）；已存在则跳过
- `vectorSpaceExists()` → 检查 index 是否存在
- 首次创建 index 时确保 search pipeline 已就绪

**OpenSearchVectorStoreService**
- `indexDocumentChunks()` → bulk index 文档 chunks
- `updateChunk()` → 单条 upsert
- `deleteDocumentVectors()` → delete by query（metadata.doc_id filter）
- `deleteChunkById/sByIds()` → 按 id 删除

**OpenSearchRetrieverService**
- `retrieve()` → 混合查询：knn + match(content) + match(metadata.keywords)
- pipeline 未就绪时降级为纯 knn 查询
- 支持 metadata filter（doc_id 等条件，消费 `RetrieveRequest.metadataFilters`）

### 4.3 ensureVectorSpace() 契约统一

现有问题：`VectorStoreAdmin` 接口注释定义为"幂等：不存在则创建，存在则兼容校验"，但 `MilvusVectorStoreAdmin` 实现为"存在就抛 `VectorCollectionAlreadyExistsException`"，语义不一致。

各引擎现状：
- `PgVectorStoreAdmin`：幂等——检查 HNSW 索引是否存在，不存在则 `CREATE INDEX IF NOT EXISTS`（注意：不负责建表，`t_knowledge_vector` 由 DDL 脚本预创建）
- `MilvusVectorStoreAdmin`：**非幂等**——Collection 存在时抛异常

处理方案：
- `OpenSearchVectorStoreAdmin` 实现为**真正幂等**（index 存在则跳过）
- 同时修正 `MilvusVectorStoreAdmin`：存在时跳过而非抛异常，与接口契约对齐

### 4.4 配置

```yaml
# application.yaml
rag:
  vector:
    type: opensearch  # milvus | opensearch | pg

opensearch:
  uris: http://localhost:9200
  # username: admin
  # password: admin
  analyzer:
    default: ik_max_word        # 降级时可改为 standard 或 cjk
    search: ik_smart
  hybrid:
    vector-weight: 0.5
    text-weight: 0.5
  # 生产环境 AWS OpenSearch
  # uris: https://xxx.us-east-1.es.amazonaws.com
  # auth-type: aws-sigv4        # basic | aws-sigv4
```

新增配置类 `OpenSearchConfig.java`：
- 创建 `OpenSearchClient` bean
- `@ConditionalOnProperty("rag.vector.type", havingValue = "opensearch")`
- 支持 basic auth（本地 Docker）和 AWS SigV4 签名（生产），通过 `auth-type` 区分

### 4.5 测试要求

优先编写 `OpenSearchVectorStoreAdmin` 的集成测试，验证：
- Index mapping 中分词器是否正确加载（IK 可用时用 IK，不可用时用降级分词器）
- knn_vector 索引是否生效
- Bulk index 和 hybrid query 的端到端流程
- `ensureVectorSpace()` 幂等性（重复调用不报错）

## 5. 评分与过滤统一契约

### 5.1 Score 归一化规范

`RetrieverService` 接口层返回的 `RetrievedChunk.score` **必须归一化到 [0, 1] 区间**，1 表示最相似，0 表示最不相似。各引擎负责在自己的实现中完成转换：

| 引擎 | 原始 score 来源 | 转换方式 |
|------|----------------|---------|
| OpenSearch (hybrid) | 经 min_max normalization pipeline 处理 | 已在 [0, 1]，直接使用 |
| OpenSearch (纯 knn, cosine) | `_score` 字段，行为随版本变化：2.18 及之前为 `1 + cosineSimilarity`（范围 [0, 2]）；2.19+ 已归一化到 [0, 1] | **直接消费 `hit.score()`**，不做二次转换。实现时根据实际部署版本验证 `_score` 范围，如超出 [0, 1] 则 clamp |
| Milvus (COSINE) | 返回 distance [0, 2]，0=最相似 | `score = 1 - (distance / 2)` |
| Pg (pgvector, `<=>`) | cosine distance [0, 2]，0=最相似 | 当前用 `1 - distance`，需修正为 `1 - (distance / 2)` 以保证落入 [0, 1] |

**Pg 实现修正**：当前 `PgRetrieverService` 使用 `1 - (embedding <=> vector)` 作为 score，当 distance > 1 时 score 为负值。需修正为 `1 - ((embedding <=> vector) / 2)` 以保证 [0, 1] 区间。

**OpenSearch 注意**：cosinesimil 的 `_score` 计算方式在 2.18→2.19 之间有 breaking change。实现时应直接消费 `hit.score()` 返回值，不假设具体公式。如果 `_score > 1`（旧版本行为），clamp 到 [0, 1]：`Math.min(hit.score(), 1.0f)`。

### 5.2 metadata filter 消费

`RetrieveRequest` 已定义 `metadataFilters` 字段（`Map<String, Object>`），但当前三个引擎实现均未消费此字段。

本次 OpenSearch 实现中：
- `OpenSearchRetrieverService` 将 `metadataFilters` 翻译为 OpenSearch 的 `bool.filter` 子句（keyword 字段用 `term`，text 字段用 `match`）
- 后续视需要在 Milvus / Pg 实现中补齐

### 5.3 topK 行为

各引擎 topK 含义统一为：**过滤后**返回的最大结果数。

- OpenSearch (lucene engine)：pre-filtering，天然满足
- Milvus (nmslib)：post-filtering，可能返回 < topK（已知限制，不在本次修正范围）
- Pg：SQL `LIMIT`，天然满足

## 6. 检索模式切换影响面

### 6.1 当前检索架构

当前 chat 接口 `GET /rag/v3/chat` 参数为 `question / conversationId / deepThinking`，**不含 kbId**。检索流程为：

1. 意图分类 → 匹配 `IntentNode` → 每个 IntentNode 指向一个 `collectionName`
2. `IntentDirectedSearchChannel`：按意图定向检索对应知识库
3. `VectorGlobalSearchChannel`：当意图置信度低于阈值时，**扫描所有知识库 collection** 做兜底

### 6.2 引入 RBAC 后的变更

引入"用户只能访问有权知识库"后，需要以下调整：

**chat API 变更：**

| 项目 | 变更前 | 变更后 |
|------|--------|--------|
| 参数 | `question, conversationId, deepThinking` | 新增 `knowledgeBaseId`（可选） |
| 行为（传了 kbId） | - | 仅在指定知识库检索，跳过意图分类和全局通道 |
| 行为（未传 kbId） | 意图定向 + 全局兜底 | 意图定向 + 全局兜底，但**全局通道仅扫描用户有权的知识库** |

**检索通道变更：**

| 通道 | 变更 |
|------|------|
| `IntentDirectedSearchChannel` | 过滤意图匹配结果：只保留用户有权访问的 IntentNode 对应知识库 |
| `VectorGlobalSearchChannel` | `getAllKBCollections()` 改为只查用户可访问的知识库列表（admin 不限） |
| `SearchContext` | 新增 `accessibleKbIds`（`Set<String>`）显式字段，在检索链路入口注入。选择显式字段而非复用 `metadata`（`Map<String, Object>`）的原因：(1) 该字段是所有检索通道都必须消费的安全约束，不是可选上下文；(2) 强类型避免遗漏或类型转换错误；(3) 当前 `SearchContext.metadata` 实际上没有任何消费者，不适合承载关键安全数据 |

**前端变更：**
- 聊天页面新增知识库选择器（可选），用户可选择单个知识库或"自动"模式
- "自动"模式下走原有意图分类 + 全局兜底（受 RBAC 过滤）

### 6.3 是否保留全局兜底通道

**保留**，但行为调整：
- admin：扫描所有知识库（不变）
- user：仅扫描该用户有权访问的知识库列表
- 通过 `SearchContext.accessibleKbIds` 传递范围，`VectorGlobalSearchChannel.getAllKBCollections()` 接受此参数做过滤

## 7. RBAC 权限体系

### 7.1 角色模型兼容策略

**现状：** `t_user.role` 字段存储 `"admin"` / `"user"` 字符串，代码中大量位置直接判断此字段：
- `UserContext.getRole()` 返回字符串
- `SaTokenStpInterfaceImpl.getRoleList()` 查询此字段
- `@SaCheckRole("admin")` 注解拦截管理接口

**策略：保留 `t_user.role` 为系统角色，RBAC 三表只负责知识库可见性。**

| 层级 | 职责 | 数据来源 |
|------|------|---------|
| 系统角色 | admin / user 身份判断、管理接口鉴权 | `t_user.role`（不变） |
| 知识库可见性 | 用户能访问哪些知识库 | `t_user_role` + `t_role` + `t_role_kb_relation`（新增） |

好处：
- 不改动现有登录态、鉴权、管理后台的判断逻辑
- `@SaCheckRole("admin")` 继续生效
- RBAC 三表是**增量新增**，不触动现有表结构
- admin 绕过知识库可见性检查（系统角色优先）

### 7.2 数据模型（3 张新表）

**t_role** — 角色定义

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(20) | 主键，雪花 ID |
| name | VARCHAR(64) | 角色名称，唯一 |
| description | VARCHAR(256) | 角色描述 |
| created_by | VARCHAR(64) | 创建人 |
| updated_by | VARCHAR(64) | 更新人 |
| create_time | TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | 更新时间 |
| deleted | INT | 逻辑删除 |

**t_role_kb_relation** — 角色 ↔ 知识库可见性关联

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(20) | 主键，雪花 ID |
| role_id | VARCHAR(20) | 关联角色 |
| kb_id | VARCHAR(20) | 关联知识库 |
| created_by | VARCHAR(64) | 创建人 |
| create_time | TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | 更新时间 |
| deleted | INT | 逻辑删除 |

**t_user_role** — 用户 ↔ 角色关联

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(20) | 主键，雪花 ID |
| user_id | VARCHAR(20) | 关联用户 |
| role_id | VARCHAR(20) | 关联角色 |
| created_by | VARCHAR(64) | 创建人 |
| create_time | TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | 更新时间 |
| deleted | INT | 逻辑删除 |

> 字段类型与现有 `schema_pg.sql` 统一：主键和外键均为 `VARCHAR(20)`，与 `t_user.id`、`t_knowledge_base.id` 一致。

### 7.3 关系模型

```
t_user (已有)          t_role (新增)         t_knowledge_base (已有)
┌──────────┐          ┌──────────┐          ┌──────────────────┐
│ id       │          │ id       │          │ id               │
│ username │    N:N   │ name     │    N:N   │ name             │
│ role     │◄────────►│ desc     │◄────────►│ collection_name  │
│ (系统角色)│          │ ...      │          │ ...              │
└──────────┘          └──────────┘          └──────────────────┘
      │                    │                        │
      └─── t_user_role ───┘   t_role_kb_relation ──┘
            (user_id,            (role_id,
             role_id)             kb_id)
```

- `t_user.role`：系统角色（admin/user），控制管理权限，**不变**
- `t_user_role` + `t_role` + `t_role_kb_relation`：知识库可见性，**新增**
- 一个用户可拥有多个角色，一个角色可挂载多个知识库，一个知识库可被多个角色共享

### 7.4 权限解析流程

```
用户登录
  │
  ├── t_user.role == "admin"？ ──YES──► 返回所有知识库，跳过 RBAC
  │
  NO
  │
  ▼
查询 t_user_role → 获取该用户所有 role_id
  │
  ▼
查询 t_role_kb_relation → 获取所有 role 关联的 kb_id（去重）
  │
  ▼
查询 t_knowledge_base WHERE id IN (kb_id 列表)
  │
  ▼
返回用户可见的知识库列表
  │
  ▼
用户选择一个知识库或"自动"模式 → 检索时校验权限 → 执行 OpenSearch 查询
```

### 7.5 核心权限服务

```java
public interface KbAccessService {

    /** 获取用户可访问的所有知识库 ID。admin 返回全量。 */
    Set<String> getAccessibleKbIds(String userId);

    /**
     * 校验当前用户是否有权访问指定知识库，无权则抛异常。
     * 完全依赖 UserContext：系统态（无登录态）直接放行，admin 放行，user 鉴权。
     * 不单独传 userId，避免"显式传参"与"隐式上下文"语义混乱。
     */
    void checkAccess(String kbId);
}
```

实现策略：
1. `checkAccess(kbId)` 先判断 `UserContext.hasUser()`，系统态直接放行
2. admin（`UserContext.getRole()` == "admin"）直接放行
3. `getAccessibleKbIds(userId)` 查 `t_user_role` → `t_role_kb_relation` → 去重返回 kb_id 集合
4. 加 Redis 缓存，key = `kb_access:{userId}`，以下事件清除缓存：
   - 角色/权限变更时
   - **知识库逻辑删除时**（防止缓存残留已删除 kb_id）
5. 查询时 JOIN `t_knowledge_base` 过滤 `deleted = 0`，作为缓存之外的二次保障

### 7.6 防越权设计（双层校验）

权限校验采用 **Controller fail-fast + Service 兜底** 双层策略。Service 层是安全底线，确保非 Controller 入口（MCP tool 调用、RocketMQ 消费、Agent 内部调用）也无法绕过权限检查。

**用户态 vs 系统态调用：**

当前异步任务（如 MQ 驱动的文档分块）通过 `KnowledgeDocumentChunkConsumer` 手动恢复 `UserContext`（仅设置 `username`，无完整登录态），属于"系统态"调用。Service 层鉴权需区分两种调用场景：

| 调用场景 | UserContext 状态 | 鉴权行为 |
|---------|-----------------|---------|
| 用户态（Controller → Service） | 完整 LoginUser（userId, role 等） | 执行 `checkAccess()` |
| 系统态（MQ 消费、定时任务、内部调用） | 无 UserContext 或仅有 username | **跳过** `checkAccess()`，不鉴权 |

判断方式：`KbAccessService.checkAccess()` 内部先检查 `UserContext.hasUser()` 且 `UserContext.getUserId() != null`，不满足则视为系统态调用，直接放行。

```java
// KbAccessServiceImpl — 完全依赖 UserContext，不单独传 userId
public void checkAccess(String kbId) {
    // 系统态调用（MQ消费、定时任务等）无完整登录态，直接放行
    if (!UserContext.hasUser() || UserContext.getUserId() == null) {
        return;
    }
    // admin 放行
    if ("admin".equals(UserContext.getRole())) {
        return;
    }
    // user 鉴权
    Set<String> accessible = getAccessibleKbIds(UserContext.getUserId());
    if (!accessible.contains(kbId)) {
        throw new UnauthorizedAccessException("无权访问该知识库");
    }
}
```

**Service 层（兜底）：**

```java
// RagService / KnowledgeBaseService 等 Service 内部
kbAccessService.checkAccess(kbId);
```

**Controller 层（fail-fast）：**

```java
@GetMapping("/rag/v3/chat")
public SseEmitter chat(@RequestParam String question,
                       @RequestParam(required = false) String knowledgeBaseId,
                       ...) {
    LoginUser user = UserContext.requireUser();
    if (knowledgeBaseId != null && !"admin".equals(user.getRole())) {
        kbAccessService.checkAccess(knowledgeBaseId);
    }
    return ragChatService.streamChat(question, knowledgeBaseId, ...);
}
```

### 7.7 权限矩阵

#### 知识库管理（KnowledgeBaseController）

| 接口 | admin | user（有权知识库） | user（无权知识库） |
|------|-------|-------------------|-------------------|
| `POST /knowledge-base` 创建 | ✅ | ❌ | ❌ |
| `GET /knowledge-base` 分页列表 | 全量 | 仅有权的 | - |
| `GET /knowledge-base/{kb-id}` 详情 | ✅ | ✅ | ❌ |
| `PUT /knowledge-base/{kb-id}` 编辑/重命名 | ✅ | ❌ | ❌ |
| `DELETE /knowledge-base/{kb-id}` 删除 | ✅ | ❌ | ❌ |
| `GET /knowledge-base/chunk-strategies` 分块策略 | ✅ | ✅ | ✅ |

#### 文档管理（KnowledgeDocumentController）

| 接口 | admin | user（有权知识库） | user（无权知识库） |
|------|-------|-------------------|-------------------|
| `POST /knowledge-base/{kb-id}/docs/upload` 上传 | ✅ | ❌ | ❌ |
| `POST /knowledge-base/docs/{doc-id}/chunk` 分块 | ✅ | ❌ | ❌ |
| `DELETE /knowledge-base/docs/{doc-id}` 删除 | ✅ | ❌ | ❌ |
| `GET /knowledge-base/docs/{docId}` 详情 | ✅ | ✅（只读） | ❌ |
| `PUT /knowledge-base/docs/{docId}` 编辑 | ✅ | ❌ | ❌ |
| `GET /knowledge-base/{kb-id}/docs` 文档列表 | ✅ | ✅（只读） | ❌ |
| `GET /knowledge-base/docs/search` 全局搜索 | 全量 | 仅有权知识库下的文档 | - |
| `PATCH /knowledge-base/docs/{docId}/enable` 启停 | ✅ | ❌ | ❌ |
| `GET /knowledge-base/docs/{docId}/chunk-logs` 日志 | ✅ | ✅（只读） | ❌ |

#### 聊天（RAGChatController）

| 接口 | admin | user |
|------|-------|------|
| `GET /rag/v3/chat` 问答 | 不限知识库 | 仅有权知识库（含自动模式下的全局兜底） |

#### 角色管理（RoleController，新增）

| 接口 | admin | user |
|------|-------|------|
| 角色 CRUD / 知识库挂载 / 用户分配 | ✅ | ❌ |

#### 用户管理

| 接口 | admin | user |
|------|-------|------|
| 用户创建 / 编辑 / 分配角色 | ✅ | ❌ |
| 查看个人信息 | ✅ | ✅ |

> 文档写操作（上传、编辑、分块、删除、启停）限 admin only。user 对有权知识库只有**只读**权限（查看知识库详情、文档列表、文档详情、分块日志）。

## 8. 资源命名与生命周期

### 8.1 collectionName 的多重用途

当前 `collectionName` 同时用于：
- 向量空间标识：Milvus collection 名 / Pg 中 `metadata->>'collection_name'` 的值 / OpenSearch index 名
- S3 存储桶名

注意：Pg 实现中 `collectionName` **不是表名**（Pg 使用共享单表 `t_knowledge_vector`），而是 metadata 中的过滤值。

本次设计**继续保留此约定**：`collectionName` 在 OpenSearch 场景下即为 index 名。理由：
- 与现有代码一致，不引入额外映射层
- 命名规则由用户在创建知识库时指定，已有唯一性校验

### 8.2 资源生命周期表

| 事件 | PostgreSQL 表 | 向量空间（OpenSearch index） | S3 存储桶 | Search Pipeline |
|------|--------------|---------------------------|----------|----------------|
| **创建知识库** | INSERT t_knowledge_base | 创建 index + mapping | 创建 bucket | 首次创建时幂等初始化（cluster 级别共享） |
| **删除知识库（当前：逻辑删除）** | UPDATE deleted=1 | **不删除 index** | **不删除 bucket** | 不受影响（cluster 级别） |
| **物理清理（定时任务，待实现）** | DELETE 记录 | 删除 index | 清空并删除 bucket | 不受影响 |

逻辑删除后 index 和 bucket 保留的原因：
- 支持误删恢复（恢复 deleted=0 即可，数据仍在）
- 物理清理作为独立的运维操作，可人工触发或定时执行

> 本次设计不实现物理清理，但预留接口：`VectorStoreAdmin` 后续可扩展 `deleteVectorSpace(VectorSpaceId)` 方法。

## 9. 涉及的代码变更汇总

### 新增

| 组件 | 说明 |
|------|------|
| `OpenSearchConfig` | 客户端配置 + pipeline 初始化 |
| `OpenSearchVectorStoreAdmin` | index 创建与管理 |
| `OpenSearchVectorStoreService` | chunk CRUD |
| `OpenSearchRetrieverService` | 混合查询检索 |
| `RoleDO` / `RoleKbRelationDO` / `UserRoleDO` | RBAC 实体类 |
| `KbAccessService` / `KbAccessServiceImpl` | 权限解析服务 |
| `RoleService` / `RoleController` | 角色管理 |
| RBAC 数据库迁移脚本 | 3 张新表 DDL |
| OpenSearch Docker Compose | 本地开发环境 |

### 修改

**后端：**

| 组件 | 变更 |
|------|------|
| `MilvusVectorStoreAdmin.ensureVectorSpace()` | 修正为幂等（存在时跳过，不抛异常） |
| `PgRetrieverService` | score 归一化修正：`1 - (distance / 2)` |
| `RAGChatController` | 新增可选参数 `knowledgeBaseId`，加权限校验 |
| `RAGChatService` | `streamChat()` 签名新增 `knowledgeBaseId` 参数（当前签名：`question, conversationId, deepThinking, emitter`） |
| `RAGChatServiceImpl` | `streamChat()` 实现中注入 `accessibleKbIds` 到 `SearchContext`，传了 `knowledgeBaseId` 时跳过意图分类直接定向检索 |
| `VectorGlobalSearchChannel` | 接受 `accessibleKbIds` 过滤，user 只扫有权知识库 |
| `IntentDirectedSearchChannel` | 过滤意图结果，只保留用户有权的知识库 |
| `SearchContext` | 新增 `accessibleKbIds` 字段 |
| `KnowledgeBaseController.pageQuery()` | user 只返回有权知识库 |
| `KnowledgeDocumentController` | 涉及知识库的接口加权限校验 |
| 用户管理接口 | 支持分配角色 |
| `bootstrap/pom.xml` | 新增 `opensearch-java` 依赖 |
| `application.yaml` | 新增 opensearch 配置段 |

**前端：**

| 文件 | 变更 |
|------|------|
| `frontend/src/stores/chatStore.ts` | `buildQuery()` 调用新增 `knowledgeBaseId` 参数（当前只传 `question / conversationId / deepThinking`） |
| 聊天页面组件 | 新增知识库选择器 UI（下拉选择或"自动"模式），选中值绑定到 chatStore |
| 前端 API/类型定义 | 新增知识库列表接口调用（获取当前用户有权的知识库），新增 `knowledgeBaseId` 类型定义 |
