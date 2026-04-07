# Ragent AI

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Java](https://img.shields.io/badge/Java-17-ff7f2a.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.x-6db33f.svg)
![React](https://img.shields.io/badge/React-18-61dafb.svg)

> 企业级 Agentic RAG 智能体平台，基于 Java 17 + Spring Boot 3 + React 18 构建，覆盖从文档入库到智能问答的全链路。

![](assets/qa-home.png)

## 核心能力

- **多路检索引擎**：意图定向检索 + 全局向量检索并行执行，结果经去重、重排序后处理，兼顾精准度与召回率。
- **意图识别与引导**：树形多级意图分类（领域→类目→话题），置信度不足时主动引导澄清，而非硬猜答案。
- **问题重写与拆分**：多轮对话自动补全上下文，复杂问题拆为子问题分别检索。
- **会话记忆管理**：保留近 N 轮对话，超限自动摘要压缩，控 Token 成本不丢上下文。
- **模型路由与容错**：多模型优先级调度、首包探测、健康检查、三态熔断、自动降级。
- **MCP 工具集成**：意图非知识检索时自动提参调用业务工具，检索与工具调用无缝融合。
- **文档入库 ETL**：节点编排 Pipeline（抓取→解析→增强→分块→向量化→写入），灵活配置可扩展。
- **RBAC 权限控制**：基于角色的知识库访问控制，不同角色看到和检索的知识库不同。
- **全链路追踪**：重写、意图、检索、生成每个环节均有 Trace 记录，排查与调优有据可依。
- **管理后台**：React 管理界面，覆盖知识库管理、意图树编辑、入库监控、链路追踪、角色管理、系统设置。

## 技术架构

### 系统总览

![](assets/system-architecture.png)

### 模块分层

Ragent 采用前后端分离架构，后端按职责分为四个 Maven 模块：

<img src="assets/ragent-module-layering.png" width="50%" />

- **bootstrap** — 主 Spring Boot 应用，按业务域组织：`rag/`（RAG 核心）、`ingestion/`（文档入库）、`knowledge/`（知识库管理）、`core/`（解析/分块）、`user/`（认证/RBAC）、`admin/`（仪表盘）。
- **framework** — 跨域基础设施：异常体系、幂等控制、分布式 ID、用户/Trace 上下文透传、SSE 封装、统一响应体。
- **infra-ai** — AI 基础设施：LLM 客户端抽象、模型路由、Embedding 服务、Rerank 服务。
- **mcp-server** — MCP（Model Context Protocol）服务端实现。

### 技术栈

| 层面 | 技术选型 |
|------|---------|
| 后端框架 | Java 17、Spring Boot 3.5.7、MyBatis Plus 3.5.14 |
| 前端框架 | React 18、Vite、TypeScript、TailwindCSS |
| 关系数据库 | PostgreSQL |
| 向量数据库 | OpenSearch 2.18（混合搜索）/ Milvus 2.6 / pgvector（三选一，配置切换） |
| 缓存 | Redis + Redisson |
| 对象存储 | S3 兼容存储（RustFS） |
| 消息队列 | RocketMQ 5.x |
| 文档解析 | Apache Tika 3.2 |
| 模型供应商 | 百炼（阿里云）、SiliconFlow、Ollama（本地） |
| 认证鉴权 | Sa-Token 1.43 |
| 代码规范 | Spotless（自动格式化，CI 强制检查） |

## 核心设计

### RAG 问答链路

一次用户提问的完整链路：

![](assets/ragent-chain.png)

RAG 对话处理时序：

![](assets/rag-sequence-diagram.png)

```
限流入队 → RBAC 权限校验 → 加载会话记忆
→ 查询改写 + 子问题拆分 → 意图识别（并行）
→ 歧义引导（短路）/ System-Only 短路
→ 多通道检索（KB 向量检索 + MCP 工具调用，并行）
→ 去重 → 重排序 → 上下文组装 → LLM 流式生成（SSE）
→ 回答持久化 + 评估采集 + 链路追踪结束
```

### 多路检索架构

![](assets/multi-channel-retrieval.png)

检索引擎采用多通道并行 + 后处理流水线架构。每个通道独立执行、互不影响，通过线程池并行调度。后处理器按顺序串联，逐步精炼检索结果。

OpenSearch 模式下支持混合搜索（KNN 向量 + 全文检索），权重可配。

### 模型路由与容错

![](assets/model-routing-failover.png)

多模型优先级调度，三态熔断器（CLOSED → OPEN → HALF_OPEN）独立维护每个模型的健康状态。首包探测阶段缓冲所有事件，确保模型切换时用户端不会收到半截的脏数据。

### 文档入库流水线

<img src="assets/ingestion-pipeline.png" width="25%" />

基于节点编排的 Pipeline，支持两种入库模式：
- **CHUNK 模式**：Extract → Chunk → Embed → Persist，轻量直接。
- **PIPELINE 模式**：Fetcher → Parser → Enhancer → Chunker → Enricher → Indexer，支持 LLM 增强和条件执行。

文档上传后通过 RocketMQ 事务消息异步处理，每个任务和节点都有独立的执行日志。

### 关键设计模式

| 设计模式 | 应用场景 | 解决的问题 |
|---------|---------|-----------|
| 策略模式 | SearchChannel、PostProcessor、MCPToolExecutor、VectorStoreService | 检索通道、后处理器、MCP 工具、向量库可插拔替换 |
| 工厂模式 | IntentTreeFactory、StreamCallbackFactory、ChunkingStrategyFactory | 复杂对象的创建逻辑集中管理 |
| 注册表模式 | MCPToolRegistry、IntentNodeRegistry | 组件自动发现与注册 |
| 模板方法 | IngestionNode 基类 | 入库节点统一执行流程 |
| 装饰器模式 | ProbeBufferingCallback | 首包探测能力 |
| 责任链模式 | 后处理器链、模型降级链 | 多个处理步骤按顺序串联 |
| AOP | @RagTraceNode、@ChatRateLimit | 链路追踪和限流与业务代码解耦 |

## 可扩展性

核心模块均预留扩展点，新增实现类注册为 Spring Bean 即自动生效：

- **检索通道**：实现 `SearchChannel` 接口
- **后处理器**：实现 `SearchResultPostProcessor` 接口
- **MCP 工具**：实现 `MCPToolExecutor` 接口
- **入库节点**：实现 `IngestionNode` 接口
- **向量数据库**：实现 `VectorStoreService` + `VectorStoreAdmin` 接口
- **模型供应商**：在 infra-ai 层实现 `ChatClient` 接口

## 控制台

### 用户问答

支持自然语言输入、深度思考模式、示例问题快速填充、Markdown 渲染、代码高亮、回答评价。

![](assets/qa-home.png)

![](assets/qa-answer.png)

### 管理后台

覆盖仪表盘、知识库管理、意图树配置、入库监控、链路追踪、用户管理、角色管理、系统设置。

![](assets/admin-overview.png)

![](assets/admin-knowledge-base.png)

![](assets/admin-trace.png)

## 快速启动

```bash
# 构建（跳过测试）
mvn clean install -DskipTests

# 启动后端（端口 9090，上下文路径 /api/ragent）
mvn -pl bootstrap spring-boot:run

# 启动前端
cd frontend && npm install && npm run dev
```

基础设施依赖：PostgreSQL、Redis、RocketMQ、S3 兼容存储（RustFS）、向量数据库（OpenSearch / Milvus / pgvector 三选一）。

Docker Compose 文件在 `resources/docker/` 目录下。数据库初始化脚本在 `resources/database/` 目录下。

## License

[Apache License 2.0](LICENSE)
