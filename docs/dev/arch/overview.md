# 系统架构总览

> 面向架构评审的四层分层架构概览。各层细节见同目录的 `frontend.md` / `bootstrap.md` / `infra-ai.md` / `infrastructure.md`。
>
> 架构图：[`diagram/architecture/arch_overview.drawio`](../../../diagram/architecture/arch_overview.drawio)

## 1. 分层概念

系统沿用「表现层 → 业务编排层 → 基础能力层 → 资源层」的经典四段式，在"基础能力层"里把**通用基础设施**（framework）与 **AI 能力抽象**（infra-ai）拆开，避免 AI 专用代码污染公共工具包。

| 层 | Maven 模块 | 职责 | 允许依赖 |
| --- | --- | --- | --- |
| Frontend | `frontend/` (独立 Vite 工程) | 用户交互、会话状态、权限过滤、SSE 消费 | 浏览器 API · Bootstrap REST |
| Bootstrap | `bootstrap/` | 业务域编排：RAG 问答、文档入库、知识库管理、RBAC、仪表盘 | framework · infra-ai · mcp-server |
| framework | `framework/` | 跨切面的非 AI 基础设施：Result/异常/UserContext/TTL 追踪/幂等/ID | 仅 Spring 生态 |
| infra-ai | `infra-ai/` | AI 能力抽象：LLM/Embedding/Rerank 统一接口 + 路由降级 | framework |
| Infrastructure | 容器化部署 | PostgreSQL/Redis/RocketMQ/向量库/RustFS/外部 LLM | 外部服务 |

mcp-server 是一个独立 MCP 协议服务进程，Bootstrap 通过 MCP 协议调用工具，归在 infrastructure 的"外部服务"一侧，不参与本次四层拆解。

## 2. 依赖方向（单向）

```
Frontend ──HTTPS/SSE──► Bootstrap
                           │
                           ├──► framework (Maven dep)
                           └──► infra-ai  (Maven dep)
                                     │
                                     └──► framework (Maven dep)
                           ▼
Bootstrap / framework / infra-ai ──► Infrastructure（PG / Redis / MQ / OpenSearch / RustFS / 外部 LLM）
```

- **framework 不依赖 bootstrap**，也不依赖 infra-ai。引入任何业务类到 framework 即视为违规。
- **infra-ai 只依赖 framework** 用于拿到 `Result`、异常、`ChatRequest/ChatMessage` 等通用 DTO，不感知业务实体。
- **Bootstrap 是唯一面向前端的入口**，mcp-server 不直接暴露给浏览器。

## 3. 端到端核心链路（问答 SSE）

```
Browser  ──GET /api/ragent/rag/v3/chat──►  RAGChatController
            │                                    │
            │                               ChatRateLimitAspect (生成 traceId，写 RagTraceContext)
            │                                    │
            │                               RAGChatServiceImpl
            │                                    │
            │                               记忆 → 改写 → 意图 → 检索 → Prompt → LLMService.streamChat()
            │                                    │                                      │
            │                               MultiChannelRetrievalEngine                RoutingLLMService
            │                                    │                                      │
            │                          OpenSearch / Milvus / pgvector              BaiLian / Ollama / SiliconFlow
            │                                                                            │
            │◄───────────── SSE 数据帧 (content / thinking / usage / [DONE]) ────────────┘
```

关键跨层约束：
- **SSE 回调运行在 `modelStreamExecutor` 独立线程**，`RagTraceContext` 必须用 `TransmittableThreadLocal`（`framework.trace`）才能跨线程传递。
- **Sa-Token 拦截器在请求入口线程设置 `UserContext`**；任何进入异步线程的代码（MQ 消费者、`@Async`）必须在入口处捕获 `userId` 作为参数，不能从子线程 `UserContext.getUserId()`。
- **`Result` 是 API 边界的统一响应**：即使是权限拒绝（`NotRoleException`），HTTP 状态码仍然是 200，`Result.code != "0"` 才表达失败。前端断言必须看 `code` 字段。

## 4. 业务域与层的映射

| 业务域（bootstrap 下） | 主要跨层依赖 |
| --- | --- |
| `rag/`（问答） | infra-ai (LLMService/Embedding/Rerank) · 向量库 · PG (memory/trace) · Redis (限流/缓存) |
| `ingestion/`（入库流水线） | infra-ai (LLM 增强/Embedding) · RustFS (fetch) · 向量库 (index) · RocketMQ |
| `knowledge/`（KB/文档管理） | RocketMQ (分块事务消息) · PG · RustFS |
| `user/`（RBAC） | PG · Redis (kb_access 缓存) · Sa-Token |
| `admin/`（仪表盘） | PG（只读跨域聚合） |
| `core/`（解析/分块） | Apache Tika 内嵌（非独立服务） |

## 5. 四种不变量（Architecture Invariants）

评审重点关注：

1. **依赖单向** — framework/infra-ai 不能反向依赖 bootstrap；任何 AI 逻辑不应出现在 framework。
2. **业务域边界** — 每个域内部保持 `controller → service → dao`。跨域只读聚合归在 `admin/`，不允许一个业务 controller 直接查别域的 DAO。
3. **向量库可切换** — 通过 `rag.vector.type` 配置，OpenSearch / Milvus / pgvector 三实现走同一个 `VectorStoreService` 接口。切换不应改业务代码。⚠️ 当前只有 OpenSearch 实现了 `metadataFilters`（security_level 过滤），见 `../followup/backlog.md`。
4. **外部 LLM 可替换** — 新增第 4 家供应商只改 `infra-ai`（加一个 `ChatClient` 实现 + `application.yaml` 配候选）；业务不感知。

## 6. 跨层约定（高频踩坑汇总）

这些约束反复出现在多个层，单独列出避免漏看：

- **`extra_data TEXT` JSON 字段**：查询用 Gson（自动数字强转），**写入必须用 Jackson**（否则整数被序列化成 `5228.0` 触发 SQL CAST 失败）。
- **`@Data @Builder` + Jackson 不兼容**：任何走 Jackson 反序列化的类（Redis 缓存、`@RequestBody`、MQ Event）必须显式 `@NoArgsConstructor @AllArgsConstructor`。
- **`@RequestParam`/`@PathVariable` 必须写 `value=`**：IntelliJ 有 `-parameters` 自动加，Maven 命令行没有，依赖参数名推断的代码在 CI/生产会抛 `IllegalArgumentException`。
- **`@TableLogic` 自动追加 `WHERE deleted=0`**：不要再手动 `.eq(::getDeleted, 0)`。
- **两份 Schema 文件**：`schema_pg.sql`（干净 DDL）和 `full_schema_pg.sql`（pg_dump 风格）必须同步维护。

## 7. 文档导航

| 关注点 | 文档 |
| --- | --- |
| 前端组件/路由/Zustand/权限展示 | [`frontend.md`](frontend.md) |
| 业务域编排、问答链路、MQ 驱动入库 | [`bootstrap.md`](bootstrap.md) |
| LLM/Embedding/Rerank 抽象、模型路由降级 | [`infra-ai.md`](infra-ai.md) |
| PG/Redis/MQ/向量库/RustFS 拓扑与使用模式 | [`infrastructure.md`](infrastructure.md) |
| 场景式代码导航（"想做 X，从哪儿改"） | [`../entry-points.md`](../entry-points.md) |
| 已知技术债/决策 | [`../followup/backlog.md`](../followup/backlog.md) |
