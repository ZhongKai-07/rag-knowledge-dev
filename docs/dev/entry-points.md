# Entry Points — 按业务场景的代码导航

> 目的：**"想做 X，从哪儿改起？"** —— 按场景索引，不按文件树分类。
> 文件树看 [根 CLAUDE.md 的 Project Code Map](../../CLAUDE.md#project-code-map) 即可，本文不重复。
> 原则：场景比结构稳。改文件名/挪包不影响本表，换业务故事才更新。

---

## 🧩 扩展点 — 新增可插拔能力

### 加一个检索通道（e.g. 专门查 FAQ / 知识图谱）

- **契约**：实现 `rag/core/retrieve/channel/SearchChannel.java`
- **注册**：`@Component` 标注即自动被 `MultiChannelRetrievalEngine` 装载（构造器 `List<SearchChannel>` 注入）
- **参考现成**：`IntentDirectedSearchChannel` / `VectorGlobalSearchChannel`
- **调度方式**：`RetrievalEngine.retrieve()` 并行跑所有通道，结果交 `SearchResultPostProcessor` 链去重 + 重排
- **Gotcha**：通道需要自己从 `SearchContext` 读 `accessibleKbIds` 做 RBAC 过滤；`kbSecurityLevels` map 是预计算的，不要回调 `KbAccessService`

### 加一个后处理器（去重策略 / 重排模型 / 结果过滤）

- **契约**：`rag/core/retrieve/postprocessor/SearchResultPostProcessor`
- **顺序**：实现里看 `getOrder()`；按数字从小到大串行
- **参考**：`DeduplicationPostProcessor`、`RerankPostProcessor`

### 加一个 MCP 工具（e.g. 查天气、调 CRM）

- **契约**：`rag/core/mcp/MCPToolExecutor`
- **注册**：`@Component` + 在意图树里建一个 `NodeType=MCP` 节点，`identifier` 与工具名对齐
- **参数提取**：由 `MCPToolExecutor` 负责从用户原话里 LLM 抽参（有默认实现可继承）
- **调度点**：`RAGChatServiceImpl` 根据意图判定 MCP → 绕过向量检索直调工具

### 加一个入库节点（e.g. PII 脱敏、OCR）

- **契约**：`ingestion/node/IngestionNode` 接口 + 继承 `AbstractIngestionNode` 基类
- **注册**：在 `t_ingestion_pipeline_node` 里配 + 绑定 `nodeType`；`IngestionEngine` 按 pipeline 定义顺序调度
- **现成节点链**：Fetcher → Parser → (Enhancer) → Chunker → (Enricher) → Indexer
- **共享状态**：走 `IngestionContext`

### 加一个向量数据库实现

- **契约**：`rag/core/vector/VectorStoreService` + `VectorStoreAdmin`
- **激活**：`application.yaml` `rag.vector.type=<你的 type>`，再配对应 `@ConditionalOnProperty` 的 bean
- **现成**：`OpenSearchVectorStoreService` / `MilvusVectorStoreService` / `PgVectorStoreService`
- **⚠️ 坑**：`metadataFilters` 目前只有 OpenSearch 实现了真过滤，Milvus/Pg 静默忽略 → 切换前必须补（见 `follow-ups.md` SL-1）

### 加一个模型供应商（第四家 LLM）

- **契约**：`infra-ai` 模块的 `ChatClient` 接口
- **注册**：`application.yaml` `ai.chat.candidates` 加条目，配优先级 + 健康检查
- **失败降级**：`ModelRouter` 三态熔断自动切下一家，无需改业务代码

### 加一个文档来源（e.g. 飞书/Notion）

- **契约**：`ingestion/strategy/fetcher/FetcherStrategy`
- **现成**：`LocalFileFetcher` / `HttpUrlFetcher` / `S3Fetcher` / `FeishuFetcher`
- **`SourceType` 枚举**：别忘了同步加

### 加一个分块策略

- **契约**：`core/chunk/strategy/ChunkingStrategy`
- **现成**：`FixedSizeTextChunker` / `StructureAwareTextChunker`
- **选择**：`ChunkingStrategyFactory` 按 `KnowledgeDocumentDO.chunkingStrategy` 字段分发

---

## 🔀 业务链路 — 改已有行为

### 改 RAG 问答链路（记忆/改写/意图/检索/生成）

- **总编排**：`rag/service/impl/RAGChatServiceImpl.streamChat()`
- **阶段定位**：
  - 限流 / traceId：`rag/aop/ChatRateLimitAspect`
  - 记忆：`rag/core/memory/ConversationMemoryService`
  - 改写：`rag/core/rewrite/QueryRewriteService`
  - 意图：`rag/core/intent/IntentResolver` → `DefaultIntentClassifier`
  - 检索：`rag/core/retrieve/RetrievalEngine` → `MultiChannelRetrievalEngine`
  - 生成：`rag/service/handler/StreamChatEventHandler`
- **链路图**：`diagram/sequence/rag_sequence_diagram.drawio.png`

### 改文档入库行为

- **触发**：`POST /knowledge-base/docs/{docId}/chunk` → `KnowledgeDocumentController.startChunk`
- **事务消息**：RocketMQ 事务发送（`KnowledgeDocumentChunkConsumer` 消费）
- **执行**：`KnowledgeDocumentServiceImpl.executeChunk()`
  - CHUNK 模式：Extract → Chunk → Embed → Persist（简短）
  - PIPELINE 模式：走 `IngestionEngine` 节点链
- **状态：** `t_knowledge_document.status` + `t_knowledge_document_chunk_log`
- **图**：`diagram/sequence/kb_ingestion_sequence.drawio`

### 改权限（谁能看哪些 KB / 文档）

- **唯一真相源**：`user/service/KbAccessService`（接口）+ `KbAccessServiceImpl`
- **生效点**：
  - 检索前：`RAGChatServiceImpl` 先拿 `getAccessibleKbIds()` 塞进 `SearchContext`
  - 单 KB 操作：`kbAccessService.checkAccess(kbId)` / `checkManageAccess(kbId)`
  - 文档级：`checkDocManageAccess(docId)` / `checkDocSecurityLevelAccess(docId, level)`
  - 角色分配：`validateRoleAssignment(roleIds)`（DEPT_ADMIN 不可分 SUPER/DEPT_ADMIN + 不可超自身 ceiling）
  - Last-SUPER_ADMIN 守护：`simulateActiveSuperAdminCountAfter(intent)` pre-flight
- **用户身份缓存**：`UserProfileLoader` 单次 JOIN 加载（登录时，不走缓存）
- **Redis 缓存**：`kb_access:{userId}` (USER) / `kb_access:dept:{userId}` (DEPT_ADMIN) / `kb_security_level:{userId}`

### 改知识库空间隔离（URL / 会话锁 / 切空间清理）

- **URL 作唯一真相源**：`?kbId=xxx` —— 前端各处从 URL 读，`chatStore.activeKbId` 只是缓存
- **切空间重置**：`chatStore.resetForNewSpace()`
- **会话所有权**：`ConversationService.validateKbOwnership(convId, userId, kbId)` —— 所有 messages/rename/delete 入口都调
- **入口页**：`frontend/src/pages/SpacesPage.tsx` + 后端 `SpacesController.getSpacesStats()`

### 改前端权限显示（菜单/按钮可见性）

- **唯一真相源**：`frontend/src/utils/permissions.ts` (`getPermissions(user)` 纯函数 + `usePermissions()` hook)
- **不要**在组件里内联 `user.isSuperAdmin` 判断
- **路由守卫**：`frontend/src/router/guards.tsx`（`RequireAnyAdmin` / `RequireMenuAccess("roles")`）
- **菜单过滤**：`AdminLayout.tsx` 调 `permissions.canSeeMenuItem(id)`；DEPT_VISIBLE 数组在 `permissions.ts`

### 新增管理后台页面

1. 组件放 `frontend/src/pages/admin/{feature}/XxxPage.tsx`
2. 路由在 `frontend/src/router.tsx` 注册（可能要包 `<RequireMenuAccess>`）
3. 侧边栏在 `frontend/src/components/layout/Sidebar.tsx` 或 `pages/admin/AdminLayout.tsx` 加菜单项
4. `permissions.ts` 里加菜单 ID（若 DEPT_ADMIN 也能看，需加进 `DEPT_VISIBLE`）
5. API service 放 `frontend/src/services/{feature}Service.ts`

---

## 🔍 调试场景

### traceId 从哪里进、到哪里出？

- **生成**：`rag/aop/ChatRateLimitAspect`（进入聊天接口时）
- **存放**：`RagTraceContext` (ThreadLocal，TTL)
- **记录**：`@RagTraceNode` 注解 → `rag/aop/RagTraceAspect`
- **⚠️ 坑**：`ChatRateLimitAspect.finally` 在流结束**前**就清了 context。回调里要 **构造时捕获** traceId，别等回调时再读
- **持久化**：`t_rag_trace_run` + `t_rag_trace_node`（`extra_data` JSON 字段扩展 token 用量等）
- **查询**：`GET /rag/traces/runs` (分页) / `/rag/traces/runs/{traceId}` (详情含节点)

### SSE 响应问题

- **入口**：`RAGChatController.chat()` 返回 `SseEmitter`（不要塞进 `Result<>`）
- **推送源头**：`rag/service/handler/StreamChatEventHandler`（在 `modelStreamExecutor` 线程池）
- **⚠️ 坑 1**：`streamChat` 立即返回；StreamCallback 在**另一个线程**跑，调用方读不到回调里设的 ThreadLocal
- **⚠️ 坑 2**：OpenAI SSE frame 顺序 `finish_reason → usage → [DONE]`。循环退出要看 `streamEnded` (`[DONE]`) 而非 `finished` (`finish_reason`)，否则吞 usage
- **usage 回填**：`onComplete` 拿到 token 数后回写 `RagTraceContext`

### 权限拒绝为什么不是 403？

- **全部返回 HTTP 200** + `Result.code != "0"`
- `NotRoleException` / `ClientException` 由 `framework/web/GlobalExceptionHandler` 翻译成 `Result`
- **前端**：断言 `code` 字段，别看 HTTP status

### 链路某一步没跑到

- `RAGChatServiceImpl` 里有 3 个短路点：
  1. `checkLogin` 失败 → Sa-Token 拦截（401 / `NotLoginException`）
  2. `accessibleKbIds.isEmpty()` 或歧义引导触发 → 短路返回引导语
  3. 检索结果为空 → 短路返回"未检索到"
- 都会写 trace，看 `t_rag_trace_run.status` 能分辨

---

## ⚙️ 配置入口

### 后端主配置

- `bootstrap/src/main/resources/application.yaml`
- **关键节**：
  - `rag.vector.type` —— 向量库切换（opensearch / milvus / pg）
  - `rag.query-rewrite` —— 改写开关 + 历史轮数
  - `rag.rate-limit` —— 全局并发上限
  - `rag.memory` —— 记忆轮数、摘要开关、TTL
  - `rag.search.channels` —— 各通道置信度阈值
  - `ai.chat.candidates` / `ai.embedding.candidates` —— 模型候选列表 + 优先级
  - `ai.rerank` —— 重排模型
  - `opensearch` / `rustfs` / `sa-token` —— 中间件连接

### 前端配置

- `frontend/.env` —— `VITE_APP_NAME`、API base URL
- `frontend/vite.config.ts` —— 开发代理（5173 → 9090）

### RBAC 种子数据

- `resources/database/init_data_pg.sql` —— GLOBAL dept + admin 用户 + SUPER_ADMIN 角色 + user_role 链
- `resources/database/fixture_pr3_demo.sql` —— alice/bob/carol + 研发/法务 KB（**仅** Mode B curl 矩阵用）

### Schema 迁移

- **改表**：同时改 `schema_pg.sql`（干净 DDL） + `full_schema_pg.sql`（pg_dump 格式），两份都要动
- **增量**：加一个 `upgrade_vX.Y_to_vX.Z.sql`
- **在线库**：ALTER 语句 + 如要加 COMMENT 需单独写 COMMENT 语句（`full_schema_pg.sql` 风格）

---

## 📐 设计文档

- **数据库 ERD**：`diagram/erd/*.drawio`（数个版本，看文件名时间戳）
- **RAG 时序**：`diagram/sequence/rag_sequence_diagram.drawio.png`
- **入库时序**：`diagram/sequence/kb_ingestion_sequence.drawio`
- **系统架构**：`diagram/architecture/系统架构.drawio`
- **RBAC 设计**：`docs/dev/design/rag-permission-design.md`、`rbac-and-security-level-implementation.md`
- **权限审计基线**：`docs/dev/security/2026-04-18-authorization-baseline.md` — 端点级矩阵，RBAC/ACL 重构前后对比依据
- **PR 规划 / 验收日志**：`docs/superpowers/plans/*.md`、`docs/dev/pr3-*`

---

## 🧭 不在这里找什么

- **"这个 class 在哪个包"** → 根 `CLAUDE.md` 的 Project Code Map
- **"一个 package 里都有啥"** → `bootstrap/CLAUDE.md` / `frontend/CLAUDE.md` 各自的"关键类"表
- **"项目怎么跑起来"** → `README.md` + `docs/dev/launch.md`
- **"踩过的坑"** → 各层 `CLAUDE.md` 的 Key Gotchas
- **"过去几轮干了啥"** → `log/dev_log/dev_log.md` + `log/dev_log/YYYY-MM-DD-*.md`
- **"待办"** → `docs/dev/follow-ups.md`
