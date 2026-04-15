# infra-ai 模块

infra-ai 是 AI 基础设施层，为上层业务（bootstrap）提供统一的大语言模型（LLM）、向量嵌入（Embedding）和重排序（Rerank）能力。**核心职责是屏蔽多模型提供商差异，实现智能路由和故障降级。**

## 构建

```bash
mvn -pl infra-ai install -DskipTests
```

## 包结构

```
com.nageoffer.ai.ragent.infra/
├── chat/        ← LLM 聊天（同步 + 流式）
├── embedding/   ← 文本向量化
├── rerank/      ← 检索结果重排
├── model/       ← 模型选择、路由、健康检测
├── config/      ← AI 模型配置属性类
├── enums/       ← 模型提供商、能力枚举
├── http/        ← HTTP 工具、媒体类型、异常
├── token/       ← Token 计数
└── util/        ← LLM 响应清洗
```

## 三层服务架构

每种 AI 能力都分三层：

```
Service 接口（业务调用入口）
    └── RoutingXxxService（@Primary，路由实现）
            └── XxxClient 接口
                    ├── BaiLianXxxClient
                    ├── OllamaXxxClient
                    └── SiliconFlowXxxClient
```

业务代码只注入 `LLMService`、`EmbeddingService`、`RerankService`，不直接依赖具体 Client。

## 关键类

### Chat（chat/）

| 类 | 说明 |
|----|------|
| `LLMService` | 统一 LLM 接口：`chat()`、`chat(request, modelId)`（指定模型）、`streamChat()`（流式） |
| `RoutingLLMService` | `@Primary` 实现，调用 `ModelSelector` 选模型，通过 `ModelRoutingExecutor` 执行并支持降级 |
| `ChatClient` | 单个模型提供商的聊天客户端接口 |
| `BaiLianChatClient` | 阿里百炼（qwen 系列）实现 |
| `OllamaChatClient` | Ollama 本地模型实现 |
| `SiliconFlowChatClient` | 硅基流动实现 |
| `StreamCallback` | 流式响应回调接口（`onContent`、`onThinking`、`onTokenUsage`、`onComplete`、`onError`） |
| `ProbeStreamBridge` | 流式首包探测桥接器，缓冲回调事件直到首包成功后回放 |
| `OpenAIStyleSseParser` | 解析 OpenAI 格式的 SSE 流（含 `[DONE]`、usage frame） |
| `StreamAsyncExecutor` | 在独立线程池中执行流式请求，`streamChat()` 调用后立即返回 |
| `TokenUsage` | 封装 prompt_tokens、completion_tokens、total_tokens |

### Embedding（embedding/）

| 类 | 说明 |
|----|------|
| `EmbeddingService` | 统一嵌入接口：`embed(text)` 和 `batchEmbed(texts)` |
| `RoutingEmbeddingService` | `@Primary` 路由实现 |
| `OllamaEmbeddingClient` | Ollama 向量化 |
| `SiliconFlowEmbeddingClient` | 硅基流动向量化 |

### Rerank（rerank/）

| 类 | 说明 |
|----|------|
| `RerankService` | 统一重排接口 |
| `RoutingRerankService` | `@Primary` 路由实现 |
| `BaiLianRerankClient` | 百炼重排实现 |
| `NoopRerankClient` | 空实现（测试/占位，直接返回原顺序） |

### 模型路由（model/）

| 类 | 说明 |
|----|------|
| `ModelSelector` | 根据请求特征（如 deepThinking 模式）选择候选模型，按 priority 排序 |
| `ModelRoutingExecutor` | 执行模型调用，失败时自动降级到下一个候选 |
| `ModelHealthStore` | 基于 Redis 的模型健康状态存储，故障计数超阈值后熔断 |
| `AIModelProperties` | 对应 `application.yaml` 中 `ai.*` 配置的属性类 |

### 配置结构（AIModelProperties）

```yaml
ai:
  chat:
    default-model: qwen3-max
    candidates:
      - id: qwen3-max
        provider: BAI_LIAN      # 枚举: OLLAMA / BAI_LIAN / SILICON_FLOW / NOOP
        model: qwen3-max-latest
        priority: 3             # 数字越大优先级越高
        enabled: true
  embedding:
    default-model: qwen-emb-8b
    candidates: [...]
  rerank:
    default-model: qwen3-rerank
  selection:
    failure-threshold: 2        # 失败多少次后熔断该模型
```

## 关键 Gotchas

- **OpenAI SSE 帧顺序**：`finish_reason` 帧 → usage 帧（空 choices）→ `[DONE]`。循环必须在收到 `[DONE]`（`streamEnded`）时才退出，而不是在收到 `finish_reason`（`finished`）时退出，否则 usage 数据会丢失。`OpenAIStyleSseParser` 已处理此逻辑。
- **`streamChat()` 立即返回**：流式请求由 `StreamAsyncExecutor` 在独立线程池执行，调用 `streamChat()` 后主线程立即得到返回值。`StreamCallback` 的各方法运行在 `modelStreamExecutor` 线程池上，不要在调用线程等待回调完成。
- **`NoopRerankClient`**：如果重排模型未配置或不可用，系统默认使用 `NoopRerankClient`（返回原始顺序），不会报错。这是有意设计的降级行为。
- **模型健康检测**：`ModelHealthStore` 记录每个模型的连续失败次数。`ModelRoutingExecutor` 在选择候选时会跳过处于熔断状态的模型。新增模型提供商时需注册对应的 Client Bean。
- **深度思考模式**：`ModelSelector` 会检查请求中的 `deepThinking` 标志，从 `ai.chat.deep-thinking-model` 对应的候选列表中选择模型（而非默认候选列表）。
- **Buffer replay must happen outside the lock**：`ProbeStreamBridge.commit()` must snapshot+clear the buffer inside `synchronized(lock)`, then replay the snapshot outside. Holding the lock during `downstream.onContent()` etc. blocks the stream callback thread delivering concurrent events.
