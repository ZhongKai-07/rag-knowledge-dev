# PR E3 Spike: AnswerPipeline 抽取 ADR

- **日期**：2026-04-25
- **分支**：`feature/eval-e3-spike`
- **决议状态**：✅ 已确定
- **作者**：ZhongKai-07（spike via Claude）
- **相关**：`docs/superpowers/specs/2026-04-24-rag-eval-closed-loop-design.md` §6.2 / §13.1 / §15

---

## 1. Spike 目标

PR E2 后，spec §13.1 标记 PR E3 最大未知项为：

> 从 `RAGChatServiceImpl.streamChat` 抽出 `AnswerPipeline`（最小同步编排）的真实工程量与隐性耦合 —— 必须先做 spike PR 验证抽取边界。

本 spike 回答两个问题：

1. **抽 `AnswerPipeline` 出来工程量多大？**
2. **`ChatForEvalService` 是否必须依赖这次重构？**

---

## 2. 调查方法

逐项验证 spec §13.1 列出的"隐性耦合"：

| 担忧 | spike 检查 | 文件:行 |
|---|---|---|
| `streamChat` 内嵌 `memoryService.loadAndAppend` 副作用 | grep 调用点；查 history 用法 | `RAGChatServiceImpl.java:150` |
| `evalCollector` ThreadLocal 被下游消费 | grep `EvaluationCollector\|evalCollector` 全 bootstrap | 18 个文件全扫 |
| `RagTraceContext` TL 被下游服务读 | grep `rag/core/` 子树 | 0 引用 |
| `LLMService` 是否有同步 API | 读 `LLMService.java` | line 86 |
| `retrievalEngine.retrieve` 签名是否纯净 | 读 callsite | `RAGChatServiceImpl.java:187` |
| `@ChatRateLimit` 是否硬性绑定 | 读切面 | `ChatRateLimitAspect.java` |
| `@RagTraceRoot/Node` 是否要求 traceId 非空 | 读切面 | `RagTraceAspect.java:122-125` |

---

## 3. 关键发现

### 3.1 ✅ `LLMService.chat(ChatRequest)` 同步 API 已存在

`infra-ai/.../LLMService.java:86`：

```java
String chat(ChatRequest request);
```

**含义**：spec §6.2 假设的"streamChat 是 void、需先抽出 AnswerPipeline 才能拿到完整 answer"——前提**不成立**。`RoutingLLMService` 直接给同步同步调用入口。

### 3.2 ✅ 下游服务零 ThreadLocal 依赖

`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/` 全子树 grep `RagTraceContext`：**0 命中**。

`evalCollector` 写读两点确认：
- **写入仅一处**：`RAGChatServiceImpl.streamChat` 自身
- **读取仅一处**：`StreamChatEventHandler.java:342`（`onComplete` → 写 legacy `t_rag_evaluation_record`）
- `RetrievalEngine` / `RAGPromptService` / `QueryRewriteService` / `IntentResolver` / `GuidanceService` / `ConversationMemoryService` / `LLMService` —— **零引用 evalCollector**

**含义**：eval 路径**不调** `RagTraceContext.setEvalCollector` 完全安全，下游服务不会因此 NPE 或漂日志。

### 3.3 ✅ Trace 切面对 null traceId 优雅 fallthrough

`RagTraceAspect.aroundNode` line 122-125：

```java
String traceId = RagTraceContext.getTraceId();
if (StrUtil.isBlank(traceId)) {
    return joinPoint.proceed();   // 不创建 trace node，直接执行
}
```

**含义**：`ChatForEvalService` 不挂 `@RagTraceRoot`、不进 `@ChatRateLimit` 切面 → 下游 `@RagTraceNode` 看到 traceId blank → **跳过 trace 写入** → t_rag_trace_run / t_rag_trace_node 不会被 eval 污染。这恰好满足 spec §10.2 "完全不注册 trace run"。

### 3.4 ✅ `retrievalEngine.retrieve` 签名干净

```java
RetrievalContext retrieve(List<SubQuestionIntent> subIntents,
                           AccessScope scope,
                           String knowledgeBaseId);
```

3 个参数全显式，无 TL 依赖，无 callback。eval 路径只需传 `AccessScope.all()` 即可。

### 3.5 ✅ `@ChatRateLimit` 仅在 `streamChat` 一处使用

切面职责（`ChatRateLimitAspect.java`）：
1. SSE 队列限流（`chatQueueLimiter.enqueue`）
2. set `RagTraceContext.traceId/taskId`
3. 起 trace run（写 `t_rag_trace_run`）
4. finally `RagTraceContext.clear()`

`ChatForEvalService` **不挂注解** = 上述 4 件事**全跳过**，不需要任何"绕开"逻辑。

### 3.6 ⚠️ `streamChat` 早返回 3 处需映射为 `AnswerResult` 状态码

`streamChat` 现有 3 个早返回：
1. `guidance.isPrompt()` → emit guidance text + `onComplete` 早返回
2. `allSystemOnly` → 走 `streamSystemResponse`（不走 RAG，纯 system prompt 聊天）
3. `ctx.isEmpty()` → emit "未检索到与问题相关的文档内容" + `onComplete`

**eval 路径没有 SSE，必须用返回值表达**。建议 `AnswerResult` 用一个 status 枚举：

```java
enum Status {
    SUCCESS,           // 正常 RAG 回答，answer + chunks 齐全
    EMPTY_CONTEXT,     // 检索为空，answer 为占位文本，chunks=[]
    SYSTEM_ONLY,       // 命中 SystemOnly 意图，eval 应跳过该条（NULL 指标）
    AMBIGUOUS_INTENT   // 命中 guidance，同上跳过
}
```

`EvalRunExecutor` 对 `EMPTY_CONTEXT` 仍可送 Python 算分（context_recall=0 暴露检索失败比 NULL 更有用）；`SYSTEM_ONLY` / `AMBIGUOUS_INTENT` 直接写 `t_eval_result.error="..."` 并跳过。

---

## 4. 决议：**PR E3 一次性落地，不做前置 AnswerPipeline 重构**

### 4.1 拒绝 PR E3a（streamChat 重构）的理由

1. **抽 `AnswerPipeline` 唯一目的是给 eval 复用同步编排** —— 但 eval 可以**直接复用现有 7 个 service bean**（`queryRewriteService` / `intentResolver` / `guidanceService` / `retrievalEngine` / `promptBuilder` / `llmService` / `kbReadAccess`），无需一个新接口包装
2. **`streamChat` 内嵌的 `evalCollector` / `callback.emitSources` / `taskManager.bindHandle`** 三件事都是 **SSE 独有逻辑**，eval 不需要它们 —— 不重构 = 不复用 = 不传染
3. **重构 streamChat 的工程量 ≈ 完整 PR E3 的 30%**（要改 `RAGChatServiceImplSourcesTest` 10 个 mock 调用点 + handler 接线 + SSE 帧序回归测试），收益为零（eval 不依赖）
4. **风险压缩**：spec §6.2 列的"隐性耦合"在 spike 验证后**全部不存在**

### 4.2 `ChatForEvalService` 的最终形态

放在 `rag/core/`（spec §7.2 已锁定），约 **80-120 行**，零新文件依赖：

```java
@Service
@RequiredArgsConstructor
public class ChatForEvalService {

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final RAGPromptService promptBuilder;
    private final LLMService llmService;

    /** 同步阻塞调用，走真实 RAG 完整链路。仅供 eval 域使用。 */
    public AnswerResult chatForEval(String kbId, String question) {
        AccessScope systemScope = AccessScope.all(); // §15.2 边界由调用方保证

        RewriteResult rewrite = queryRewriteService.rewriteWithSplit(question, List.of());
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewrite);

        if (guidanceService.detectAmbiguity(rewrite.rewrittenQuestion(), subIntents).isPrompt()) {
            return AnswerResult.skipped(Status.AMBIGUOUS_INTENT);
        }
        if (subIntents.stream().allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()))) {
            return AnswerResult.skipped(Status.SYSTEM_ONLY);
        }

        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, systemScope, kbId);
        if (ctx.isEmpty()) {
            return AnswerResult.emptyContext();
        }

        List<RetrievedChunk> chunks = ctx.getIntentChunks().values().stream()
                .flatMap(List::stream).distinct().toList();
        IntentGroup merged = intentResolver.mergeIntentGroup(subIntents);

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                PromptContext.builder()
                        .question(rewrite.rewrittenQuestion())
                        .mcpContext(ctx.getMcpContext())
                        .kbContext(ctx.getKbContext())
                        .mcpIntents(merged.mcpIntents())
                        .kbIntents(merged.kbIntents())
                        .intentChunks(ctx.getIntentChunks())
                        .cards(List.of())  // eval 不开引用规则
                        .build(),
                List.of(),  // 无 history
                rewrite.rewrittenQuestion(),
                rewrite.subQuestions());

        String answer = llmService.chat(ChatRequest.builder()
                .messages(messages)
                .thinking(false)            // eval 强制非深度思考
                .temperature(0D)            // 减少非确定性
                .topP(1D)
                .build());

        return AnswerResult.success(answer, chunks);
    }
}
```

`AnswerResult` 是新 record（`rag/core/`）：

```java
public record AnswerResult(Status status, String answer, List<RetrievedChunk> chunks) {
    public static AnswerResult success(String answer, List<RetrievedChunk> chunks) { ... }
    public static AnswerResult emptyContext() { ... }
    public static AnswerResult skipped(Status status) { ... }
}
```

### 4.3 `streamChat` 这边**不动**

PR E3 不重构 `RAGChatServiceImpl.streamChat`：
- ✅ Sources 链路（PR4-PR5）的字节级行为保留
- ✅ `RAGChatServiceImplSourcesTest` 10 个 mock 不动
- ✅ SSE 帧序契约（META → SOURCES → MESSAGE+ → FINISH → DONE）零回归风险
- ✅ Backlog SRC-9 / EVAL-2 等遗留项不被本 PR 牵连

---

## 5. PR E3 落地骨架（不再"拆 E3a / E3b"）

| 工作量 | 文件 | 估时 |
|---|---|---|
| Java：`ChatForEvalService` + `AnswerResult` | 新增 2 文件 | 0.5 天 |
| Java：`EvalRunService` + `EvalRunExecutor` + `SystemSnapshotBuilder` | 新增 3 文件 | 1 天 |
| Java：`RagasEvalClient.evaluate()` | 已有类 + 1 方法 | 0.5 天 |
| Java：DTO `EvaluateRequest/Response` | 新增 2 record | 0.5 天 |
| Java：DAO `EvalRunMapper` / `EvalResultMapper` + xml | 新增 4 文件 | 0.5 天 |
| Java：`EvalRunController` 触发/查询/趋势 | 新增 1 文件 | 1 天 |
| Java：单测（snapshot builder / executor 三态状态机 / client） | 新增 3 文件 | 1 天 |
| Python：`evaluate.py` + `app.py` `/evaluate` 路由 | 新增 1 + 改 1 | 1 天 |
| Python：`/evaluate` 单测 | 新增 1 | 0.5 天 |
| 前端：替换 `RunsPlaceholderTab` → 真页面（运行列表 + 触发 + 轮询） | 新增 4 文件 | 1.5 天 |
| 前端：替换 `TrendsPlaceholderTab` → recharts 折线 + snapshot diff | 新增 3 文件 | 1 天 |
| 前端：`EvalRunDetailPage` 看板 + drill-down | 新增 2 文件 | 1 天 |
| 前端：`evalSuiteService.ts` 扩 run/trend API | 已有文件 + 5 函数 | 0.5 天 |
| E2E + dev_log + CLAUDE 更新 | | 0.5 天 |
| **合计** | | **~10.5 天 / 约 1.5 周** |

**前置依赖**：EVAL-3（read-side `security_level` redaction）必须**与本 PR 同步落地**或紧前。spec §15.1 / §10.2 / `eval/CLAUDE.md` 第 5 条均要求"`/eval/runs/{id}/results` 上线前落 redaction"。

---

## 6. 风险残留（非阻塞）

| 风险 | 缓解 |
|---|---|
| `LLMService.chat(ChatRequest)` 实际执行路径与 streamChat 是否完全等价（routing / 健康检查 / fallback） | 阅 `RoutingLLMService.chat()` 实现，预期复用同一 `ModelRoutingExecutor`。若 eval 走的路径与生产不同 → snapshot 记 `chat_model` 时要标注实际执行路径 |
| Python `/evaluate` 调用百炼 API QPS 爆 | spec §6.2 决策点 `max-parallel-runs=1` + `batch-size=5` 已对齐 |
| `RagTraceContext` 在 evalExecutor 线程里完全 blank → 下游日志没有 traceId 关联 | 接受。`X-Eval-Run-Id` HTTP header（spec §9.4）在 Python 侧关联，Java 侧用 `MDC.put("evalRunId", runId)` 替代 |
| `intentResolver.isSystemOnly` / `mergeIntentGroup` 行为是否对 eval 合适 | spike 未深读，PR E3 实施前需 1-hour 复核——当前直接复用风险低，因为这两个方法本身就是**纯函数**（输入 subIntents → 输出 boolean / IntentGroup） |

---

## 7. 决议要点（写进 PR E3 plan 时引用）

1. ✅ **不开 PR E3a 单独重构** —— spike 否定了"必须先抽 AnswerPipeline"的前提
2. ✅ `ChatForEvalService` 直接复用 7 个现有 service bean，无新接口
3. ✅ `streamChat` 保持字节级不变，PR E3 完全不碰 `RAGChatServiceImpl`
4. ✅ Trace / rate-limit / evalCollector 三个"隐性耦合"在 spike 验证后均**不存在**（下游零依赖 + 切面优雅 fallthrough + AOP 不挂注解即跳过）
5. ✅ EVAL-3（redaction）必须与 PR E3 同 PR 或紧前落地

---

## 8. Spike 产出

- **本 ADR**：单文件，无代码变更
- **`feature/eval-e3-spike` 分支**：仅含本文件，准备 merge 到 main 作为决议存档
- **下一步**：进入 writing-plans，为 PR E3（含 EVAL-3）输出 task-by-task 计划
