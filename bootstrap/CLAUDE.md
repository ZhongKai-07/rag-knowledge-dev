# bootstrap 模块

bootstrap 是项目的主 Spring Boot 应用模块，包含所有业务域的完整实现。应用启动在端口 **9090**，上下文路径 `/api/ragent`。

## 构建与运行

```bash
# 单独编译本模块（跳过测试）
mvn -pl bootstrap install -DskipTests

# 启动应用（在项目根目录执行，绕过代理避免 RustFS/S3 连接问题）
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run

# 运行单个测试类
mvn -pl bootstrap test -Dtest=SimpleIntentClassifierTests

# 运行单个测试方法
mvn -pl bootstrap test -Dtest=SimpleIntentClassifierTests#testMethod
```

配置文件：`bootstrap/src/main/resources/application.yaml`

## 代码组织

所有业务代码在 `src/main/java/com/nageoffer/ai/ragent/` 下，按**业务域**划分（而非技术层）。每个域内部遵循 `controller/ → service/ → dao/` 分层。

```
rag/          ← RAG 问答核心（最大，含检索、向量、记忆、Prompt、意图等）
ingestion/    ← 文档 ETL 流水线（节点链模式）
knowledge/    ← 知识库 CRUD、文档管理、RocketMQ 异步更新
core/         ← 文档解析（Tika）和分块策略
admin/        ← 仪表板统计（只读跨域聚合）
user/         ← 认证（Sa-Token）、用户、RBAC 权限
```

## 各域关键类

### rag 域

| 类 | 职责 |
|----|------|
| `RAGChatController` | SSE 流式聊天入口 `GET /rag/v3/chat` |
| `RAGChatServiceImpl` | 问答主编排：记忆→改写→意图→检索→生成 |
| `RetrievalEngine` | 多通道检索引擎（并行执行各检索通道） |
| `MultiChannelRetrievalEngine` | 意图导向 + 全局向量检索并行去重重排 |
| `AuthzPostProcessor` | 检索后置纵深防御（order=0）：`kbId==null` / `AccessScope` / `security_level` 天花板三重 fail-closed；命中即 ERROR 日志 |
| `MetadataFilterBuilder` | 按 kb 注入 `security_level` 过滤条件的可注入 bean（替代原 static `MultiChannelRetrievalEngine.buildMetadataFilters`）|
| `VectorMetadataFields` | OpenSearch metadata 字段名常量单一真相源（`KB_ID` / `SECURITY_LEVEL`），避免字面量漂移 |
| `RAGPromptService` | 根据检索结果和场景构建结构化 Prompt |
| `PromptTemplateUtils.fillSlots(template, Map)` | `{slot}` 模板填充工具（不要手写 `.replace()` 链） |
| `VectorStoreService` | 向量存储接口（Milvus/OpenSearch/pgvector 三种实现） |
| `DefaultIntentClassifier` | LLM 驱动的意图分类（Domain→Category→Topic 树） |
| `ConversationMemoryService` | 会话记忆加载、追加、摘要（JDBC 持久化） |
| `QueryRewriteService` | 查询改写与子问题拆分 |
| `RagTraceContext` | 基于 TTL 的链路追踪上下文（ThreadLocal） |

向量库类型通过 `rag.vector.type` 配置切换：`milvus` / `opensearch` / `pg`。

### ingestion 域

| 类 | 职责 |
|----|------|
| `IngestionEngine` | 节点链编排器，顺序执行各节点 |
| `IngestionNode` | 节点接口，所有节点实现此接口 |
| `FetcherNode` | 从 LocalFile/HttpUrl/S3/Feishu 获取文档 |
| `ParserNode` | 调用 Tika 解析文档内容 |
| `ChunkerNode` | 分块 + Embedding 向量化 |
| `EnhancerNode` | LLM 文档级增强（可选） |
| `EnricherNode` | LLM 分块级增强（可选） |
| `IndexerNode` | 向量写入存储 |
| `IngestionContext` | 贯穿整条流水线的共享状态对象 |
| `PipelineDefinition` | 流水线定义（节点列表 + 每个节点的 NodeConfig） |

### knowledge 域

| 类 | 职责 |
|----|------|
| `KnowledgeBaseController` | 知识库 CRUD |
| `KnowledgeDocumentController` | 文档上传、触发分块 |
| `KnowledgeDocumentServiceImpl` | 文档处理核心（CHUNK/PIPELINE 两种模式） |
| `KnowledgeDocumentChunkConsumer` | RocketMQ 消费者，异步驱动分块流程 |
| `SpacesController` | Knowledge Spaces 统计接口 |

### user 域

| 类 | 职责 |
|----|------|
| `KbAccessService` | `@Deprecated` 上帝接口；2026-04-18 RBAC 改造后保留用于 47 调用点分批迁移，新代码直接注入 framework `security/port/` 下的 7 个小 port |
| `KbAccessServiceImpl` | 同时 implements 全部 7 个 framework security port（`CurrentUserProbe` / `KbReadAccessPort` / `KbManageAccessPort` / `UserAdminGuard` / `SuperAdminInvariantGuard` / `KbAccessCacheAdmin` + `KbMetadataReader` 由 `KbMetadataReaderImpl` 在 knowledge 域实现）|
| `AuthController` | 登录/登出（Sa-Token） |
| `RoleService` | 角色-知识库关联管理 |
| `SysDeptService` | 部门 CRUD（GLOBAL 硬保护 + 删除前引用计数校验） |
| `SysDeptController` | 部门 REST API（读 AnyAdmin / 写 `@SaCheckRole("SUPER_ADMIN")`） |
| `UserProfileLoader` | 单次 JOIN 加载用户身份快照（user+dept+roles），不走缓存 |
| ~~`SuperAdminMutationIntent`~~ | ⚠️ 已于 2026-04-18 迁至 `framework.security.port.SuperAdminMutationIntent`，此处不再保留 |

## 关键 Gotchas

- **SSE 是异步的**：`streamChat()` 立即返回，`StreamCallback` 在独立线程池上执行。不要在调用线程读取回调内设置的 ThreadLocal 值。
- **SUPER_ADMIN 权限**：`KbAccessService.getAccessibleKbIds()` 对 `SUPER_ADMIN` 直接返回全量 KB（在 service 内部处理，不再依赖 controller 层判断）。判断当前用户是否超管用 `kbAccessService.isSuperAdmin()`。`@SaCheckRole` 注解用 `"SUPER_ADMIN"` 而非 `"admin"`（PR1 已全局替换，旧字符串不存在了）。
- **@TableLogic 自动过滤**：带 `@TableLogic` 的实体 MyBatis Plus 自动追加 `WHERE deleted=0`，不要再手动 `.eq(::getDeleted, 0)`。
- **两份 Schema 文件**：`schema_pg.sql`（干净 DDL）和 `full_schema_pg.sql`（pg_dump 格式）需同步维护。改表结构时必须两个都改。
- **PostgreSQL 小写折叠**：`selectMaps` 中 `.select("kb_id AS kbId")` 的 map key 是 `kbid` 不是 `kbId`。始终用 snake_case 别名（`AS kb_id`）再用 `row.get("kb_id")` 取值。
- **RagTraceContext 提前清空**：`ChatRateLimitAspect.finally` 在流结束前就清了上下文。在回调构造器里捕获 `traceId` 等值，不要等到回调时再读。
- **Knowledge Spaces**：`t_conversation.kb_id` 区分会话所属空间。`validateKbOwnership()` 用 `Objects.equals()` 做 null 安全比较；`kb_id=NULL` 的旧会话是 fail-closed 的（不属于任何空间）。
- **`t_knowledge_base` 没有 `description` 列**：schema_pg.sql 里只有 `id / name / embedding_model / collection_name / created_by / updated_by / dept_id`。`embedding_model` 和 `created_by` 是 NOT NULL 无默认。写 INSERT 或 fixture 时必须提供这两个字段，且不要尝试插入 `description`。
- **`t_user_role.id` 和 `t_role_kb_relation.id` 是显式主键**：`VARCHAR(20) NOT NULL PRIMARY KEY`，无自动生成（不同于 `@TableId(type=ASSIGN_ID)` 的 Java 层雪花 ID）。手写 SQL INSERT 时必须显式提供 `id` 值，否则 NOT NULL 违反。
- **Controller 参数注解必须写显式名称**：`@RequestParam("name") String name`、`@PathVariable("id") String id` — 禁止省略名称依赖参数名推断（IntelliJ 本地跑正常，`mvn` 命令行会报 `IllegalArgumentException`）。同类型多 Bean 时构造函数参数必须加 `@Qualifier("beanName")`，不能依赖 Lombok `@RequiredArgsConstructor` 自动推断。

## 数据库访问

```bash
docker exec postgres psql -U postgres -d ragent -c "SQL语句"
```

用户是 `postgres`，不是 `ragent`。

## 主要配置节

| 配置节 | 用途 |
|--------|------|
| `rag.vector.type` | 向量库选择（milvus/opensearch/pg） |
| `rag.query-rewrite` | 查询改写开关和历史消息轮数 |
| `rag.rate-limit` | 全局并发限制 |
| `rag.memory` | 会话记忆保留轮数、摘要开关、TTL |
| `rag.search.channels` | 各检索通道的置信度阈值 |
| `ai.chat.candidates` | 聊天模型候选列表（含优先级） |
| `ai.embedding.candidates` | Embedding 模型候选列表 |
| `ai.rerank` | 重排模型配置 |
| `opensearch` | OpenSearch 地址、分析器、向量/文本权重 |
| `rustfs` | 对象存储配置（S3 兼容） |
| `sa-token` | 认证 Token 名称和有效期 |
