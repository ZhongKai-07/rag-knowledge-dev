# Infrastructure 层架构

> 所有持久化资源、中间件和外部服务。本地通过 `resources/docker/` 下的 docker-compose 编排；生产部署拓扑参考 `diagram/deploy/aws_deploy.drawio`（不在本文档范围）。
>
> 架构图：[`diagram/architecture/arch_infrastructure.drawio`](../../../diagram/architecture/arch_infrastructure.drawio)
> 启动流程：[`docs/dev/launch.md`](../launch.md)

## 1. 组件总览

| 组件 | 版本 | 用途 | 谁使用 |
| --- | --- | --- | --- |
| PostgreSQL | 16 | 业务 + 追踪 + 评估所有关系型数据 | 所有业务域 |
| Redis + Redisson | 7.x | RBAC 缓存 · 限流 · 幂等 Token · 分布式锁 · 模型熔断 | framework.idempotent · user.KbAccessService · rag.AOP · infra-ai.ModelHealthStore |
| RocketMQ | 5.x | 文档分块事务消息 | knowledge.mq |
| 向量存储 | OpenSearch 2.18 / Milvus 2.6 / pgvector | 向量检索（三选一） | rag.core.vector |
| RustFS | 最新 | S3 兼容对象存储，保存原始文档 | ingestion.fetcher · knowledge.FileStorageService |
| 外部 LLM | 百炼 / Ollama / SiliconFlow | Chat / Embedding / Rerank | infra-ai（业务透明） |
| mcp-server | 自研 | MCP 协议工具扩展 | rag.core.mcp |

## 2. PostgreSQL

### 2.1 主要表

| 业务域 | 表 | 用途 |
| --- | --- | --- |
| user | `t_user` · `t_role` · `t_user_role` · `t_role_kb_relation` · `sys_dept` | 认证 + RBAC + 部门 |
| knowledge | `t_knowledge_base` · `t_knowledge_document` · `t_knowledge_chunk` · `t_knowledge_document_chunk_log` | KB/文档/分块 + 分阶段日志 |
| rag | `t_conversation` · `t_message` · `t_rag_trace_run` · `t_rag_trace_node` · `t_rag_evaluation_record` · `t_intent_tree` · `t_intent_node` | 会话 + 追踪 + 评估 + 意图树 |
| ingestion | `t_ingestion_pipeline` · `t_ingestion_pipeline_node` · `t_ingestion_task` | 流水线配置 + 任务状态 |

### 2.2 关键约定

- **连接凭证**：Docker 容器的默认用户是 `postgres`（不是 `ragent`）；数据库名是 `ragent`。
- **两份 Schema 文件**并行维护：`resources/database/schema_pg.sql`（干净 DDL）和 `full_schema_pg.sql`（pg_dump 风格）。改表结构必须同步改。
- **升级脚本**：`resources/database/upgrade_vX.Y_to_vX.Z.sql` 增量迁移；加字段到实体**必须**搭配 upgrade 脚本，否则启动报 `PSQLException: column does not exist`。
- **`@TableLogic` 自动软删**：逻辑删除用 `deleted` 字段，MP 自动追加 `WHERE deleted=0`。
- **`extra_data TEXT` JSON**：`t_rag_trace_run` / `t_rag_trace_node` 用它扩展 tokens/latency，避免频繁 schema 迁移。**查询用 Gson，写入必须用 Jackson**（Gson 把 int 写成 `"5228.0"` 破坏 SQL CAST）。
- **pgvector 模式**：`schema_pg.sql` 包含 `CREATE EXTENSION vector` + `t_knowledge_vector(embedding vector(1536))`。默认 `postgres:16` 镜像没装此扩展，初始化会报错 —— 在 `rag.vector.type=opensearch/milvus` 模式下是**预期且安全**的（该表不存在，其他表正常创建）。

### 2.3 本地管理

```bash
docker exec postgres psql -U postgres -d ragent -c "SQL语句"
```

完全重建（**会清数据**）：见根 `CLAUDE.md` "Infrastructure" 节。

## 3. Redis + Redisson

### 3.1 键命名约定

| Key Pattern | 用途 | 写入方 |
| --- | --- | --- |
| `kb_access:{userId}` | USER 可访问 KB 集合 | `KbAccessService` |
| `kb_access:dept:{userId}` | DEPT_ADMIN 可访问 KB | `KbAccessService` |
| `kb_security_level:{userId}` Hash | 按 KB 的 maxSecurityLevel | `KbAccessService` |
| `model_health:{modelId}` | 模型连续失败计数 | infra-ai `ModelHealthStore` |
| `idempotent:submit:{key}` | 提交去重 Token | `@IdempotentSubmit` |
| `idempotent:consume:{key}` | MQ 消息去重 | `@IdempotentConsume` |
| `chat_rate_limit:*` | 聊天限流令牌 | `ChatRateLimitAspect` |
| Redisson 锁：`lock:url_rechunk:{docId}` 等 | 分布式锁 | `knowledge.schedule` |

### 3.2 缓存失效约定

- 修改 `t_role_kb_relation` / `t_user_role` / `sys_dept` 必须主动失效 `kb_access:*` 和 `kb_security_level:*`。
- `RoleService` / `SysDeptService` / `UserRoleService` 里封装了失效逻辑，**不要绕过**直接改 DAO。

## 4. RocketMQ

**使用场景**：唯一的事务消息场景是 **文档分块**。

```
KnowledgeDocumentController.startChunk(docId)
  → rocketMQ.sendMessageInTransaction(topic, msg)
      executeLocalTransaction: UPDATE t_knowledge_document SET status=RUNNING
  → 立即返回 200

KnowledgeDocumentChunkConsumer @IdempotentConsume
  → KnowledgeDocumentServiceImpl.executeChunk()
  → 原子事务：删旧 chunk/向量 → 写新 chunk/向量 → status=SUCCESS
```

**关键点**：
- **事务消息**保证"消息发出"与"DB 状态变 RUNNING"的原子性。
- **`@IdempotentConsume`** 基于 Redis 的消息 key 去重，防止 broker 重投。
- **失败重试**：由 RocketMQ 自带重试策略；达到上限后进死信队列，由监控告警（Dashboard 的任务列表页会标记 FAILURE）。

**部署注意**：`resources/docker/rocketmq/` 提供了单机 compose；本地需要先启动 nameserver 再启 broker。

## 5. 向量存储（三选一）

通过 `application.yaml` `rag.vector.type` 切换：`opensearch` / `milvus` / `pg`。三种实现对应：

| 实现 | 特点 | metadataFilters |
| --- | --- | --- |
| `OpenSearchRetrieverService` | 混合检索（向量 + 全文）· 默认推荐 | ✅ 已实现 security_level 过滤 |
| `MilvusRetrieverService` | 专用向量库，性能高 | ❌ **静默忽略**（`follow-ups.md` SL-1） |
| `PgRetrieverService` | 与业务 PG 同实例，运维简单 | ❌ **静默忽略** |

### 5.1 索引/集合命名

一个 KB 对应一个独立索引/集合，名字 = `t_knowledge_base.collection_name`（一般是 `kb_<id>_<slug>`）。

删除 KB 时同步清理：
```bash
curl -X DELETE http://localhost:9201/<collection-name>   # OpenSearch
```

### 5.2 架构债

**切换到 Milvus/pg 会丢失 security_level 过滤**。生产前必须补齐两实现的 `metadataFilters`。

## 6. RustFS（S3 对象存储）

- **用途**：保存用户上传的原始文档（PDF/Word/Excel/HTML 等），Bootstrap 通过 S3 SDK 读写。
- **bucket 命名**：与 KB 的 `collectionName` 一致。
- **启动坑**：Bootstrap 启动时必须绕过 localhost 代理：
  ```powershell
  $env:NO_PROXY='localhost,127.0.0.1'
  $env:no_proxy='localhost,127.0.0.1'
  mvn -pl bootstrap spring-boot:run
  ```
- **依赖范围**：**所有**向量库模式都需要 RustFS（原文件存储与向量存储正交），不是只有 S3Fetcher 用。

## 7. 外部 LLM

**三家候选**（见 infra-ai 文档细节）：

| 供应商 | 协议 | 典型用途 |
| --- | --- | --- |
| 阿里百炼 | OpenAI-compatible SSE | 默认 Chat · Rerank · Embedding 生产 |
| Ollama | Ollama 原生 | 本地开发，无外网依赖 |
| SiliconFlow | OpenAI-compatible | 降级备选 |

鉴权：API Key 通过 yaml 配置，请勿提交到仓库（示例：`.env` / `application-local.yaml` 不进 git）。

## 8. mcp-server（独立 Maven 模块 + 进程）

- **Model Context Protocol**：让 LLM 能以结构化方式调外部工具。
- **定位**：`mcp-server/` 模块独立启动，仅对 Bootstrap 内网暴露，不接入公网入口。
- **使用方**：`rag/core/mcp/MCPToolExecutor` —— 命中 MCP 意图时走工具调用替代向量检索。
- **扩展**：见 `entry-points.md` "加一个 MCP 工具"。

## 9. 配置片段（关键节选）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ragent
    username: postgres
  redis:
    host: localhost
    port: 6379

rocketmq:
  name-server: localhost:9876
  producer.group: ragent_producer

rag:
  vector:
    type: opensearch     # opensearch / milvus / pg

opensearch:
  hosts: http://localhost:9201

rustfs:
  endpoint: http://localhost:9000
  access-key: xxx
  secret-key: xxx

ai:
  chat:
    candidates: [...]
  embedding:
    candidates: [...]
```

## 10. 评审关注点

- **单一职责清晰**：PG 存关系型，向量库存语义检索，RustFS 存二进制原文件，Redis 存缓存/锁/熔断 —— 不越界使用（不把二进制塞 PG、不把缓存当唯一数据源）。
- **缓存一致性走失效协议**：角色/部门/KB 关系改动主动清缓存，不依赖 TTL 自愈。
- **RocketMQ 只承担分块一件事**：范围收敛，便于监控；简化到 Spring Event 的演进思路在 `diagram/deploy/aws_deploy.drawio` 的 AWS 部署方案中有备选设计。
- **向量库可插拔**但 **不完全对等**：评审须确认业务是否依赖 security_level 过滤；是则锁定 OpenSearch，否则优先补齐另外两家。
- **外部 LLM 的边界**：infra-ai 做了抽象，基础设施层只需要保证网络可达 + 密钥注入。换供应商不改业务代码。
- **生产部署形态未覆盖**：本层文档聚焦逻辑架构；AWS/私有化部署形态见 `diagram/deploy/aws_deploy.drawio` 与将来可能的 `docs/dev/deploy.md`。
