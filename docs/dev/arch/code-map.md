# Project Code Map

Bootstrap module (`bootstrap/src/main/java/com/nageoffer/ai/ragent/`) organized by domain:

```
rag/                              ← RAG 核心域（最大，194 文件）
├── controller/
│   ├── RAGChatController         ← SSE 流式聊天入口 GET /rag/v3/chat
│   ├── ConversationController    ← 会话管理
│   ├── IntentTreeController      ← 意图树 CRUD
│   ├── RagTraceController        ← 链路追踪查询
│   └── RagEvaluationController   ← RAGAS 评测导出
├── service/impl/
│   └── RAGChatServiceImpl        ← 问答主编排（记忆→改写→意图→检索→生成）
├── core/
│   ├── intent/                   ← 意图分类（DefaultIntentClassifier, IntentResolver）
│   ├── retrieve/                 ← 检索引擎（RetrievalEngine, MultiChannelRetrievalEngine）
│   │   ├── channel/              ← 搜索通道（IntentDirected, VectorGlobal）
│   │   ├── postprocessor/        ← 去重 + 重排
│   │   ├── OpenSearchRetrieverService
│   │   ├── MilvusRetrieverService
│   │   └── PgRetrieverService
│   ├── vector/                   ← 向量存储抽象（VectorStoreService/Admin 接口 + 3 种实现）
│   ├── memory/                   ← 会话记忆（JDBC 存储 + 摘要）
│   ├── prompt/                   ← Prompt 构建（RAGPromptService, ContextFormatter）
│   │                                 PR3 起 RAGPromptService 内部私有 buildCitationEvidence + appendCitationRule（citation mode）
│   ├── source/                   ← Answer Sources 聚合 + 质量埋点（PR2 起）
│   │   ├── SourceCardBuilder     ← docId 聚合 → List<SourceCard>（纯聚合 @Service，按 topScore desc clip 到 max-cards）
│   │   └── CitationStatsCollector ← PR3 静态工具，scan(answer, cards) → (total, valid, invalid, coverage) record
│   ├── rewrite/                  ← 查询改写（QueryRewriteService）
│   ├── guidance/                 ← 歧义引导（IntentGuidanceService）
│   └── mcp/                      ← MCP 工具注册与执行
├── aop/                          ← 限流（ChatRateLimitAspect）、链路追踪
├── config/                       ← OpenSearchConfig, MilvusConfig, PgVectorStoreConfig 等
└── dto/                          ← RetrievalContext, EvaluationCollector 等

ingestion/                        ← 文档入库域（64 文件）
├── controller/
│   ├── IngestionPipelineController ← Pipeline CRUD
│   └── IngestionTaskController     ← Task 触发与监控
├── engine/
│   └── IngestionEngine           ← 节点链编排器（核心）
├── node/                         ← 6 类节点
│   ├── FetcherNode               ← 文档获取（LocalFile/HttpUrl/S3/Feishu）
│   ├── ParserNode                ← 数据驱动解析（PR 1 起）：从 NodeConfig.settings.parseMode 路由 BASIC/ENHANCED
│   ├── EnhancerNode              ← LLM 文档级增强
│   ├── ChunkerNode               ← 分块 + Embedding
│   ├── EnricherNode              ← LLM 分块级增强
│   └── IndexerNode               ← 向量入库
├── strategy/fetcher/             ← 文档源策略实现
└── domain/                       ← PipelineDefinition, IngestionContext, NodeConfig

knowledge/                        ← 知识库管理域（61 文件）
├── controller/
│   ├── KnowledgeBaseController   ← KB CRUD
│   ├── KnowledgeDocumentController ← 文档上传/分块触发
│   └── KnowledgeChunkController  ← 分块操作
├── service/impl/
│   └── KnowledgeDocumentServiceImpl ← 文档处理核心（CHUNK/PIPELINE 两种模式）
├── mq/
│   ├── KnowledgeDocumentChunkConsumer ← RocketMQ 异步分块消费者
│   └── KnowledgeDocumentChunkTransactionChecker
└── schedule/                     ← URL 源定时重分块、分布式锁

core/                             ← 基础能力域
├── chunk/                        ← ChunkEmbeddingService, ChunkingStrategy, VectorChunk
│   └── strategy/                 ← FixedSizeTextChunker, StructureAwareTextChunker
└── parser/                       ← DocumentParserSelector, ParseMode (BASIC/ENHANCED),
                                    ParserType (TIKA/MARKDOWN/DOCLING enum slot),
                                    TikaDocumentParser, MarkdownDocumentParser,
                                    FallbackParserDecorator (PR 2 起 ENHANCED 降级 + metadata stamp),
                                    ParseResult (text + metadata + pages + tables, 4-arg ctor + legacy 2-arg),
                                    layout/ (BlockType, LayoutBlock, DocumentPageText, LayoutTable —
                                             engine-neutral，PR 5 起由 DoclingResponseAdapter mapping 填充)

user/                             ← 用户与权限域
├── controller/
│   ├── AuthController            ← 登录/登出
│   ├── UserController            ← 用户 CRUD
│   └── RoleController            ← 角色管理
├── service/
│   ├── KbAccessService           ← @Deprecated 上帝接口（2026-04-18 RBAC 重构后保留用于调用点分批迁移）
│   │                               新代码直接注入 framework/security/port/ 下 7 个 port
│   │                               （CurrentUserProbe / KbReadAccessPort / KbManageAccessPort /
│   │                                KbRoleBindingAdminPort / UserAdminGuard /
│   │                                SuperAdminInvariantGuard / KbAccessCacheAdmin）
│   ├── RoleService               ← 角色-知识库关联管理
│   ├── UserProfileLoader         ← 单次 JOIN 加载用户身份快照（user+dept+roles），不走缓存
│   └── support/                  ← PR3（2026-04-27）引入 — target-aware 权限计算
│       ├── KbAccessSubject               ← record(userId, deptId, roleTypes, maxSecurityLevel)
│       │                                   isSuperAdmin() / isDeptAdmin()
│       ├── KbAccessSubjectFactory(Impl)  ← 唯一 UserContext/UserProfileLoader→Subject 入口
│       │                                   currentOrThrow() / forTargetUser(userId)
│       └── KbAccessCalculator            ← 纯函数计算器（不 import UserContext / LoginUser，
│                                           ArchUnit 守门）；computeAccessibleKbIds /
│                                           computeMaxSecurityLevels；接管原 KbRbacAccessSupport
└── dao/entity/                   ← UserDO, RoleDO, UserRoleDO, RoleKbRelationDO

admin/                            ← 管理后台域（10 文件）
├── controller/
│   └── DashboardController       ← 仪表盘 KPI/趋势/性能
└── service/
    └── DashboardServiceImpl      ← 跨域聚合统计（只读）
```

---

**模块级关键类表格**请看对应 `CLAUDE.md`：`bootstrap/CLAUDE.md` / `framework/CLAUDE.md` / `infra-ai/CLAUDE.md` / `frontend/CLAUDE.md`。
