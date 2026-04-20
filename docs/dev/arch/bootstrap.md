# Bootstrap 层架构

> 主 Spring Boot 应用，端口 9090，上下文路径 `/api/ragent`。按**业务域**（不是技术层）组织，每个域内部保持 `controller → service → dao → domain` 四段式。
>
> 架构图：[`diagram/architecture/arch_bootstrap.drawio`](../../../diagram/architecture/arch_bootstrap.drawio)
> 关键链路时序图已有：[`diagram/sequence/rag_sequence_diagram.drawio`](../../../diagram/sequence/rag_sequence_diagram.drawio)、[`diagram/sequence/kb_ingestion_sequence.drawio`](../../../diagram/sequence/kb_ingestion_sequence.drawio)

## 1. 业务域一览

| 域 | 路径 | 文件数 | 核心职责 |
| --- | --- | --- | --- |
| `rag/` | `bootstrap/src/main/java/.../rag/` | ~194 | RAG 问答核心：编排、检索、记忆、Prompt、意图、追踪、MCP |
| `ingestion/` | `.../ingestion/` | ~64 | 文档 ETL 节点链流水线 |
| `knowledge/` | `.../knowledge/` | ~61 | 知识库/文档 CRUD、RocketMQ 异步分块驱动 |
| `user/` | `.../user/` | ~31 | Sa-Token 认证、RBAC、部门、security_level |
| `admin/` | `.../admin/` | ~10 | 仪表盘 KPI（跨域只读聚合） |
| `core/` | `.../core/` | ~17 | 文档解析（Tika）+ 分块策略 |

**域边界约束**：
- 跨域只读聚合统一放在 `admin/`，不允许 `rag/` 的 controller 去查 `knowledge/` 的 DAO。
- 跨域写操作走事件/MQ（如 `knowledge/` 通过 RocketMQ 驱动 `ingestion/`）或 service 注入（如 `rag/` 注入 `KbAccessService`）。
- `core/` 是基础能力，被 `ingestion/` 和 `rag/` 都消费。

## 2. 入站与切面

```
HTTP Request
    → SaInterceptor         // 仅 checkLogin()，不做角色检查
    → GlobalExceptionHandler // framework.web，捕获 ClientException/ServiceException/NotRoleException
    → Controller (@SaCheckRole / 显式调用 kbAccessService.checkAccess)
    → AOP
        - ChatRateLimitAspect  // 仅作用于 RAG 聊天：排队 + 生成 traceId + 写 RagTraceContext（TTL）
        - RagTraceAspect       // @RagTraceNode 注解方法自动计时 + 记录 extra_data JSON
    → Service → DAO
```

**关键坑**：
- `SaInterceptor` **只检查登录**。角色检查是 controller 自己的责任（`@SaCheckRole("SUPER_ADMIN")` 或调 `kbAccessService.checkAccess(kbId)`）。新增 controller 一定要补。
- `ChatRateLimitAspect.finally` 会在流结束**前**清 `RagTraceContext`；需要 `traceId` 的回调必须**构造时捕获**，不能等回调时再 `RagTraceContext.getTraceId()`。
- 权限拒绝（`NotRoleException` / `ClientException`）由 `GlobalExceptionHandler` 翻成 `Result`，**HTTP 状态码始终是 200**，错误通过 `Result.code` 表达。

## 3. rag 域（问答核心）

### 3.1 编排主流程（`RAGChatServiceImpl.streamChat`）

```
AOP: 限流入队 + traceId 生成
  │
  ├── KbAccessService.getAccessibleKbIds(userId)      // RBAC 过滤可见 KB
  ├── ConversationMemoryService.loadAndAppend         // 加载历史 + 追加当前问
  ├── QueryRewriteService.rewriteWithSplit            // 改写 + 子问题拆分
  ├── IntentResolver.resolve → DefaultIntentClassifier // LLM 并行意图识别
  ├── IntentGuidanceService.detectAmbiguity            // 歧义 → 短路返回引导语
  ├── if (all SYSTEM intents) → 跳过检索，直调 LLM
  ├── RetrievalEngine.retrieve                         // 并行跑通道 → 去重 → 重排
  │     ├── KB 意图 → MultiChannelRetrievalEngine → VectorStoreService
  │     └── MCP 意图 → MCPToolExecutor → 参数抽取 + 工具执行
  ├── if (empty hits) → 短路"未检索到"
  ├── RAGPromptService.buildStructuredMessages         // 组装结构化 prompt
  └── LLMService.streamChat(..., StreamChatEventHandler)
         (modelStreamExecutor 线程：content/thinking/usage 帧 → SseEmitterSender)

onComplete:
  ├── ConversationMemoryService.appendAssistant(..)    // 回答入库
  ├── token 用量写 t_rag_trace_run.extra_data (Jackson!)
  └── EvaluationCollector.snapshot → t_rag_evaluation_record

AOP: 链路追踪结束 + 限流出队
```

### 3.2 扩展点（均为接口 + `@Component` 自动装配）

| 扩展 | 契约 | 注册方式 |
| --- | --- | --- |
| 检索通道 | `core/retrieve/channel/SearchChannel` | `@Component` 即被 `MultiChannelRetrievalEngine` 注入 `List<>` |
| 后处理器 | `core/retrieve/postprocessor/SearchResultPostProcessor` | 按 `getOrder()` 串行 |
| MCP 工具 | `core/mcp/MCPToolExecutor` + 意图树 `NodeType=MCP` 节点 | identifier 对齐工具名 |
| 向量库实现 | `core/vector/VectorStoreService` + `VectorStoreAdmin` | `@ConditionalOnProperty(rag.vector.type=...)` |

### 3.3 链路追踪

- **ThreadLocal 基础设施**：`framework.trace.RagTraceContext`（TTL）。
- **标注**：`@RagTraceNode("name")` 注解在 service 方法上。
- **持久化**：`t_rag_trace_run`（整条链路）+ `t_rag_trace_node`（每节点），扩展字段通过 `extra_data TEXT` JSON。
- **写入必须用 Jackson**：Gson 会把 int 序列化为 `"5228.0"`，破坏 Dashboard 的 `CAST(... AS INTEGER)`。

## 4. ingestion 域（文档 ETL 流水线）

**节点链模式**：`IngestionEngine` 顺序调用每个 `IngestionNode`，通过 `IngestionContext` 共享状态。

```
Fetcher → Parser → [Enhancer] → Chunker → [Enricher] → Indexer
```

| 节点 | 接口 | 典型实现 |
| --- | --- | --- |
| Fetcher | `FetcherNode` → `FetcherStrategy` | LocalFile / HttpUrl / S3 / Feishu |
| Parser | `ParserNode` | `TikaDocumentParser`（内嵌 Tika 3.2） |
| Enhancer | `EnhancerNode`（可选） | LLM 文档级改写（标题/摘要） |
| Chunker | `ChunkerNode` + `ChunkingStrategy` | FixedSizeText / StructureAware，产出后直接 batch embed |
| Enricher | `EnricherNode`（可选） | LLM 分块级增强（问答对、关键词） |
| Indexer | `IndexerNode` → `VectorStoreService.writeBatch` | OpenSearch / Milvus / pgvector |

**Pipeline 定义持久化**：`t_ingestion_pipeline` + `t_ingestion_pipeline_node`。管理后台可视化编辑（前端 `pages/admin/ingestion`）。

## 5. knowledge 域（KB 与文档管理 + RocketMQ）

### 5.1 文档入库链路

```
POST /knowledge-base/{kbId}/docs/upload
  → FileStorageService 存 S3 (RustFS)
  → INSERT t_knowledge_document (status=PENDING)

POST /knowledge-base/docs/{docId}/chunk           // 用户手动触发
  → kbAccessService.checkManageAccess(kbId)
  → RocketMQ 事务消息 (事务回调: status=RUNNING)
  → 立即返回 200

KnowledgeDocumentChunkConsumer @IdempotentConsume
  → KnowledgeDocumentServiceImpl.executeChunk()
      ├── CHUNK 模式：Extract(Tika) → Chunk(策略) → Embed → Persist
      └── PIPELINE 模式：IngestionEngine.execute(pipelineId)
  → 原子事务：删旧 chunk+向量 → 写新 chunk+向量 → status=SUCCESS
  → 记录 t_knowledge_document_chunk_log（阶段耗时）
```

**分布式锁**：URL 源定时重分块走 Redisson，避免同一文档被并发处理。

### 5.2 Knowledge Spaces

- `t_conversation.kb_id` 区分会话所属空间。
- `validateKbOwnership()` 用 `Objects.equals()` 做 null 安全比较；`kb_id=NULL` 的旧会话 fail-closed。
- 所有会话接口（messages/rename/delete）必须传 `kbId`，后端做所有权校验。

## 6. user 域（Sa-Token + RBAC）

### 6.1 核心抽象

| 抽象 | 说明 |
| --- | --- |
| `RoleType` 枚举 | `SUPER_ADMIN` / `DEPT_ADMIN` / `USER` |
| `LoginUser`（TTL UserContext） | `userId · username · deptId · roleTypes · maxSecurityLevel` |
| `Permission` 枚举 | `READ < WRITE < MANAGE`（`ordinal()` 可比较） |
| `t_role_kb_relation` | 角色 × 知识库 × maxSecurityLevel（0-3） |

### 6.2 KbAccessService（唯一真相源）

| 方法 | 用途 | 缓存 |
| --- | --- | --- |
| `getAccessibleKbIds(userId)` | 返回可访问 KB 集合 | Redis `kb_access:{userId}` / `kb_access:dept:{userId}` |
| `checkAccess(kbId)` / `checkManageAccess(kbId)` | 单 KB 读/管权限 | — |
| `getMaxSecurityLevelForKb(userId, kbId)` | 按 KB 解析安全等级 | Redis Hash `kb_security_level:{userId}` |
| `validateRoleAssignment(roleIds)` | DEPT_ADMIN 分配约束 | — |
| `simulateActiveSuperAdminCountAfter(intent)` | Last-SUPER_ADMIN 守护 | — |

**DEPT_ADMIN 隐式权限**：`kb.dept_id == self.dept_id` 的 KB 无需 `role_kb_relation` 即拥有 MANAGE。跨部门必须显式绑定。

### 6.3 已知架构债

- **security_level 过滤仅 OpenSearch 实现**：`MilvusRetrieverService` 和 `PgRetrieverService` 静默忽略 `metadataFilters` 参数。切换 `rag.vector.type` 到 milvus/pg 前必须补齐（`../followup/backlog.md` SL-1）。

## 7. admin 域（仪表盘）

- **只读跨域聚合**：`DashboardServiceImpl` 注入 `rag/knowledge/user` 三域的 Mapper，做 SQL 聚合。
- **不写任何表**，不破坏域边界。
- 所有页面在前端 `pages/admin/dashboard/`。

## 8. 数据访问约定

- ORM：MyBatis Plus 3.5.14；实体用 `@TableName` + `@TableId(type=ASSIGN_ID)` 默认走 Snowflake。
- 逻辑删除：`@TableLogic` 自动追加 `WHERE deleted=0`。**不要**再手动 `.eq(::getDeleted, 0)`。
- PostgreSQL 标识符小写折叠：`selectMaps` 的 `.select("kb_id AS kbId")` 产出 map key 是 `kbid`。始终用 snake_case 别名。
- 两份 Schema：`resources/database/schema_pg.sql`（干净 DDL）和 `full_schema_pg.sql`（pg_dump 风格）必须同步维护。

## 9. 关键跨层依赖

| 依赖方 | 依赖对象 | 使用方式 |
| --- | --- | --- |
| `RAGChatServiceImpl` | `infra-ai` LLMService/Embedding/Rerank | 构造器注入 |
| `IngestionEngine` | `infra-ai` LLMService（可选 Enhancer/Enricher）、EmbeddingService | 构造器注入 |
| 所有 controller | `framework` `Results` 工厂、`GlobalExceptionHandler` | 自动装配 |
| 所有异步代码 | `framework.trace.RagTraceContext`（TTL） | 必须用 TTL，不用普通 ThreadLocal |
| RBAC | `framework.context.UserContext`（Sa-Token 填） | 仅在 HTTP 请求线程有效 |

## 10. 评审关注点

- **扩展点契约清晰**：新增向量库/模型供应商/检索通道/入库节点都只需实现接口 + `@Component`，配置驱动生效。改业务代码的成本低。
- **域边界守得住**：跨域聚合集中在 `admin/`，写操作走 MQ 或 service 注入。评审时需警惕有无"顺手查一下别域 DAO"。
- **异步/流式的 ThreadLocal 陷阱反复出现**：统一用 TTL `RagTraceContext`；`UserContext` 禁止进异步线程（需捕获 `userId` 传参）。
- **架构债已记录**：`docs/dev/followup/backlog.md` 收录 security_level 过滤缺失、两份 schema 维护成本等；评审建议结合 followup 决定本轮是否还债。
