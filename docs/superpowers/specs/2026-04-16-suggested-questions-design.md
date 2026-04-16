# 智能问题预测（Suggested Questions）设计文档

**日期**：2026-04-16
**状态**：设计已确认，待撰写实现计划
**参考**：`vibe-coding了一个预测下一次提问的功能.pdf`（外部原始 PDF，本文档对其中的方案做了修正与适配）

---

## 1. 目标与用户故事

每次 RAG 问答完成后，系统基于用户本轮问题、对话历史、检索到的文档片段、以及本轮回答内容，自动预测 3 个最可能的后续提问，以"chip 按钮"形式展示在回答下方。用户点击任一 chip 即自动发起下一轮提问（等价于用户手动输入并发送）。

类似产品：ChatGPT 的 "Continue"、Perplexity 的 "Related questions"。

**核心约束：**
- 不破坏现有系统架构
- 不使用 RocketMQ（独立的异步处理用专用 `ThreadPoolTaskExecutor`）
- 推荐生成是 UX 辅助功能：失败不影响主回答链路
- 不持久化推荐内容到结构化表（chip 是短暂 UX）；仅写入 `t_rag_trace_run.extra_data` JSON 做审计

---

## 2. 需求决策（已确认）

### 2.1 交付时机 —— 方案 B：新 SSE 事件 `suggestions`

拒绝方案：
- **A. 阻塞 FINISH**：用户会感知"答案写完但状态仍 loading"，体验差
- **C. 独立 HTTP 请求**：多一次 round-trip、服务端还要通过 messageId 反查上下文，复杂

采纳方案 B：FINISH 立刻发、保持 SSE 连接、异步生成完再发 `suggestions` 事件、最后发 `done` 关闭连接。

### 2.2 推荐生成策略 —— 基于已检索片段 grounding + 小模型独立调用

输入：`当前问题 + 对话历史（最近 2 轮） + 检索片段（top-3） + 本轮回答`

拒绝方案：
- **合并到主回答一次 LLM 调用**：主回答流是 token-by-token 推送给前端的，混入结构化输出需要状态机边界检测，脆弱且耦合主链路
- **事后用 embedding 校验**：额外 3 次 embedding + 3 次检索，过度工程
- **纯 LLM 推测**：推荐质量差，可能推荐 KB 没有的问题

采纳方案：
- 独立 LLM 调用，但路由到**小模型**（默认 `qwen-turbo`，可配置）
- 参考 `MultiQuestionRewriteService` 模式：低温度（0.1）、低 topP（0.3）、max tokens 150、JSON 输出

### 2.3 跳过条件（5 条）

以下场景**不触发**推荐生成，直接发 `done` 关闭：

| # | 场景 | 判定依据 |
|---|------|---------|
| 1 | 用户取消生成 | `taskManager.isCancelled(taskId)` |
| 2 | LLM 主回答出错 | `onError` 已被触发 |
| 3 | 意图歧义引导 | `IntentGuidanceService.detectAmbiguity` 短路返回 |
| 4 | System-Only 意图 | `RAGChatServiceImpl` 检测到全 SYSTEM 意图、跳过检索 |
| 5 | 检索为空 | `RetrievalContext.isEmpty()`（`mcpContext` 和 `kbContext` 均空） |

### 2.4 MCP 场景 —— 暂不支持

当前 MCP 功能尚未开发完成。**含任何 MCP 意图的本轮对话**（无论纯 MCP 还是 mcp-kb-mixed）一律不生成推荐。未来 MCP 稳定后按需再评估。

### 2.5 配置

```yaml
rag:
  suggestions:
    enabled: true           # 功能总开关
    model-id: qwen-turbo    # 小模型 ID，留空则走 ai.chat.default-model
    max-output-tokens: 150
    timeout-ms: 5000        # 异步生成超时兜底
```

### 2.6 推荐数量 —— 固定 3 个

不做可配置。需要调整时改 prompt 模板。

### 2.7 持久化策略 —— 不做结构化持久化，只写 trace

**不创建新表、不改 `t_message` 结构**。用户若点 chip，其文本走正常 `sendMessage` 流程作为 `t_message(role=user)` 落库。

推荐内容额外写入 `t_rag_trace_run.extra_data`（现有 JSON TEXT 列），用于：
- 调试：用户反馈"推荐莫名其妙"时按 trace ID 复现
- 评测：未来做点击率 / 质量分析时数据可用
- 一致性：沿用项目已有的"extensible metrics via extra_data JSON"约定

合并到现有的 token 用量 JSON：

```json
{
  "promptTokens": 850,
  "completionTokens": 320,
  "totalTokens": 1170,
  "suggestedQuestions": ["q1", "q2", "q3"]
}
```

---

## 3. 架构

### 3.1 SSE 契约变更

```
现在：  [meta] → [message]* → [finish] → [done] → 关闭
将来：  [meta] → [message]* → [finish] → [suggestions?] → [done] → 关闭
                                               ↑
                                  跳过条件 / 生成失败 / 取消时缺省
```

**关键不变量：**
- `finish` 事件载荷**不变**（仍是 `CompletionPayload(messageId, title)`）
- `done` 事件**永远到达**（即使异步推荐生成 panic，也由 `finally` 保证）
- `cancel` / `reject` 分支不变

**事件顺序保证**：
- `finish` 在 `onComplete` 同步发出
- `suggestions`（若有）在异步任务里发出
- `done` 在同一异步任务的 `finally` 里发出
- 三者共用同一个 `SseEmitterSender`，单线程事件顺序天然有保证

### 3.2 职责分层

| 层 | 职责 |
|----|------|
| `RAGChatServiceImpl`（主编排） | 收集上下文、**预判 `shouldGenerate`**、组装 `SuggestionContext`、传给 factory |
| `StreamChatEventHandler`（SSE 协议层） | 仅维护 SSE 生命周期与事件顺序契约，**不判业务** |
| `SuggestedQuestionsService` | Prompt 渲染 + LLM 调用 + JSON 解析 + 兜底 |
| `SuggestedQuestionsExecutor`（线程池） | 隔离推荐生成、失败不拖主链路 |

此分层确保 handler 不被业务细节（什么是歧义、什么是 System-Only）污染，高内聚低耦合。

### 3.3 数据流

```
RAGChatServiceImpl.streamChat()
├── 记忆 / 改写 / 意图 / 检索（完全不变）
│
├── shouldGenerate 预判：
│     触发歧义引导？  → false
│     System-Only？   → false
│     检索为空？      → false
│     含 MCP 意图？   → false
│     否则             → true
│
├── 组装 SuggestionContext(question, history, topKChunks, shouldGenerate)
│
└── factory.createChatEventHandler(emitter, convId, taskId, suggestionContext)
        │
        └── StreamChatEventHandler.onComplete()
              ├── 记忆落库 / trace / 评测（不变）
              ├── sender.sendEvent(FINISH, payload)   ← 立刻
              │
              ├── if (!context.shouldGenerate || taskManager.isCancelled) {
              │     sender.sendEvent(DONE, "[DONE]");
              │     taskManager.unregister(taskId); sender.complete();
              │     return;
              │   }
              │
              └── executor.submit(() -> {
                    List<String> qs = List.of();
                    try {
                      qs = suggestedQuestionsService.generate(context, answer);
                      if (!taskManager.isCancelled(taskId)
                          && CollUtil.isNotEmpty(qs)) {
                          sender.sendEvent(SUGGESTIONS,
                              new SuggestionsPayload(mid, qs));
                      }
                    } catch (Exception e) {
                      log.warn("推荐生成失败", e);
                    } finally {
                      mergeTraceExtraData(qs);   // 合并到 trace.extra_data
                      sender.sendEvent(DONE, "[DONE]");
                      taskManager.unregister(taskId);
                      sender.complete();
                    }
                  });
```

**关键点：**
- `taskManager.unregister` 移到异步任务内部 —— 保证推荐生成期间任务仍能被取消
- 取消检测做两次（`executor.submit` 入口处、LLM 返回后发事件前）防竞态
- `finally` 绝对执行 `sender.complete()`，SSE 连接不会泄漏

---

## 4. 文件清单

### 4.1 后端新增

| 文件 | 说明 |
|------|------|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SuggestionsPayload.java` | `record SuggestionsPayload(String messageId, List<String> questions)` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestionContext.java` | `record(String question, List<ChatMessage> history, List<RetrievedChunk> topChunks, boolean shouldGenerate)` —— `topChunks` 由 `RAGChatServiceImpl` 从 `RetrievalContext.intentChunks` 扁平化并按相关度取 top 3 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestedQuestionsService.java` | 接口：`List<String> generate(SuggestionContext ctx, String answer)` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java` | 默认实现，仿 `MultiQuestionRewriteService` 结构 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SuggestedQuestionsExecutorConfig.java` | `@Bean("suggestedQuestionsExecutor")` 线程池配置 |
| `bootstrap/src/main/resources/prompt/suggested-questions.st` | Prompt 模板 |

### 4.2 后端修改

| 文件 | 改动 |
|------|------|
| `bootstrap/.../rag/enums/SSEEventType.java` | 新增枚举项 `SUGGESTIONS("suggestions")` |
| `bootstrap/.../rag/config/RAGConfigProperties.java` | 新增嵌套属性类 `Suggestions(enabled, modelId, maxOutputTokens, timeoutMs)` |
| `bootstrap/.../rag/constant/RAGConstant.java` | 新增 `SUGGESTED_QUESTIONS_PROMPT_PATH = "prompt/suggested-questions.st"` |
| `bootstrap/.../rag/service/handler/StreamChatHandlerParams.java` | 新增字段 `SuggestionContext suggestionContext`、`SuggestedQuestionsService suggestedQuestionsService`、`ThreadPoolTaskExecutor suggestedQuestionsExecutor` |
| `bootstrap/.../rag/service/handler/StreamChatEventHandler.java` | 重写 `onComplete()` 加异步推荐分支（保持其他逻辑不变） |
| `bootstrap/.../rag/service/handler/StreamCallbackFactory.java` | 注入新依赖；`createChatEventHandler` 签名增加 `SuggestionContext` 参数 |
| `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java` | 增加 `shouldGenerate` 预判逻辑、组装 `SuggestionContext`、传入 factory |
| `bootstrap/.../rag/service/RagTraceRecordService.java` | 新增方法 `mergeRunExtraData(String traceId, Map<String, Object> additions)` |
| `bootstrap/.../rag/service/impl/RagTraceRecordServiceImpl.java` | 实现 `mergeRunExtraData`：读 → 合并 → 写回 |
| `bootstrap/src/main/resources/application.yaml` | 新增 `rag.suggestions` 配置节 |

### 4.3 前端修改

| 文件 | 改动 |
|------|------|
| `frontend/src/types/index.ts`（或对应 `.ts`） | 新增 `SuggestionsPayload` 类型 `{ messageId: string; questions: string[] }` |
| `frontend/src/hooks/useStreamResponse.ts` | `StreamHandlers` 加 `onSuggestions?`；dispatch switch 加 `case "suggestions"` |
| `frontend/src/stores/chatStore.ts` | `Message` 类型加 `suggestedQuestions?: string[]`；`sendMessage` 绑 `onSuggestions` → 按 `messageId` 找到消息、写入数组 |
| `frontend/src/components/chat/MessageItem.tsx` | assistant 消息下方渲染推荐 chip 按钮组；点击调 `chatStore.sendMessage(q)` |

---

## 5. Prompt 模板（`prompt/suggested-questions.st`）

```
你是 RAG 知识库助手的"追问预测"子模块。基于用户本轮的问答和检索到的文档片段，
预测用户最可能提出的 3 个后续问题。

【当前问题】
{question}

【对话历史】（最近 2 轮，可为空）
{history}

【本轮检索片段】（top-3，按相关度降序，格式：[标题] 内容片段）
{chunks}

【本轮回答】（可能被尾部截断到 500 字以内）
{answer}

【要求】
1. 严格输出 3 个问题，每个不超过 30 字
2. 必须基于上述检索片段内容，不得臆造 KB 中不存在的概念
3. 3 个问题覆盖不同方向（深入 / 横向 / 实操），避免同质
4. 自然口语化，避免生硬的"请问 XXX"句式
5. 只输出 JSON，不要 markdown 代码块：
   {"questions":["q1","q2","q3"]}
```

### 5.1 解析策略

参考 `MultiQuestionRewriteService.parseRewriteAndSplit`：

1. `LLMResponseCleaner.stripMarkdownCodeFence(raw)`（剥 markdown 代码块）
2. `JsonParser.parseString()` + `obj.getAsJsonArray("questions")` 提取字符串列表
3. 若不是合法 JSON，走正则兜底（按行，提取以疑问号结尾的句子）
4. 仍失败返回空 `List.of()` —— 前端自动不渲染

### 5.2 输入组装细节

- **`chunks`**：取检索结果前 3 条，拼接为 `[标题] 内容前 150 字…` 的紧凑格式
- **`history`**：仿 `MultiQuestionRewriteService.buildRewriteRequest` —— 只保留最近 4 条 USER/ASSISTANT 消息，过滤 SYSTEM 摘要
- **`answer`**：若 > 500 字，保留尾部 500 字（结尾通常引出新方向）
- **`ChatRequest` 参数**：`temperature=0.1, topP=0.3, thinking=false, maxTokens=150`

---

## 6. 错误处理与降级

| 分支 | 处理 |
|------|------|
| 小模型超时（> `timeout-ms`） | `CompletableFuture.orTimeout` 或 Future 取消；log.warn；返回空 |
| LLM 返回非 JSON | stripMarkdownCodeFence → Gson parse → 正则兜底 → 空 |
| 返回不足 3 条 | 有几条用几条，前端照常渲染；不凑数 |
| 模型熔断 | `RoutingLLMService` 自动降级到其他候选；全挂则返空 |
| Executor 队列满 | **`AbortPolicy`** 拒绝 —— 推荐是辅助功能，宁可不推荐也别拖 done |
| 任何异常 | log.warn；**`finally` 保证 DONE + complete 必发** |
| `extra_data` 写入失败 | log.warn；不影响 SSE 收尾 |

---

## 7. 线程池设计

```java
@Bean("suggestedQuestionsExecutor")
public ThreadPoolTaskExecutor suggestedQuestionsExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(2);
    ex.setMaxPoolSize(8);
    ex.setQueueCapacity(50);
    ex.setThreadNamePrefix("suggest-");
    ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    ex.setWaitForTasksToCompleteOnShutdown(true);
    ex.setAwaitTerminationSeconds(10);
    ex.initialize();
    return ex;
}
```

**设计考虑：**
- 与 `modelStreamExecutor` 隔离 —— 推荐生成的故障不能影响主流式回调
- Queue 50 × 平均生成 500ms ≈ 25 秒的突发缓冲，日常够用
- `AbortPolicy` 比 `CallerRunsPolicy` 更符合本功能的定位（辅助 UX 不可阻塞主链路）

---

## 8. 测试策略

### 8.1 单元测试

**`DefaultSuggestedQuestionsServiceTest`**（mock `LLMService` 和 `PromptTemplateLoader`）

| 用例 | 期望 |
|------|------|
| 正常 3 条 JSON 返回 | 返回 `List` 大小 = 3 |
| LLM 返回 markdown 包裹 JSON | 剥壳后解析成功 |
| LLM 返回完全非 JSON 文本 | 正则兜底，至少返回其中可识别的疑问句；全失败返空 |
| LLM 抛异常 | 返回空 `List`，不向上抛 |
| `chunks` 为空 | `shouldGenerate` 已在上游阻断，此 case 不到达 service；防御式返空 |

**`RAGChatServiceImplShouldGenerateTest`**

覆盖 5 个跳过条件（歧义、System-Only、空检索、MCP 意图、MCP 混合）→ `shouldGenerate` = false。

### 8.2 集成测试

**`StreamChatEventHandlerSuggestionsIntegrationTest`**（mock LLM）

| 用例 | 验证事件序列 |
|------|-------------|
| 正常问答 | `finish → suggestions → done` |
| `shouldGenerate=false` | `finish → done`（无 `suggestions`） |
| 推荐生成异常 | `finish → done`（无 `suggestions`，有 warn 日志） |
| finish 后用户 cancel | `finish → done`（无 `suggestions`，`cancel` 事件已处理） |

### 8.3 手动验收

前置：`mvn -pl bootstrap clean spring-boot:run` + `cd frontend && npm run dev`

1. 发 KB 相关问题（如"Java 有哪些特点"）→ 答案完成后 ~500ms 出现 3 个 chip
2. 点任一 chip → 自动作为下一轮 user 消息发送
3. 发歧义问题（触发 `IntentGuidanceService`）→ 无 chip
4. 发首轮问题（无历史）→ 正常出 chip
5. 配 `rag.suggestions.enabled: false` → 重启后彻底无 chip
6. 杀掉 `qwen-turbo` 或让其不可达 → 验证 `RoutingLLMService` 降级；若全挂则无 chip 但 done 照常到达
7. 打开浏览器 Network → 看 SSE 事件流顺序正确；观察 `t_rag_trace_run.extra_data` JSON 里有 `suggestedQuestions` 字段

---

## 9. 不变量清单

| # | 不变量 | 如何保证 |
|---|--------|---------|
| 1 | 主回答链路不受推荐功能影响 | 推荐生成在独立 executor 异步执行 |
| 2 | `done` 事件永远到达 | 异步任务 `finally` 保证 |
| 3 | `CompletionPayload` 契约不变 | 推荐走独立 `SuggestionsPayload`，不扩 `finish` 载荷 |
| 4 | SSE 事件顺序保证 | 单 `SseEmitterSender`，同步 `sendEvent` 调用 |
| 5 | 任务取消能终止推荐生成 | 异步任务入口和发事件前两次检测 `isCancelled` |
| 6 | 不新增数据库迁移 | 仅读写现有 `t_rag_trace_run.extra_data` JSON 列 |
| 7 | MCP 意图暂不触发 | `shouldGenerate` 预判中拦截 |

---

## 10. 超出范围（Out of Scope）

以下内容**本次不做**，记录以备将来：

- MCP 场景下的推荐生成（MCP 功能稳定后再评估）
- 推荐点击率埋点 / 分析面板（前端可先发 telemetry 事件，后端暂不处理）
- 推荐内容 embedding 事后校验（事后检索过滤，避免推荐 KB 没覆盖的问题；当前靠 prompt 中的 grounding 约束）
- 历史会话"回溯补推荐"（chip 不持久化到 `t_message`，这个功能不存在）
- 可配置推荐数量（固定 3 个）

---

## 11. 后续步骤

1. 本 spec 经用户 review 确认无误
2. 调用 `superpowers:writing-plans` skill 产出详细实现计划
3. 按计划分阶段执行（后端基础设施 → 后端业务 → 前端）
