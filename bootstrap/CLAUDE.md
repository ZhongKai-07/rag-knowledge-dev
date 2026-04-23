# bootstrap 模块

bootstrap 是项目的主 Spring Boot 应用模块，包含所有业务域的完整实现。应用启动在端口 **9090**，上下文路径 `/api/ragent`。

## 构建与运行

完整命令见**根 `CLAUDE.md`** 的 "Build & Run Commands" 节。常用速记：

```bash
# 单独编译本模块
mvn -pl bootstrap install -DskipTests

# 启动（必须在根目录执行，且 PowerShell 设 NO_PROXY）
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
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
| `RetrievalEngine` | 多通道检索引擎（并行执行各检索通道）。PR2 起 `retrieve(...)` 丢掉 `int topK` 参数，改为注入 `RagRetrievalProperties` + `RetrievalPlan(recallTopK, rerankTopK)` record 贯穿。`resolveSubQuestionPlan` 翻译 `IntentNode.topK` override（保留"最终保留数"语义）到 rerank 端。 |
| `MultiChannelRetrievalEngine` | 意图导向 + 全局向量检索并行去重重排 |
| `AuthzPostProcessor` | 检索后置纵深防御（order=0）：`kbId==null` / `AccessScope` / `security_level` 天花板三重 fail-closed；命中即 ERROR 日志 |
| `MetadataFilterBuilder` | 按 kb 注入 `security_level` 过滤条件的可注入 bean（替代原 static `MultiChannelRetrievalEngine.buildMetadataFilters`）|
| `VectorMetadataFields` | OpenSearch metadata 字段名常量单一真相源（`KB_ID` / `SECURITY_LEVEL` / `DOC_ID` / `CHUNK_INDEX`），避免字面量漂移 |
| `SourceCardBuilder` | 按 `docId` 聚合 `List<RetrievedChunk>` → `List<SourceCard>` 的纯聚合 `@Service`。不理解业务分支（flag/推不推判定/SSE/落库/kbName 查询都不负责） |
| `SourceCardsHolder` | set-once CAS 容器（`AtomicReference<List<SourceCard>>`）。编排层同步段 `trySet`，handler 异步 `onComplete` 通过 `.get()` 读 `Optional` 快照（PR3 起 `mergeCitationStatsIntoTrace` 消费），避开 ThreadLocal |
| `CitationStatsCollector` | 纯工具静态类（`rag/core/source/`）。`scan(answer, cards) → (total, valid, invalid, coverage)` record。`valid` 用 `indexSet.contains(n)` 非 `1..N` range（对齐前端 `indexMap.get(n)` 契约，支持未来非连续 index）；regex `CITATION = \[\^(\d+)]` + `SENTENCE = [^。！？]+[。！？]`；空输入 → `(0,0,0,0.0)` 不除零。SENTENCE 粗切对无终止标点尾段系统性低估 coverage |
| `RagRetrievalProperties` | `rag.retrieval.*` 配置（`recallTopK=30` / `rerankTopK=10`）。PR2 起拆分召回池与最终保留数，支撑 over-retrieve + rerank 漏斗。`@PostConstruct validate()` 校验 `recall >= rerank > 0` — 启动期 fail-stop。 |
| `RagSourcesProperties` | `rag.sources.*` 配置（`enabled` / `previewMaxChars` / `maxCards`，2026-04-22 PR5 起默认 `enabled: true` 上线；`false` 仍是 hot-rollback 通道，字节级回到 PR4 末态） |
| `RAGPromptService` | 根据检索结果和场景构建结构化 Prompt。PR3 起 `buildStructuredMessages` 内部派生 `citationMode = CollUtil.isNotEmpty(ctx.getCards()) && ctx.hasKb()`；私有 `buildCitationEvidence(ctx)` 正文从 `intentChunks.RetrievedChunk.getText()` 全文回收（**不**用 `SourceChunk.preview`）；私有 `appendCitationRule(base, cards)` 动态白名单（size=1 → `[^1]`，≥2 → `[^1] 至 [^N]`）。签名零变化，`cards==null||empty` → PR2 等价回归 |
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
| `KbAccessService` | `@Deprecated` 上帝接口；2026-04-18 RBAC 改造后保留用于历史调用点分批迁移（数量随每轮 PR 下降，实时用 `grep -rn KbAccessService bootstrap/src/main/java` 查）。新代码直接注入 `framework.security.port.*` 下的 7 个小 port |
| `KbAccessServiceImpl` | 同时 implements 全部 7 个 framework security port（`CurrentUserProbe` / `KbReadAccessPort` / `KbManageAccessPort` / `UserAdminGuard` / `SuperAdminInvariantGuard` / `KbAccessCacheAdmin` + `KbMetadataReader` 由 `KbMetadataReaderImpl` 在 knowledge 域实现）|
| `AuthController` | 登录/登出（Sa-Token） |
| `RoleService` | 角色-知识库关联管理 |
| `SysDeptService` | 部门 CRUD（GLOBAL 硬保护 + 删除前引用计数校验） |
| `SysDeptController` | 部门 REST API（读 AnyAdmin / 写 `@SaCheckRole("SUPER_ADMIN")`） |
| `UserProfileLoader` | 单次 JOIN 加载用户身份快照（user+dept+roles），不走缓存 |
| ~~`SuperAdminMutationIntent`~~ | ⚠️ 已于 2026-04-18 迁至 `framework.security.port.SuperAdminMutationIntent`，此处不再保留 |

## 关键 Gotchas（bootstrap 独有）

通用坑点见 `docs/dev/gotchas.md`。以下仅为 bootstrap 域数据/写路径的独有约束：

- **`t_knowledge_base` 没有 `description` 列**：`schema_pg.sql` 里只有 `id / name / embedding_model / collection_name / created_by / updated_by / dept_id`。`embedding_model` 和 `created_by` 是 NOT NULL 无默认。写 INSERT 或 fixture 时必须提供这两个字段，且不要尝试插入 `description`。
- **`t_user_role.id` 和 `t_role_kb_relation.id` 是显式主键**：`VARCHAR(20) NOT NULL PRIMARY KEY`，无自动生成（不同于 `@TableId(type=ASSIGN_ID)` 的 Java 层雪花 ID）。手写 SQL INSERT 时必须显式提供 `id` 值，否则 NOT NULL 违反。
- **Knowledge Spaces 会话归属**：`t_conversation.kb_id` 区分会话所属空间。`validateKbOwnership()` 用 `Objects.equals()` 做 null 安全比较；`kb_id=NULL` 的旧会话是 fail-closed 的（不属于任何空间）。
- **测试里给 `@Value` 字段注值用 `ReflectionTestUtils.setField`**：`spring-boot-starter-test` 已带 `spring-test`，直接 `import org.springframework.test.util.ReflectionTestUtils`，不要手写 `field.setAccessible(true)` + `throws Exception`。
- **回答来源 SSE 事件顺序**（2026-04-21 PR2）：`META → SOURCES → MESSAGE+ → FINISH → (SUGGESTIONS) → DONE`。`SOURCES` 由 `RAGChatServiceImpl` 在检索 + 聚合完成后、LLM 流启动前**同步 emit**（通过 `callback.emitSources(payload)`），**不走 handler 生命周期回调**。这保证 SSE 帧序：`sender.sendEvent` 同步，单个 `SseEmitter` 上 SOURCES 必然在首个 MESSAGE 之前到达。
- **sources 推不推的判定锚点统一用 `distinctChunks.isEmpty()`**：不要用 `RetrievalContext.isEmpty()`（其定义是 `!hasMcp && !hasKb`，MCP-only 成功时 ctx 不 empty 但 KB chunks 为 0，会漏判），也不要用 `hasMcp`（会误伤 mixed KB+MCP 场景）。三层闸门：(1) `rag.sources.enabled=false` 或 (2) `distinctChunks.isEmpty()` → 不调 `SourceCardBuilder`；(3) builder 返回 `cards.isEmpty()` → 不 `trySet` / 不 `emit`。这三条都是"继续回答但跳过 sources"，**不是**"真早返回"（真早返回是 guidance/SystemOnly/`ctx.isEmpty()`）。
- **orchestrator 决策，handler 机械发射**（PR2 起架构约定）：sources 的推不推 / flag 读取 / 三层闸门全在 `RAGChatServiceImpl`；`StreamChatEventHandler.emitSources(payload)` 是纯 delegate 到 `sender.sendEvent(SSEEventType.SOURCES.value(), payload)`，**不加 try/catch，不加业务分支**。后续改动保持边界：业务决策不要下沉到 handler，handler 的机械方法不要上浮到 orchestrator。
- **`onComplete` 埋点顺序硬性**（PR3 起）：`updateTraceTokenUsage()`（overwrite 走 `updateRunExtraData(String)`）必须在 `mergeCitationStatsIntoTrace()`（merge 走 `mergeRunExtraData(Map)`）**之前**执行。颠倒会让 merge 结果被后续 overwrite 清掉。`StreamChatEventHandlerCitationTest` 用 `Mockito.InOrder` 锁此顺序。根治见 backlog SRC-9（把 `updateTraceTokenUsage` 也改成 merge 写）。`onComplete` 里 `traceId` 用构造期 `final` 字段，**不**在异步回调里读 `RagTraceContext.getTraceId()`（零 ThreadLocal 新增是 PR3 硬约束）。
- **sources 判定锚点统一用 indexSet.contains(n)**（PR3 起后端侧）：`CitationStatsCollector.scan` 的 `valid` 判定走 `Set<Integer> validIndexes = cards.stream().map(SourceCard::getIndex).collect(toSet())` 的 `contains(n)`。**绝不**用 `cards[n-1]` 或 `1..N` range — 对齐前端 `indexMap.get(n)` 契约，保未来过滤后非连续 index 的语义不动。同理 `RAGPromptService.appendCitationRule` 当前用 `cards.size()` 作为 range 上界只在 `SourceCardBuilder` 保证 index 1..N 连续的前提下正确；若未来 cards 非连续必须改为 `max(card.index)`。

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
| `rag.retrieval` | 两阶段检索配置（`recall-top-k=30` / `rerank-top-k=10`）。PR2 起生效。`SearchChannelProperties.topKMultiplier` 已 `@Deprecated(forRemoval=true)`，不再读取。 |
| `rag.search.channels` | 各检索通道的置信度阈值 |
| `rag.sources` | 回答来源（Answer Sources）功能开关 + 卡片参数。2026-04-22 PR5 起默认 `enabled: true`；置 `false` 即 hot-rollback 回 PR4 末态（前端 `hasSources=false` 对称 gate + 后端 `SourceCardBuilder` 空输出 + `persistSourcesIfPresent` 空 guard 全链路字节级等价） |
| `ai.chat.candidates` | 聊天模型候选列表（含优先级） |
| `ai.embedding.candidates` | Embedding 模型候选列表 |
| `ai.rerank` | 重排模型配置 |
| `opensearch` | OpenSearch 地址、分析器、向量/文本权重 |
| `rustfs` | 对象存储配置（S3 兼容） |
| `sa-token` | 认证 Token 名称和有效期 |

> **PR5 后补充口径（supersedes 上文"三层闸门"口径）**：`sources` / `suggestions` 的 relevance gate 已统一为 `hasRelevantKbEvidence = !distinctChunks.isEmpty() && maxScore >= rag.sources.min-top-score`（默认 0.55）。当前实际是 **4 层闸门**：(1) feature flag on → (2) `hasRelevantKbEvidence` → (3) `builder` 返回 cards 非空 → (4) `trySetCards` 成功才 `emitSources`。若本文上方仍写"三层闸门"，以此说明为准。
