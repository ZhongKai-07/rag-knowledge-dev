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
- **user/** — Authentication (Sa-Token), user management.

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
| Vector DB | Milvus 2.6 (also supports pgvector) |
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

## Language

Project documentation and comments are in Chinese. Code identifiers are in English.

## Key Gotchas

- **SSE streaming is async**: `ChatClient.streamChat()` returns immediately via `StreamAsyncExecutor`; `StreamCallback` methods run on `modelStreamExecutor` thread pool. Do NOT read ThreadLocal values set by streaming callbacks from the calling thread — they won't be there yet.
- **OpenAI SSE usage frame order**: `finish_reason` frame → usage frame (empty choices) → `[DONE]`. Loop must break on `[DONE]` (`streamEnded`), not on `finish_reason` (`finished`), otherwise usage data is missed.
- **RagTraceContext ThreadLocal cleared early**: `ChatRateLimitAspect.finally` clears context before streaming completes. Capture `traceId` etc. in handler constructor, not at callback time.
- **`extra_data TEXT` JSON pattern**: Used in `t_rag_trace_run` and `t_rag_trace_node` for extensible metrics (token usage, question length) without schema migration. Parse with Gson in query service.

## RAG Evaluation (RAGAS)

- `t_rag_evaluation_record` stores query-chunk-answer triples for evaluation
- `EvaluationCollector` gathers data via `RagTraceContext` ThreadLocal across the RAG pipeline
- Export endpoint: `GET /rag/evaluations/export` produces RAGAS-compatible JSON
- RAGAS evaluation script: `ragas/ragas/run_eval.py` (uses 百炼 API as evaluator LLM)

## Frontend Patterns

- Admin pages: create component in `frontend/src/pages/admin/{feature}/`, register route in `router.tsx`, add sidebar item + breadcrumb in `AdminLayout.tsx`
- UI components: shadcn/ui in `frontend/src/components/ui/`; icons from `lucide-react`
- API services: `frontend/src/services/` using axios instance from `api.ts`; response wrapper `{ code, data, message }`
