# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Developer Docs — 渐进式披露（按需加载）

> **完整索引见 [`docs/dev/README.md`](docs/dev/README.md)**。下面仅列"改任何代码前都该扫一眼"的三条 + 模块级指引。其他设计/启动/验收/技术债等文档按需跳读。

**高频入口（常驻）**：

- **`docs/dev/entry-points.md`** — 场景化导航"我想做 X，从哪开始"。盲探代码前先来这里。
- **`docs/dev/gotchas.md`** — 历史坑点与修复规则（7 大主题分组）。改代码前按主题先查一遍，避免复犯。
- **`docs/dev/README.md`** — 整个开发者文档地图。找不到东西或要新加文档时先看这里的分类规则。

**按主题跳读**（编辑时再加载）：

- 架构 / 数据流 → `docs/dev/arch/`（含 code-map、business-flows 等）
- 设计文档 / 实施计划 → `docs/dev/design/`
- 启动 / 部署 → `docs/dev/setup/`
- 技术债 / 复盘 / 角色评审 → `docs/dev/followup/`
- 授权基线 → `docs/dev/security/`
- PR 验收 / demo → `docs/dev/verification/`

**模块级指引**：`bootstrap/CLAUDE.md` / `frontend/CLAUDE.md` / `framework/CLAUDE.md` / `infra-ai/CLAUDE.md` — 各自的"关键类"表 + 模块独有 gotcha。

**其他**：
- `log/dev_log/dev_log.md` — 按日期的 PR/开发日志索引
- `log/notes/` — 零散开发笔记（概念澄清 / 调试心路 / 小型 Q&A；见目录内 README 定位与不同于 dev_log、diagnostic 的边界）
- `AGENTS.md`（根）— agent contract

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
| Vector DB      | OpenSearch 2.18 / Milvus 2.6 / pgvector（三选一，`rag.vector.type` 切换） |
| ORM            | MyBatis Plus 3.5.14                                               |
| Cache          | Redis + Redisson                                                  |
| Message Queue  | RocketMQ 5.x                                                      |
| Object Storage | S3-compatible (RustFS)                                            |
| Doc Parsing    | Apache Tika 3.2                                                   |
| Auth           | Sa-Token 1.43                                                     |
| Code Format    | Spotless (enforced in build)                                      |
| Frontend       | React 18 + Vite + TypeScript + TailwindCSS                        |

> **当前生产只用 OpenSearch**（`application.yaml` `rag.vector.type=opensearch`）。Milvus / pgvector 后端的 `metadataFilters`（security_level 过滤）尚未实现 —— 切换前必须先补齐，见 `docs/dev/followup/backlog.md` SL-1。

## Configuration

Main config: `bootstrap/src/main/resources/application.yaml`

Key config sections: database, Redis, RocketMQ, Milvus, AI model providers (Ollama, BaiLian/Alibaba, SiliconFlow), RAG parameters (rewrite settings, search channels, confidence thresholds, memory limits).

## Infrastructure

Docker Compose files in `resources/docker/` for Milvus and RocketMQ. Database init scripts in `resources/database/`.

Full environment setup guide (Docker containers + DB init + backend/frontend start): `docs/dev/setup/launch.md`

Upstream open-source repo: `git remote upstream` → `https://github.com/nageoffer/ragent.git` (added 2026-04-15). Use `git fetch upstream` to compare.

Upgrade scripts in `resources/database/`:

- `upgrade_v1.2_to_v1.3.sql` — adds `kb_id` to `t_conversation` for knowledge space isolation
- `upgrade_v1.3_to_v1.4.sql` — adds `thinking_content`/`thinking_duration` to `t_message` for deep-thinking chain persistence
- `upgrade_v1.4_to_v1.5.sql` — 软删后复用 KB `collection_name`（`UNIQUE (collection_name, deleted)`）
- `upgrade_v1.5_to_v1.6.<env>.sql` — 给 `t_role` 加 `dept_id` 列并按环境回填（模板 `.template.sql` + 本地 `.local-dev.sql`；其他环境必须自建 `.<env>.sql`）
- `upgrade_v1.6_to_v1.7.sql` — `t_role.dept_id SET NOT NULL`（前置校验空行）
- `upgrade_v1.7_to_v1.8.sql` — 修 `t_knowledge_base.dept_id` 默认值 `'GLOBAL' → '1'`，并回填历史数据
- `upgrade_v1.8_to_v1.9.sql` — 为 `t_message` 增加 `sources_json` 列（Answer Sources PR4 持久化）
- `upgrade_v1.9_to_v1.10.sql` — 新增 eval 域 4 张表（`t_eval_gold_dataset / t_eval_gold_item / t_eval_run / t_eval_result`）
- `upgrade_v1.10_to_v1.11.sql` — 修 eval 黄金集软删唯一键为 partial unique index（仅约束 `deleted=0`）

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

项目历史坑点与修复规则（按主题分组，47+ 条）集中在 **`docs/dev/gotchas.md`**。动手前按主题检索相关分组（后端 / 数据库 / 安全 / 向量 / MQ / 前端 / 运维），避免复犯。修完新坑后按文件末尾"新增坑点指南"回填。

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

## Project Code Map & Core Business Flows

- **代码地图**（bootstrap 各域目录树与主要类）：**`docs/dev/arch/code-map.md`**
- **核心业务链路**（RAG 问答 / KB 管理 / 文档入库 / 后台统计 / RBAC）：**`docs/dev/arch/business-flows.md`**

模块级关键类表格请看对应 `{module}/CLAUDE.md`。

