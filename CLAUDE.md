# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Developer Docs — Read First If Relevant

- **`docs/dev/entry-points.md`** — scenario-driven navigation ("I want to do X, where do I start?"). Check this before exploring the codebase blind.
- **`docs/dev/follow-ups.md`** — deferred tech debt backlog (from the 2026-04-14 `/simplify` review). Consult when picking up related work so you don't reinvent known issues.
- **`docs/dev/follow-up/`** — role-perspective reviews (`CTO_review.md` / `architect_review.md` / `IT_Manager_review.md` / `PM_review.md`) + per-initiative retrospectives (e.g. `2026-04-18-rbac-refactor-retrospective.md`). Read before touching related areas to understand both "what shipped" and "what's the backlog".
- **`docs/dev/security/2026-04-18-authorization-baseline.md`** — endpoint-level authorization matrix. Reference when changing any RBAC-related endpoint.
- **`docs/dev/launch.md`** — full environment bring-up (Docker + DB init + backend/frontend start).
- **`log/dev_log/dev_log.md`** — running development log, indexes detailed per-PR notes under `log/dev_log/YYYY-MM-DD-*.md`.
- **`bootstrap/CLAUDE.md`,** **`frontend/CLAUDE.md`,** **`framework/CLAUDE.md`,** **`infra-ai/CLAUDE.md`** — module-specific "关键类" tables + gotchas.
- **`AGENTS.md`** (root) — agent contract for this repo.

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

**Pre-existing test failures on fresh checkout**: `MilvusCollectionTests`, `InvoiceIndexDocumentTests`, `PgVectorStoreServiceTest.testChineseCharacterInsertion`, `IntentTreeServiceTests.initFromFactory`, `VectorTreeIntentClassifierTests` (10 errors total) fail without Milvus container / pgvector extension / seeded KB data. These are baseline on `main`, not regressions — ignore their failures when validating unrelated changes.

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

| Layer          | Technology                                                        |
| -------------- | ----------------------------------------------------------------- |
| JDK            | Java 17                                                           |
| Framework      | Spring Boot 3.5.7                                                 |
| Database       | PostgreSQL                                                        |
| Vector DB      | OpenSearch 2.18 (生产阶段确定只使用OpenSearch，暂时不考虑其他的切换)/ Milvus 2.6 / pgvector（三选一，`rag.vector.type` 切换） |
| ORM            | MyBatis Plus 3.5.14                                               |
| Cache          | Redis + Redisson                                                  |
| Message Queue  | RocketMQ 5.x                                                      |
| Object Storage | S3-compatible (RustFS)                                            |
| Doc Parsing    | Apache Tika 3.2                                                   |
| Auth           | Sa-Token 1.43                                                     |
| Code Format    | Spotless (enforced in build)                                      |
| Frontend       | React 18 + Vite + TypeScript + TailwindCSS                        |

## Configuration

Main config: `bootstrap/src/main/resources/application.yaml`

Key config sections: database, Redis, RocketMQ, Milvus, AI model providers (Ollama, BaiLian/Alibaba, SiliconFlow), RAG parameters (rewrite settings, search channels, confidence thresholds, memory limits).

## Infrastructure

Docker Compose files in `resources/docker/` for Milvus and RocketMQ. Database init scripts in `resources/database/`.

Full environment setup guide (Docker containers + DB init + backend/frontend start): `docs/dev/launch.md`

Upstream open-source repo: `git remote upstream` → `https://github.com/nageoffer/ragent.git` (added 2026-04-15). Use `git fetch upstream` to compare.

Upgrade scripts in `resources/database/`:

- `upgrade_v1.2_to_v1.3.sql` — adds `kb_id` to `t_conversation` for knowledge space isolation
- `upgrade_v1.3_to_v1.4.sql` — adds `thinking_content`/`thinking_duration` to `t_message` for deep-thinking chain persistence

Full dev-environment wipe + rebuild (**dev only — destroys all data**):

```bash
# PG: drop + recreate (kills active connections first, then re-inits schema)
docker exec postgres psql -U postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='ragent' AND pid<>pg_backend_pid();"
docker exec postgres psql -U postgres -c "DROP DATABASE IF EXISTS ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent ENCODING 'UTF8';"
docker exec -i postgres psql -U postgres -d ragent < resources/database/schema_pg.sql
docker exec -i postgres psql -U postgres -d ragent < resources/database/init_data_pg.sql
# OpenSearch: one index per KB, named after collectionName
curl -X DELETE http://localhost:9201/<collection-name>
# RustFS: bucket == collectionName, data mounted at container /data
docker exec rustfs sh -c "rm -rf /data/<collection-name>"
```

## Language

Project documentation and comments are in Chinese. Code identifiers are in English.

## Key Gotchas

- **SSE streaming is async**: `ChatClient.streamChat()` returns immediately via `StreamAsyncExecutor`; `StreamCallback` methods run on `modelStreamExecutor` thread pool. Do NOT read ThreadLocal values set by streaming callbacks from the calling thread — they won't be there yet.
- **OpenAI SSE usage frame order**: `finish_reason` frame → usage frame (empty choices) → `[DONE]`. Loop must break on `[DONE]` (`streamEnded`), not on `finish_reason` (`finished`), otherwise usage data is missed.
- **RagTraceContext ThreadLocal cleared early**: `ChatRateLimitAspect.finally` clears context before streaming completes. Capture `traceId` etc. in handler constructor, not at callback time.
- **`extra_data TEXT`** **JSON pattern**: Used in `t_rag_trace_run` and `t_rag_trace_node` for extensible metrics without schema migration. Query path uses Gson (`getAsInt()` coerces both `5228` and `5228.0`). **Merge/write path MUST use Jackson** — `gson.fromJson(.., Map.class)` parses all numbers as `Double`, so round-tripping `{"totalTokens":5228}` emits `"5228.0"`, which breaks the Dashboard's `CAST(... AS INTEGER)` SQL. `sumTokensInWindow` defensively does `CAST(CAST(... AS NUMERIC) AS BIGINT)` for legacy rows.
- **`@Data @Builder`** **alone breaks Jackson deserialization**: Lombok's interaction between `@RequiredArgsConstructor` (from `@Data`) and `@Builder`'s all-args constructor doesn't reliably expose a public no-arg constructor. Any class going through Jackson (`ObjectMapper.readValue`, Redis cache via `GenericJackson2`, `@RequestBody`, MQ events) must add explicit **`@NoArgsConstructor @AllArgsConstructor`**. `IntentNode` hit this — every request logged `Cannot construct instance of IntentNode` and fell back to `IntentTreeFactory` rebuild. DO classes using MyBatis Plus are exempt (MP uses its own reflection path).
- **Database access via Docker**: `docker exec postgres psql -U postgres -d ragent -c "SQL"`. User is `postgres`, not `ragent`.
- **Two schema files maintained independently**: `schema_pg.sql` (clean DDL) and `full_schema_pg.sql` (pg\_dump style) must BOTH be updated when changing table schemas. Forgetting one causes init/upgrade divergence.
- **`full_schema_pg.sql`** **COMMENT placement**: COMMENTs are in separate blocks (not inline after CREATE TABLE). Each has its own `-- Name: COLUMN ...; Type: COMMENT` header block. When adding columns, add the COMMENT in a new block near existing comments for the same table.
- **Admin RBAC special case**: `KbAccessService.getAccessibleKbIds()` for `SUPER_ADMIN` returns all KBs (enforced inside the service, not at controller layer). Use `kbAccessService.isSuperAdmin()` to check admin status — do NOT use `"admin".equals(UserContext.getRole())` (that string is gone since PR1). `@SaCheckRole` annotations use `"SUPER_ADMIN"` not `"admin"`.
- **@TableLogic auto-filter**: MyBatis Plus entities with `@TableLogic` on `deleted` field automatically append `WHERE deleted=0`. Do NOT add redundant `.eq(::getDeleted, 0)` conditions in queries.
- **软删表的 UNIQUE 约束必须带 `deleted` 列**：`@TableLogic` 表在业务列加 `UNIQUE (col)` 会和软删语义冲突 —— 软删行仍占该 `col`，"删后重建同名"会被 DB 直接拒绝；但 App 层的 `.eq(::getDeleted, 0)` 查重会通过（认为可复用），两层不一致。正确写法 `UNIQUE (col, deleted)`，参考 `t_ingestion_pipeline.uk_ingestion_pipeline_name`。`t_knowledge_base.uk_collection_name` 于 2026-04-19 按此修复（见 `upgrade_v1.4_to_v1.5.sql`）。新建带软删的表一律套用此模式。
- **PostgreSQL folds unquoted identifiers to lowercase**: `selectMaps` with `.select("kb_id AS kbId")` produces map key `kbid`, not `kbId`. Always use snake\_case aliases (`AS kb_id`, `AS doc_count`) and `row.get("kb_id")` — never camelCase.
- **Frontend HMR vs Backend restart**: Vite dev server hot-reloads frontend changes instantly. Spring Boot requires manual restart (`mvn -pl bootstrap spring-boot:run`) after any Java code change. Always confirm backend is restarted before verifying backend changes.
- **`mvn spring-boot:run`** **does NOT recompile stale classes after branch switch**: After `git checkout`, old `.class` files in `target/` remain. Run `mvn clean -pl bootstrap spring-boot:run` on first run in a new branch or new machine to force full recompilation with `-parameters`.
- **Cross-module source changes require** **`mvn install`**: Editing `framework` or `infra-ai` source then running `mvn -pl bootstrap spring-boot:run` fails — bootstrap resolves these modules from the local Maven repo, not from source. Run `mvn clean install -DskipTests` from root first. This is distinct from the branch-switch stale-class issue.
- **Entity column additions require DB migration before startup**: Adding fields to MyBatis Plus `@TableName` entities without running the corresponding `upgrade_*.sql` script causes `PSQLException: column does not exist` at runtime. Always pair entity field additions with a migration script in `resources/database/` and remind to execute it.
- **`-parameters`** **flag: IntelliJ vs Maven divergence**: IntelliJ adds `-parameters` automatically; Maven doesn't (compiler-plugin `<parameters>` isn't set). Without it, `@RequestParam`/`@PathVariable` without explicit `value=` throw `IllegalArgumentException` at runtime. **Rule**: always write explicit `value="..."` on all `@RequestParam`/`@PathVariable` annotations.
- **Sweep for bare annotations** (run after adding any controller): `grep -rEn '@(RequestParam|PathVariable)\s+(required\s*=[^,)]+,\s*)?[A-Z]' --include="*Controller.java" bootstrap/src/main/java | grep -v 'value\s*='`
- **`@RequiredArgsConstructor`** **+** **`@Qualifier`** **is SAFE**: `lombok.config` has `copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier`, so field-level `@Qualifier("beanName")` IS copied to the Lombok-generated constructor parameter. Explicit constructors are NOT required for ambiguous bean types (supersedes previous guidance).
- **Spotless runs on every** **`mvn compile`**, not just `mvn spotless:apply`: the `default` execution is wired to apply mode. A routine `mvn -pl bootstrap clean compile` can silently reformat unrelated files (e.g., collapsing an explicit constructor into `@RequiredArgsConstructor`). Always `git status` after compile and commit reformats separately.
- **pgvector extension not installed on** **`postgres:16`** **image**: `schema_pg.sql` contains `embedding vector(1536)` for `t_knowledge_vector`. `CREATE EXTENSION vector` + subsequent CREATE TABLE will error during init — **expected and safe** when `rag.vector.type=opensearch` (or `milvus`). The table simply won't exist; all other tables create successfully.
- **API signature changes require full-text search**: When backend adds/changes required parameters (e.g., adding `@RequestParam String kbId`), grep ALL frontend callers — not just the ones listed in the plan. Missing callers cause runtime 400 errors.
- **Sa-Token auth header is raw token, no Bearer prefix**: `Authorization: <token>` (NOT `Authorization: Bearer <token>`). See `application.yaml` `sa-token.token-name: Authorization` and `api.ts:15`. All permission rejections (NotRoleException, ClientException) return **HTTP 200** with `code != "0"` in the `Result` body — NOT HTTP 403/409. Assert on `code` field, never on HTTP status code.
- **Table naming convention is inconsistent**: Most tables use `t_` prefix (`t_user`, `t_role`, `t_knowledge_base`), but the department table is `sys_dept` (with entity `SysDeptDO`, mapper `SysDeptMapper`). When searching for department-related code, grep for `sys_dept` / `SysDept`, NOT `t_department` / `Dept`.
- **Seed data is not blank**: `init_data_pg.sql` wires admin user with `dept_id='1'` (GLOBAL), role `超级管理员` (`role_type=SUPER_ADMIN`, `max_security_level=3`), and `t_user_role` linking them. A fresh DB with `schema_pg.sql + init_data_pg.sql` already has a fully-privileged admin — not a "no dept / no role / max=0" user.
- **security\_level filter only implemented in OpenSearch**: `MilvusRetrieverService` and `PgRetrieverService` accept `metadataFilters` parameter but silently ignore it. Switching `rag.vector.type` to `milvus` or `pg` disables document security\_level enforcement at retrieval time. Fix these implementations before using non-OpenSearch backends in production.
- **Every controller needs explicit authorization**: `SaInterceptor` only enforces `StpUtil.checkLogin()` (login check), NOT role checks. New controllers must add their own `@SaCheckRole` or programmatic `kbAccessService` checks. `DashboardController` was audited and fixed for this in PR3.
- **Per-KB security\_level filtering**: `t_role_kb_relation.max_security_level` (SMALLINT, 0-3) controls per-KB retrieval filtering. `KbAccessService.getMaxSecurityLevelForKb(userId, kbId)` resolves it (SUPER\_ADMIN=3, DEPT\_ADMIN same-dept=role ceiling, others=MAX from relation). Cached in Redis Hash `kb_security_level:{userId}`, evicted alongside `kb_access:` cache.
- **KB-centric sharing API**: `GET/PUT /knowledge-base/{kb-id}/role-bindings` (note: hyphenated `kb-id` in path, not `kbId`). SUPER\_ADMIN any KB, DEPT\_ADMIN own-dept only. Uses `checkKbRoleBindingAccess()`.
- **DEPT\_ADMIN implicit MANAGE on same-dept KBs**: `checkManageAccess()` and `checkAccess()` both pass for `kb.dept_id == self.dept_id` without needing `role_kb_relation` entries. Cross-dept access requires explicit binding.
- **Frontend permission-gated components must handle backend rejection**: Components rendered for `isAnyAdmin` that call DEPT\_ADMIN-restricted endpoints should catch errors and hide gracefully (e.g., `KbSharingTab` sets `noAccess=true` and returns `null`), not show error toasts. The backend is the authorization boundary; the frontend optimistically renders and fails gracefully.
- **`curl` to OpenSearch (localhost:9201) requires `NO_PROXY`**: bash `NO_PROXY=localhost,127.0.0.1 curl ...`, or PowerShell `$env:NO_PROXY='localhost,127.0.0.1'`. Without it, curl routes through local HTTP proxy and returns 503. Same rule applies to any localhost infra calls (RustFS, PG via non-docker, Redis web UIs).
- **`@TableField(typeHandler = ...)`** **only fires in entity-based CRUD** (`insert` / `updateById` / `selectById`). `LambdaUpdateWrapper.set(col, val)` binds via default JDBC type mapping (`String → VARCHAR`) regardless of annotation — for jsonb columns (e.g. `t_knowledge_document.chunk_config`) this raises `column X is of type jsonb but expression is of type character varying`. Fix: use `updateById(entity)` for non-null values; secondary `LambdaUpdateWrapper.set(col, null)` for NULL clearing (NULL bindings don't trigger jsonb type mismatch). See `KnowledgeDocumentServiceImpl.update` for the hybrid pattern.
- **Java 17 baseline — no pattern matching for switch**: `switch (x) { case Foo(var y) -> ... }` (record pattern, JEP 440) and `switch (x) { case String s -> ... }` (type pattern, JEP 441) are Java 21 finalized, NOT enabled here. Use `if (x instanceof Foo y)` (JEP 394, Java 16+) instead. Sweep: `grep -rEn 'case\s+\w+\.\w+\s*\(' bootstrap/src/main/java` should be zero.
- **OpenSearch delete/update ops must be idempotent for missing index**: Manual `curl -X DELETE /<collection>` is a valid ops path (schema rebuilds, dev cleanup). Any `VectorStoreService` method hitting OS must catch `index_not_found_exception` and treat as no-op — see `OpenSearchVectorStoreService.isIndexNotFound`. Don't assume the index exists just because a KB record exists in PG.
- **CHUNK-mode write path needs explicit `ensureVectorSpace`**: Unlike PIPELINE mode (routed through `IndexerNode.execute`), `KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically` doesn't auto-ensure the index. If index is dropped externally, OpenSearch auto-create applies **dynamic mapping** (e.g. `kb_id` becomes `text` instead of declared `keyword`), breaking downstream term queries. Any new vector-write entry point must inject `VectorStoreAdmin` and call `ensureVectorSpace` before the DB transaction.
- **`AuthzPostProcessor dropped N chunks` ERROR is a production alert**: Normal path = 0 drops (retriever already filtered). Non-zero drops mean (a) retriever filter broken / stale cache, (b) index schema drifted (missing `kb_id`), or (c) non-OpenSearch backend active. Investigate — don't suppress.
- **RBAC changes that add new metadata-field filters require OS index rebuild**: AuthzPostProcessor fail-closes chunks missing the new field. Ship order is: (1) write path populates the field, (2) deploy, (3) `curl -X DELETE` each collection + re-run ingestion for all docs, (4) enable the reader-side check. Skipping step 3 makes authenticated sessions return empty answers.
- **`MessageQueueProducer.send(topic, ...)` takes the raw string** — only `@Value` and `@RocketMQMessageListener` resolve `${...}` placeholders. Passing a literal like `"foo_topic${unique-name:}"` to `send()` silently routes to a non-existent topic while the consumer subscribes to the resolved one — no error, messages lost. Always inject the topic via `@Value("foo_topic${unique-name:}")` into a field, then pass the field. See `chunkTopic` / `feedbackTopic` / `securityLevelRefreshTopic` fields in `KnowledgeDocumentServiceImpl` and `MessageFeedbackServiceImpl` for the canonical pattern.
- **DO→VO via `BeanUtil.toBean` is reflection by field name**: `KnowledgeDocumentServiceImpl.get/page` (and similar read endpoints) use `BeanUtil.toBean(do, Vo.class)`. Adding a new field on the DO is NOT enough — the matching field must exist on the VO or the frontend gets `undefined`. Always sweep the VO classes when adding a column.
- **`BeanUtil.toBean` can't populate cross-table fields like `deptName`**: `deptId` copies fine (same column on the DO); `deptName` lives in `sys_dept` so it's always `null` after `toBean`. Any VO exposing `deptName` (`KnowledgeBaseVO` / `KbRoleBindingVO` / `AccessRoleVO` / `RoleUsageVO`) must enrich in service layer: `sysDeptMapper.selectBatchIds(deptIds).forEach(d -> map.put(d.getId(), d.getDeptName()))` then `vo.setDeptName(map.get(do.getDeptId()))`. Silent failure shows as `部门：—` in UI.
- **Restart Spring Boot on port 9090 (Windows)**: `powershell "Get-NetTCPConnection -LocalPort 9090 -State Listen | % { Stop-Process -Id \$_.OwningProcess -Force }"` then relaunch. Needed after every Java change.
- **Unmapped Spring route = HTTP 200 + `{"code":"B000001","message":"系统执行出错"}`**, not 404. When verifying an endpoint was deleted/renamed via `curl`, this body is the "route is gone" signal — easy to mistake for a working endpoint returning empty. Distinct from Sa-Token/ClientException codes (also HTTP 200 but with semantic codes).
- **Deleting an admin page is a 5-file cascade**: page component + `router.tsx` import/route + `pages/admin/AdminLayout.tsx` sidebar item (+ now-unused `lucide-react` icon imports) + `utils/permissions.ts` `AdminMenuId` union + `DEPT_VISIBLE` array. Missing any one leaves dead sidebar links or orphan TS types.
- **Migration filename rule**: Pure DDL migrations (`ALTER TABLE`, `ADD COLUMN`, `SET NOT NULL`) keep generic `upgrade_vX_to_vY.sql`. Any migration with data-dependent `UPDATE` (id mappings, snowflake-keyed backfills, role→dept assignments) MUST carry `.<env>.sql` suffix (`upgrade_vX_to_vY.local-dev.sql`). Generic filename + env-specific UPDATEs silently matches zero rows on other envs — no error until the follow-up `SET NOT NULL` migration pre-check fails.

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
│   ├── KbAccessService           ← @Deprecated 上帝接口（2026-04-18 RBAC 重构后保留用于调用点分批迁移）
│   │                               新代码直接注入 framework/security/port/ 的 7 个 port
│   │                               （CurrentUserProbe / KbReadAccessPort / KbManageAccessPort / ...）
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
  新代码（2026-04-18 后）：注入 framework/security/port/ 下的细粒度端口
    CurrentUserProbe.isSuperAdmin() / isDeptAdmin()
    KbReadAccessPort.getAccessibleKbIds(userId) / getMaxSecurityLevelForKb(userId, kbId)
    KbManageAccessPort.checkManageAccess(kbId)
  旧代码：KbAccessService 上帝接口（@Deprecated），47 个调用点分批迁移中
  底层逻辑：查 t_user_role → 查 t_role_kb_relation → 返回可访问 kbId 集合 / 安全等级
             SUPER_ADMIN 跳过校验；DEPT_ADMIN 同部门 KB 跳过；Redis 缓存

生效点:
  - RAG 检索时: RetrievalEngine 过滤 accessibleKbIds
  - 知识库列表: KnowledgeBaseController 过滤可见 KB
  - 文档操作: KnowledgeDocumentController 校验文档所属 KB 的访问权限
```

