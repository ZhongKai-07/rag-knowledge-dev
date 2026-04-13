# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build entire project (skip tests for speed)
mvn clean install -DskipTests

# Build with code formatting check (Spotless)
mvn clean install -DskipTests spotless:check

# Auto-fix formatting
mvn spotless:apply

# Run the application (from project root, auto-bypass localhost proxy for RustFS/S3)
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn -pl bootstrap test -Dtest=SimpleIntentClassifierTests

# Run a single test method
mvn -pl bootstrap test -Dtest=SimpleIntentClassifierTests#testMethod
```

The application starts on port **9090** with context path `/api/ragent`.

## Project Structure

Four Maven modules with parent POM at root:

- **bootstrap** — Main Spring Boot app. Contains all domain business logic (controllers, services, DAOs, domain models). This is where nearly all development happens.
- **framework** — Cross-cutting infrastructure: common abstractions, utilities, base classes shared across domains.
- **infra-ai** — AI infrastructure layer: LLM client abstractions, model routing, embedding services.
- **mcp-server** — MCP (Model Context Protocol) server implementation for tool integration.

## Architecture

The bootstrap module organizes code by **domain** (not by technical layer):

- **rag/** — Core RAG orchestration: query rewriting, intent classification, multi-channel retrieval, conversation memory, prompt engineering, vector search, MCP tool dispatch, full-chain tracing.
- **ingestion/** — Document ETL pipeline using a **node composition pattern**: fetch → parse → enhance → chunk → vectorize → store. Nodes are composable processing units orchestrated by a pipeline engine.
- **knowledge/** — Knowledge base/collection CRUD, document management, chunk management, status tracking. Uses RocketMQ for async event-driven updates.
- **core/** — Document parsing (Apache Tika) and chunking strategies.
- **admin/** — Dashboard KPIs, overview statistics.
- **user/** — Authentication (Sa-Token), user management, RBAC role-based knowledge base access control.

Within each domain, code follows a standard layered pattern: `controller/` → `service/` → `dao/` (MyBatis Plus mappers) → `domain/` (entities, DTOs, enums).

### Key Design Patterns

- **Multi-channel retrieval**: Intent-directed search + global vector search run in parallel, results are deduplicated and reranked.
- **Hierarchical intent classification**: Domain → Category → Topic tree with confidence scoring; low-confidence triggers user guidance/clarification.
- **Model routing with failover**: Priority-based multi-model scheduling with health checks and automatic degradation.
- **Node-based pipeline**: Ingestion uses composable node chain (similar to middleware pattern) for flexible ETL.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| JDK | Java 17 |
| Framework | Spring Boot 3.5.7 |
| Database | PostgreSQL |
| Vector DB | OpenSearch 2.18 / Milvus 2.6 / pgvector（三选一，`rag.vector.type` 切换） |
| ORM | MyBatis Plus 3.5.14 |
| Cache | Redis + Redisson |
| Message Queue | RocketMQ 5.x |
| Object Storage | S3-compatible (RustFS) |
| Doc Parsing | Apache Tika 3.2 |
| Auth | Sa-Token 1.43 |
| Code Format | Spotless (enforced in build) |
| Frontend | React 18 + Vite + TypeScript + TailwindCSS |

## Configuration

Main config: `bootstrap/src/main/resources/application.yaml`

Key config sections: database, Redis, RocketMQ, Milvus, AI model providers (Ollama, BaiLian/Alibaba, SiliconFlow), RAG parameters (rewrite settings, search channels, confidence thresholds, memory limits).

## Infrastructure

Docker Compose files in `resources/docker/` for Milvus and RocketMQ. Database init scripts in `resources/database/`.

Full environment setup guide (Docker containers + DB init + backend/frontend start): `docs/dev/launch.md`

Upgrade scripts in `resources/database/`:
- `upgrade_v1.2_to_v1.3.sql` — adds `kb_id` to `t_conversation` for knowledge space isolation

## Language

Project documentation and comments are in Chinese. Code identifiers are in English.

## Key Gotchas

- **SSE streaming is async**: `ChatClient.streamChat()` returns immediately via `StreamAsyncExecutor`; `StreamCallback` methods run on `modelStreamExecutor` thread pool. Do NOT read ThreadLocal values set by streaming callbacks from the calling thread — they won't be there yet.
- **OpenAI SSE usage frame order**: `finish_reason` frame → usage frame (empty choices) → `[DONE]`. Loop must break on `[DONE]` (`streamEnded`), not on `finish_reason` (`finished`), otherwise usage data is missed.
- **RagTraceContext ThreadLocal cleared early**: `ChatRateLimitAspect.finally` clears context before streaming completes. Capture `traceId` etc. in handler constructor, not at callback time.
- **`extra_data TEXT` JSON pattern**: Used in `t_rag_trace_run` and `t_rag_trace_node` for extensible metrics (token usage, question length) without schema migration. Parse with Gson in query service.
- **Database access via Docker**: `docker exec postgres psql -U postgres -d ragent -c "SQL"`. User is `postgres`, not `ragent`.
- **Two schema files maintained independently**: `schema_pg.sql` (clean DDL) and `full_schema_pg.sql` (pg_dump style) must BOTH be updated when changing table schemas. Forgetting one causes init/upgrade divergence.
- **`full_schema_pg.sql` COMMENT placement**: COMMENTs are in separate blocks (not inline after CREATE TABLE). Each has its own `-- Name: COLUMN ...; Type: COMMENT` header block. When adding columns, add the COMMENT in a new block near existing comments for the same table.
- **Admin RBAC special case**: `KbAccessService.getAccessibleKbIds()` for `SUPER_ADMIN` returns all KBs (enforced inside the service, not at controller layer). Use `kbAccessService.isSuperAdmin()` to check admin status — do NOT use `"admin".equals(UserContext.getRole())` (that string is gone since PR1). `@SaCheckRole` annotations use `"SUPER_ADMIN"` not `"admin"`.
- **@TableLogic auto-filter**: MyBatis Plus entities with `@TableLogic` on `deleted` field automatically append `WHERE deleted=0`. Do NOT add redundant `.eq(::getDeleted, 0)` conditions in queries.
- **PostgreSQL folds unquoted identifiers to lowercase**: `selectMaps` with `.select("kb_id AS kbId")` produces map key `kbid`, not `kbId`. Always use snake_case aliases (`AS kb_id`, `AS doc_count`) and `row.get("kb_id")` — never camelCase.
- **Frontend HMR vs Backend restart**: Vite dev server hot-reloads frontend changes instantly. Spring Boot requires manual restart (`mvn -pl bootstrap spring-boot:run`) after any Java code change. Always confirm backend is restarted before verifying backend changes.
- **`mvn spring-boot:run` does NOT recompile stale classes after branch switch**: After `git checkout`, old `.class` files in `target/` remain. Run `mvn clean -pl bootstrap spring-boot:run` on first run in a new branch or new machine to force full recompilation with `-parameters`.
- **`-parameters` flag: IntelliJ vs Maven divergence**: IntelliJ adds `-parameters` automatically; Maven only does so if `maven-compiler-plugin` has `<parameters>true</parameters>`. Without it: (1) `@RequestParam`/`@PathVariable` without explicit `value=` throw `IllegalArgumentException` at runtime; (2) `@RequiredArgsConstructor` on a bean with multiple candidates of the same type fails to inject. **Rule**: always write explicit `value=` on all `@RequestParam`/`@PathVariable` annotations; use `@Qualifier` for ambiguous bean types.
- **API signature changes require full-text search**: When backend adds/changes required parameters (e.g., adding `@RequestParam String kbId`), grep ALL frontend callers — not just the ones listed in the plan. Missing callers cause runtime 400 errors.
- **Sa-Token auth header is raw token, no Bearer prefix**: `Authorization: <token>` (NOT `Authorization: Bearer <token>`). See `application.yaml` `sa-token.token-name: Authorization` and `api.ts:15`. All permission rejections (NotRoleException, ClientException) return **HTTP 200** with `code != "0"` in the `Result` body — NOT HTTP 403/409. Assert on `code` field, never on HTTP status code.
- **Table naming convention is inconsistent**: Most tables use `t_` prefix (`t_user`, `t_role`, `t_knowledge_base`), but the department table is `sys_dept` (with entity `SysDeptDO`, mapper `SysDeptMapper`). When searching for department-related code, grep for `sys_dept` / `SysDept`, NOT `t_department` / `Dept`.
- **Seed data is not blank**: `init_data_pg.sql` wires admin user with `dept_id='1'` (GLOBAL), role `超级管理员` (`role_type=SUPER_ADMIN`, `max_security_level=3`), and `t_user_role` linking them. A fresh DB with `schema_pg.sql + init_data_pg.sql` already has a fully-privileged admin — not a "no dept / no role / max=0" user.
- **security_level filter only implemented in OpenSearch**: `MilvusRetrieverService` and `PgRetrieverService` accept `metadataFilters` parameter but silently ignore it. Switching `rag.vector.type` to `milvus` or `pg` disables document security_level enforcement at retrieval time. Fix these implementations before using non-OpenSearch backends in production.
- **Every controller needs explicit authorization**: `SaInterceptor` only enforces `StpUtil.checkLogin()` (login check), NOT role checks. New controllers must add their own `@SaCheckRole` or programmatic `kbAccessService` checks. `DashboardController` was audited and fixed for this in PR3.
- **Per-KB security_level filtering**: `t_role_kb_relation.max_security_level` (SMALLINT, 0-3) controls per-KB retrieval filtering. `KbAccessService.getMaxSecurityLevelForKb(userId, kbId)` resolves it (SUPER_ADMIN=3, DEPT_ADMIN same-dept=role ceiling, others=MAX from relation). Cached in Redis Hash `kb_security_level:{userId}`, evicted alongside `kb_access:` cache.
- **KB-centric sharing API**: `GET/PUT /knowledge-base/{kb-id}/role-bindings` (note: hyphenated `kb-id` in path, not `kbId`). SUPER_ADMIN any KB, DEPT_ADMIN own-dept only. Uses `checkKbRoleBindingAccess()`.
- **DEPT_ADMIN implicit MANAGE on same-dept KBs**: `checkManageAccess()` and `checkAccess()` both pass for `kb.dept_id == self.dept_id` without needing `role_kb_relation` entries. Cross-dept access requires explicit binding.
- **Frontend permission-gated components must handle backend rejection**: Components rendered for `isAnyAdmin` that call DEPT_ADMIN-restricted endpoints should catch errors and hide gracefully (e.g., `KbSharingTab` sets `noAccess=true` and returns `null`), not show error toasts. The backend is the authorization boundary; the frontend optimistically renders and fails gracefully.

## RAG Evaluation (RAGAS)

- `t_rag_evaluation_record` stores query-chunk-answer triples for evaluation
- `EvaluationCollector` gathers data via `RagTraceContext` ThreadLocal across the RAG pipeline
- Export endpoint: `GET /rag/evaluations/export` produces RAGAS-compatible JSON
- RAGAS evaluation script: `ragas/ragas/run_eval.py` (uses 百炼 API as evaluator LLM)

## Knowledge Spaces Architecture

- Login redirects to `/spaces` (knowledge base hub), not `/chat`
- `t_conversation.kb_id` associates conversations with knowledge bases (added in v1.3 migration)
- Frontend URL `?kbId=xxx` is the single source of truth for KB locking; `chatStore.activeKbId` is a derived cache
- Entering a new space calls `resetForNewSpace()` to clear old sessions/messages
- All conversation endpoints (`messages`, `rename`, `delete`) require `kbId` as a mandatory parameter for ownership validation
- `validateKbOwnership()` uses `Objects.equals()` for null-safe comparison; old conversations with `kb_id=NULL` are fail-closed (don't belong to any space)
- Brand name: "HT KnowledgeBase" (changed from "Ragent AI 智能体" — affected files: index.html, .env, Sidebar.tsx, AdminLayout.tsx)

## Frontend Patterns

- Admin pages: create component in `frontend/src/pages/admin/{feature}/`, register route in `router.tsx`, add sidebar item + breadcrumb in `AdminLayout.tsx`
- UI components: shadcn/ui in `frontend/src/components/ui/`; icons from `lucide-react`
- API services: `frontend/src/services/` using axios instance from `api.ts`; response wrapper `{ code, data, message }`

## Project Code Map

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
│   ├── ParserNode                ← Tika 解析
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

core/                             ← 基础能力域（17 文件）
├── chunk/                        ← ChunkEmbeddingService, ChunkingStrategy, VectorChunk
│   └── strategy/                 ← FixedSizeTextChunker, StructureAwareTextChunker
└── parser/                       ← DocumentParserSelector, TikaDocumentParser

user/                             ← 用户与权限域（31 文件）
├── controller/
│   ├── AuthController            ← 登录/登出
│   ├── UserController            ← 用户 CRUD
│   └── RoleController            ← 角色管理
├── service/
│   ├── KbAccessService           ← RBAC 知识库权限校验（核心）
│   └── RoleService               ← 角色-知识库关联管理
└── dao/entity/                   ← UserDO, RoleDO, UserRoleDO, RoleKbRelationDO

admin/                            ← 管理后台域（10 文件）
├── controller/
│   └── DashboardController       ← 仪表盘 KPI/趋势/性能
└── service/
    └── DashboardServiceImpl      ← 跨域聚合统计（只读）
```

## Core Business Flows

### 1. RAG 问答链路

```
GET /rag/v3/chat → RAGChatController → RAGChatServiceImpl.streamChat()

AOP: 限流入队 + traceId 生成 + 链路追踪开始
 → RBAC 权限校验（KbAccessService.getAccessibleKbIds）
 → 加载会话记忆 + 追加当前问题（ConversationMemoryService.loadAndAppend）
 → 查询改写 + 子问题拆分（QueryRewriteService.rewriteWithSplit）
 → 意图识别（IntentResolver.resolve → DefaultIntentClassifier，LLM 调用，并行）
 → 歧义引导检测（IntentGuidanceService.detectAmbiguity）→ 触发则短路返回引导提示
 → System-Only 检测 → 全是 SYSTEM 意图则跳过检索直接调 LLM
 → 多通道检索（RetrievalEngine.retrieve，并行）
   ├── KB 意图 → MultiChannelRetrievalEngine → 向量检索（过滤可访问 KB）→ 去重 → 重排
   └── MCP 意图 → MCPToolExecutor → 参数提取（LLM）→ 工具执行
 → 检索结果为空则短路返回"未检索到"
 → 上下文组装（RAGPromptService.buildStructuredMessages）
 → LLM 流式生成（SSE 推送，temperature 根据 MCP 动态调整）
 → onComplete: 回答写入记忆 + token 用量记录 + 评估数据采集
AOP: 链路追踪结束 + 限流出队
```

### 2. 知识库管理链路

```
POST /knowledge-base           → 创建知识库（DB 记录 + VectorStoreAdmin.ensureVectorSpace 创建向量索引）
GET  /knowledge-base           → 列表查询（RBAC 过滤可访问的 KB）
PUT  /knowledge-base/{id}      → 更新知识库元信息
DELETE /knowledge-base/{id}    → 删除知识库 + 关联文档 + 向量数据
```

### 3. 文档入库链路

```
POST /knowledge-base/{kbId}/docs/upload → KnowledgeDocumentController.upload()
 → FileStorageService 存文件到 S3
 → 创建 KnowledgeDocumentDO（status=PENDING）
 → 返回 200

POST /knowledge-base/docs/{docId}/chunk → KnowledgeDocumentController.startChunk()
 → RBAC 权限校验
 → RocketMQ 事务消息发送（事务回调中更新 status=RUNNING）
 → 返回 200（异步处理）

KnowledgeDocumentChunkConsumer 异步消费：
 → KnowledgeDocumentServiceImpl.executeChunk()
   ├── CHUNK 模式: Extract(Tika) → Chunk(策略分块) → Embed(向量化) → Persist
   └── PIPELINE 模式: IngestionEngine.execute()（Fetcher→Parser→[Enhancer]→Chunker→[Enricher]→Indexer）
 → 原子事务: 删旧分块/向量 → 写新分块/向量 → 更新 status=SUCCESS
 → 记录 ChunkLog（各阶段耗时）
```

### 4. 后台管理链路

```
GET /admin/dashboard/overview     → 概览 KPI（文档总数、知识库数、对话数等）
GET /admin/dashboard/performance  → 性能指标（处理成功率、平均耗时）
GET /admin/dashboard/trends       → 趋势分析（时间窗口内的对话/文档趋势）

DashboardServiceImpl 跨域只读查询 rag + knowledge + user 的 Mapper 做聚合统计。
管理后台所有页面在 frontend/src/pages/admin/ 下，路由注册在 router.tsx。
```

### 5. RBAC 用户权限管理链路

```
认证:
  POST /auth/login  → Sa-Token 签发 Token
  POST /auth/logout → 注销

用户管理:
  GET/POST/PUT/DELETE /users → UserController（admin only）

角色管理（SUPER_ADMIN only）:
  POST/PUT/DELETE /role           → 角色 CRUD
  PUT  /role/{roleId}/knowledge-bases → 设置角色可访问的知识库列表（含 maxSecurityLevel）
  PUT  /user/{userId}/roles       → 给用户分配角色

KB 共享管理（AnyAdmin）:
  GET /knowledge-base/{kb-id}/role-bindings  → 查看 KB 的角色绑定
  PUT /knowledge-base/{kb-id}/role-bindings  → 全量覆盖 KB 的角色绑定

权限校验（贯穿所有业务链路）:
  KbAccessService.getAccessibleKbIds(userId)
    → 查 t_user_role → 查 t_role_kb_relation → 返回可访问的 kbId 集合（Redis 缓存）
  KbAccessService.checkAccess(kbId)
    → 校验当前用户是否有权访问指定知识库（SUPER_ADMIN 跳过，DEPT_ADMIN 同部门跳过）
  KbAccessService.getMaxSecurityLevelForKb(userId, kbId)
    → 按 KB 解析安全等级（Redis Hash 缓存）

生效点:
  - RAG 检索时: RetrievalEngine 过滤 accessibleKbIds
  - 知识库列表: KnowledgeBaseController 过滤可见 KB
  - 文档操作: KnowledgeDocumentController 校验文档所属 KB 的访问权限
```
