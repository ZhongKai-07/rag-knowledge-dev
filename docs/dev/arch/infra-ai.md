# infra-ai 层架构

> AI 基础设施层，为 Bootstrap 业务提供**统一的 LLM / Embedding / Rerank 接口**，屏蔽多供应商差异，内建路由、降级、健康熔断、异步流式支撑。
>
> 架构图：[`diagram/architecture/arch_infra_ai.drawio`](../../../diagram/architecture/arch_infra_ai.drawio)

## 1. 三层契约

```
Bootstrap 业务代码
      │  inject
      ▼
Service 接口            ← LLMService · EmbeddingService · RerankService
      │  @Primary impl
      ▼
RoutingXxxService       ← 模型选择 + 降级 + 熔断
      │  delegate to
      ▼
XxxClient 接口          ← 单模型/单厂商调用
      │  concrete
      ▼
BaiLian / Ollama / SiliconFlow / Noop
```

**核心设计**：业务注入的是 `LLMService` 接口，永远看不到具体模型 ID 或供应商；模型编排全部内聚在 `infra-ai`。

## 2. 包结构

```
com.knowledgebase.ai.ragent.infra/
├── chat/         ← LLM 聊天（同步 + 流式）
├── embedding/    ← 文本向量化
├── rerank/       ← 检索结果重排
├── model/        ← 模型选择、路由执行、健康熔断
├── config/       ← AIModelProperties（对应 ai.* 配置节）
├── enums/        ← ProviderType 等枚举
├── http/         ← 通用 HTTP 工具、异常
├── token/        ← Token 计数
└── util/         ← LLM 响应清洗
```

## 3. Chat 能力

### 3.1 接口

```java
public interface LLMService {
    ChatResponse chat(ChatRequest request);                       // 同步，走默认模型
    ChatResponse chat(ChatRequest request, String modelId);       // 指定模型
    void streamChat(ChatRequest request, StreamCallback callback);// 流式
}
```

`ChatRequest` / `ChatMessage` / `ChatResponse` 定义在 **framework/convention/**，保持跨模块可用。

### 3.2 Routing 实现

`RoutingLLMService`（`@Primary`）内部：

1. **ModelSelector** 根据 request 特征（`deepThinking` flag 等）从 `ai.chat.candidates` 或 `ai.chat.deep-thinking-model` 选出候选列表，按 `priority` 数字**从大到小**排序。
2. **ModelRoutingExecutor** 顺序尝试：
   - 若 `ModelHealthStore.isOpen(modelId)`（熔断）→ 跳过。
   - 调用对应 `ChatClient.chat()`/`streamChat()`。
   - 成功即返回；失败 → `health.recordFailure()` → 下一家。
3. 全部失败抛 `RemoteException`。

### 3.3 Client 实现

| Client | 提供商 | 协议 |
| --- | --- | --- |
| `BaiLianChatClient` | 阿里百炼（qwen-max / qwen-turbo） | OpenAI 兼容 SSE |
| `OllamaChatClient` | 本地 Ollama | Ollama 原生 API |
| `SiliconFlowChatClient` | 硅基流动 | OpenAI 兼容 |

新增第 4 家：实现 `ChatClient` 接口 + 在 `application.yaml` `ai.chat.candidates` 加条目，业务不感知。

## 4. Embedding 能力

同构三层：`EmbeddingService` → `RoutingEmbeddingService` → `EmbeddingClient`。

| Client | 特点 |
| --- | --- |
| `OllamaEmbeddingClient` | 本地，推荐开发环境 |
| `SiliconFlowEmbeddingClient` | 云端默认 |

支持 `embed(text)` 单条和 `batchEmbed(texts)` 批量（Chunker 节点用批量）。

## 5. Rerank 能力

| Client | 特点 |
| --- | --- |
| `BaiLianRerankClient` | qwen3-rerank 模型 |
| `NoopRerankClient` | **兜底实现**：候选全部不可用/未配置时保持原序返回，不报错 |

Noop 兜底是**有意的 graceful 降级**：Rerank 失败不应阻断问答。业务调用方永远拿得到有序结果。

## 6. 配置结构

```yaml
ai:
  chat:
    default-model: qwen3-max
    deep-thinking-model: qwen-deep                # deepThinking 请求走另一批候选
    candidates:
      - id: qwen3-max
        provider: BAI_LIAN                         # OLLAMA / BAI_LIAN / SILICON_FLOW / NOOP
        model: qwen3-max-latest
        priority: 3                                # 数字越大越优先
        enabled: true
      - id: siliconflow-fallback
        provider: SILICON_FLOW
        priority: 1
        enabled: true
  embedding:
    default-model: qwen-emb-8b
    candidates: [...]
  rerank:
    default-model: qwen3-rerank
  selection:
    failure-threshold: 2                           # 连续失败多少次熔断
```

对应属性类 `AIModelProperties`。

## 7. 流式链路（SSE）

```
Business thread            Model stream executor thread
────────────────           ──────────────────────────
streamChat(req, cb)
  → StreamAsyncExecutor
      .submit(task) ────────► task.run():
  return immediately            ChatClient.streamChat(req)
                                  → OpenAIStyleSseParser
                                       loop read frames:
                                         content/thinking → cb.onContent/onThinking
                                         finish_reason   → set finished=true
                                         usage           → cb.onTokenUsage
                                         [DONE]          → set streamEnded=true, break
                                  cb.onComplete()
                                  (or cb.onError on failure → 路由层尝试下一家)
```

**三个反复踩坑的点**：

1. **SSE 帧顺序**：`finish_reason → usage → [DONE]`。循环退出必须看 `streamEnded`（`[DONE]`），看 `finished`（`finish_reason`）会把 usage 帧吞掉。`OpenAIStyleSseParser` 内部已处理。
2. **StreamChatEventHandler 在另一个线程**：业务发起 `streamChat()` 后立即返回，**调用线程里读不到回调写的 ThreadLocal**。需要的上下文要在构造 callback 时就捕获（例如 `RagTraceContext` 的 `traceId`）。
3. **ProbeStreamBridge 锁边界**：`commit()` 必须在 `synchronized(lock)` 内完成 buffer snapshot + clear，**replay 必须在锁外**进行；否则回放时持锁阻塞并发回调。

## 8. 健康与熔断

- **`ModelHealthStore`**：Redis 存储每个模型的连续失败次数 `model_health:{modelId}`。
- **熔断阈值**：`ai.selection.failure-threshold`（默认 2）。
- **恢复**：暂采用被动方式 —— 健康状态有 TTL，超时自动恢复（具体 TTL 见实现）。
- **选择时跳过**：`ModelRoutingExecutor` 在遍历候选时调 `health.isOpen(id)` 判断是否跳过。

## 9. 评审关注点

- **封装边界干净**：业务永远不触碰具体 Client / ProviderType 枚举。新增供应商只需一个 `@Component` + yaml 配置。
- **流式异步是一等公民**：`StreamAsyncExecutor` 单独的线程池，回调语义清晰，适合对接后端 `StreamChatEventHandler`。
- **Rerank 失败不阻断**：Noop 兜底让"重排质量"成为增强而非强依赖。
- **配置驱动 > 代码分支**：模型优先级、熔断阈值、候选集均在 yaml；切换运行时行为无需重启业务代码（除配置热加载未启用）。
- **不依赖 bootstrap**：保持 infra-ai 单向依赖 framework，可以独立被其他应用复用。
