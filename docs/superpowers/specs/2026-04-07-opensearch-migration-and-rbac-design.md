# OpenSearch 迁移 + RBAC 知识库权限体系设计

> 日期：2026-04-07
> 状态：已确认

## 1. 概述

两个核心目标：

1. **向量数据库切换**：Milvus → OpenSearch，沿用"1 知识库 = 1 索引"的模型，新增混合查询（向量 + 关键词）能力
2. **RBAC 权限体系**：用户 → 角色 → 知识库可见性，实现知识库级别的访问隔离

### 实施策略

采用**接口适配方案**：复用现有 `VectorStoreAdmin` / `VectorStoreService` / `RetrieverService` 三层接口，新增 OpenSearch 实现类，通过配置切换。Milvus 实现保留，可随时回退。

## 2. 环境与技术选型

| 项目 | 选择 |
|------|------|
| 开发环境 | 本地 Docker 部署 OpenSearch |
| 生产环境 | AWS OpenSearch Service |
| Java 客户端 | opensearch-java（官方客户端） |
| 查询模式 | 混合查询（knn 向量 + 关键词全文检索） |
| 配置切换 | `rag.vector.type: opensearch`，`@ConditionalOnProperty` 激活 |

## 3. OpenSearch Index 模型

### 3.1 Index Mapping

每个知识库创建时生成一个 OpenSearch Index，mapping 如下：

```json
{
  "mappings": {
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

### 3.2 与 Milvus 字段映射对比

| Milvus 字段 | OpenSearch 字段 | 变化 |
|-------------|----------------|------|
| id (VarChar 36) | id (keyword) | 类型映射 |
| content (VarChar 65535) | content (text + ik 分词) | **新增**：支持关键词全文检索 |
| embedding (FloatVector 1536) | embedding (knn_vector 1536) | 类型映射，HNSW 参数沿用 |
| metadata (JSON blob) | metadata (object with typed fields) | **结构化**：子字段有明确类型，可精确过滤 |

### 3.3 HNSW 引擎选择：lucene

选择 lucene 引擎而非 nmslib，原因：

- **pre-filtering 支持**：lucene 在同一个 segment 内先执行 metadata filter 再做向量检索，保证 topK 结果数量准确。nmslib 只能 post-filter，先取 topK 再过滤，实际返回数量可能 < topK，导致召回不准
- **AWS 兼容性**：AWS OpenSearch Service 2.x+ 主推 lucene 引擎，兼容性和长期维护有保障
- **混合查询友好**：lucene 引擎与 hybrid query 的 normalization processor 配合更稳定

### 3.4 metadata 字段设计说明

- `task_id`、`source_location`：设置 `"index": false`，不参与查询，节省索引空间
- `keywords`：multi-field 设计，`text`（ik_smart 分词）支持全文检索，`raw`（keyword）支持精确匹配
- `summary`：`ik_max_word` 分词，支持摘要内容的全文检索

### 3.4 混合查询

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

### 3.6 Search Pipeline 管理

混合查询依赖 normalization processor 的 search pipeline，管理策略如下：

| 决策点 | 方案 |
|--------|------|
| 级别 | **cluster 级别**，所有知识库共享同一个 pipeline |
| 命名 | `ragent-hybrid-search-pipeline` |
| normalization 策略 | **min_max** — 将向量和关键词得分都归一化到 0-1，简单直观 |
| 绑定方式 | 创建 index 时设置 `index.search.default_pipeline: ragent-hybrid-search-pipeline`，查询时无需显式指定 |
| 创建时机 | 应用启动时在 `OpenSearchConfig` 初始化阶段检查 pipeline 是否存在，不存在则创建（幂等） |

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

向量和关键词的权重初始设为 0.5:0.5，后续可根据评测结果调整，配置外化到 `application.yaml`。

## 4. OpenSearch 实现层

### 4.1 接口 → 实现映射

| 现有接口 | Milvus 实现（保留） | 新增 OpenSearch 实现 |
|---------|-------------------|---------------------|
| `VectorStoreAdmin` | `MilvusVectorStoreAdmin` | `OpenSearchVectorStoreAdmin` |
| `VectorStoreService` | `MilvusVectorStoreService` | `OpenSearchVectorStoreService` |
| `RetrieverService` | `MilvusRetrieverService` | `OpenSearchRetrieverService` |

### 4.2 各实现类职责

**OpenSearchVectorStoreAdmin**
- `ensureVectorSpace()` → 创建 index + mapping（knn_vector + ik 分词）
- `vectorSpaceExists()` → 检查 index 是否存在
- 创建 search pipeline（normalization processor，用于混合查询得分融合）

**OpenSearchVectorStoreService**
- `indexDocumentChunks()` → bulk index 文档 chunks
- `updateChunk()` → 单条 upsert
- `deleteDocumentVectors()` → delete by query（metadata.doc_id filter）
- `deleteChunkById/sByIds()` → 按 id 删除

**OpenSearchRetrieverService**
- `retrieve()` → 混合查询：knn + match(content) + match(metadata.keywords)
- 通过 search pipeline 自动融合向量和关键词得分
- 支持 metadata filter（doc_id 等条件）

### 4.3 配置

```yaml
# application.yaml
rag:
  vector:
    type: opensearch  # milvus | opensearch | pg

opensearch:
  uris: http://localhost:9200
  # 生产环境 AWS OpenSearch
  # uris: https://xxx.us-east-1.es.amazonaws.com
  # username: admin
  # password: xxx
```

新增配置类 `OpenSearchConfig.java`：
- 创建 `OpenSearchClient` bean
- `@ConditionalOnProperty("rag.vector.type", havingValue = "opensearch")`
- 支持 basic auth（本地 Docker）和 AWS SigV4 签名（生产）

### 4.4 Score 标准化

`RetrieverService` 接口层返回的 score 统一归一化到 0-1 区间。OpenSearch hybrid query 经过 normalization processor 后 score 已在 0-1 之间；Milvus COSINE 距离需在 `MilvusRetrieverService` 中做转换。确保上层 Rerank 逻辑不感知引擎差异。

### 4.5 测试要求

优先编写 `OpenSearchVectorStoreAdmin` 的集成测试，验证：
- Index mapping 中 IK 分词器是否正确加载
- knn_vector 索引是否生效
- Bulk index 和 hybrid query 的端到端流程

## 5. RBAC 权限体系

### 5.1 数据模型（3 张新表）

**t_role** — 角色定义

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(20) | 主键，雪花ID |
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
| id | VARCHAR(20) | 主键，雪花ID |
| role_id | VARCHAR(20) | 关联角色 |
| kb_id | VARCHAR(20) | 关联知识库 |
| created_by | VARCHAR(64) | 创建人 |
| create_time | TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | 更新时间 |
| deleted | INT | 逻辑删除 |

**t_user_role** — 用户 ↔ 角色关联

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(20) | 主键，雪花ID |
| user_id | VARCHAR(20) | 关联用户 |
| role_id | VARCHAR(20) | 关联角色 |
| created_by | VARCHAR(64) | 创建人 |
| create_time | TIMESTAMP | 创建时间 |
| update_time | TIMESTAMP | 更新时间 |
| deleted | INT | 逻辑删除 |

### 5.2 关系模型

```
t_user (已有)          t_role (新增)         t_knowledge_base (已有)
┌──────────┐          ┌──────────┐          ┌──────────────────┐
│ id       │          │ id       │          │ id               │
│ username │    N:N   │ name     │    N:N   │ name             │
│ role     │◄────────►│ desc     │◄────────►│ collection_name  │
│ ...      │          │ ...      │          │ ...              │
└──────────┘          └──────────┘          └──────────────────┘
      │                    │                        │
      └─── t_user_role ───┘   t_role_kb_relation ──┘
            (user_id,            (role_id,
             role_id)             kb_id)
```

- 一个用户可拥有多个角色
- 一个角色可挂载多个知识库
- 一个知识库可被多个角色共享

### 5.3 权限解析流程

```
用户登录
  │
  ├── admin？ ──YES──► 返回所有知识库，跳过 RBAC
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
用户选择一个知识库 → 检索时校验权限 → 执行 OpenSearch 查询
```

### 5.4 核心权限服务

```java
public interface KbAccessService {

    /** 获取用户可访问的所有知识库 ID */
    Set<String> getAccessibleKbIds(String userId);

    /** 校验用户是否有权访问指定知识库，无权则抛异常 */
    void checkAccess(String userId, String kbId);
}
```

实现策略：
1. 查 `t_user_role WHERE user_id = ?`
2. 查 `t_role_kb_relation WHERE role_id IN (...)`
3. 去重返回 kb_id 集合
4. 可加 Redis 缓存，角色变更时清除

### 5.5 防越权设计（双层校验）

权限校验采用 **Controller fail-fast + Service 兜底** 双层策略。Service 层是安全底线，确保非 Controller 入口（MCP tool 调用、RocketMQ 消费、Agent 内部调用）也无法绕过权限检查。

**Service 层（兜底，任何入口都过不去）：**

```java
// RagService.chat() 内部
public void chat(ChatRequest request) {
    String kbId = request.getKnowledgeBaseId();
    LoginUser user = UserContext.requireUser();

    // Service 层统一权限校验
    kbAccessService.checkAccess(user.getUserId(), kbId);

    // ... 执行检索
}
```

**Controller 层（fail-fast，快速返回友好错误）：**

```java
@PostMapping("/chat")
public SseEmitter chat(@RequestBody ChatRequest request) {
    String kbId = request.getKnowledgeBaseId();
    LoginUser user = UserContext.requireUser();

    // Controller 层 fail-fast 校验
    if (!"admin".equals(user.getRole())) {
        kbAccessService.checkAccess(user.getUserId(), kbId);
    }

    return ragService.chat(request);
}
```

需要防越权的接口清单：

| 接口 | Controller 层 | Service 层 |
|------|--------------|------------|
| `POST /chat` 检索问答 | fail-fast 校验 | `ragService.chat()` 内兜底 |
| `GET /knowledge-base/list` 知识库列表 | admin 返回全部，user 过滤 | Service 内过滤 |
| `GET /knowledge-base/{id}` 知识库详情 | fail-fast 校验 | Service 内兜底 |
| `GET /knowledge-base/{id}/documents` | fail-fast 校验 | Service 内兜底 |

知识库的创建、删除、角色管理等操作仅 admin 可用，通过 Sa-Token 的 `@SaCheckRole("admin")` 注解拦截。

### 5.6 涉及的代码变更

**新增：**
- `RoleDO` / `RoleKbRelationDO` / `UserRoleDO` — 实体类
- `KbAccessService` — 权限解析服务
- `RoleService` — 角色 CRUD + 知识库挂载
- `RoleController` — 角色管理 API（admin only）

**修改：**
- `KnowledgeBaseController.list()` — 加权限过滤，user 只返回有权知识库
- `SaTokenStpInterfaceImpl.getPermissionList()` — 对接 RBAC 返回权限标识
- 用户创建/编辑接口 — 支持分配角色

## 6. 检索行为

- 用户登录后，前端展示其有权访问的知识库列表
- 用户选择一个知识库进行问答
- 检索仅在该知识库对应的 OpenSearch index 内执行
- 不支持跨知识库检索
