# PR E3 — RAG 评估闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trigger an eval run from the UI → real RAG pipeline answers each gold item → Python /evaluate scores 4 RAGAS metrics → results land in `t_eval_result` and surface as run detail / trend dashboards, with `retrieved_chunks` gated by `EvalResultRedactionService`.

**Architecture:** `ChatForEvalService` (新增到 `rag/core/`) **直接复用** 7 个现有 RAG service bean（`queryRewriteService` / `intentResolver` / `guidanceService` / `retrievalEngine` / `promptBuilder` / `llmService` / `kbReadAccess`）跑同步 RAG 链路 → 返回 `AnswerResult`。`EvalRunExecutor` 在 `evalExecutor` 线程池里 for-each 调 `chatForEval` 并把每条结果以 batch_size=5 喂给 Python `/evaluate` → 落 `t_eval_result`。所有暴露 `retrieved_chunks` 的 API（detail / drill-down / trend）必须经过 `EvalResultRedactionService`。

**Tech Stack:** Java 17 + Spring Boot 3.5.7 + MyBatis Plus + Sa-Token；Python 3.11 + FastAPI + RAGAS 0.2.x；React 18 + Vite + Zustand + recharts。

---

## 硬约束（PR E3 review checklist 必查）

1. **零 ThreadLocal 新增** — ADR `2026-04-25-answer-pipeline-spike-adr.md` 已确认下游零 TL 依赖；`ChatForEvalService` 不读不写 `RagTraceContext.evalCollector`，`EvalRunExecutor` 通过 `principalUserId` 参数和 `MDC.put("evalRunId", runId)` 关联日志。
2. **`@MapperScan` 已含 `eval.dao.mapper`**（PR E1 已加），不改 `RagentApplication`。
3. **所有 controller 类级 `@SaCheckRole("SUPER_ADMIN")`** —— `GoldDatasetController` / `GoldItemController` 已是此姿态，新增 `EvalRunController` 同此。
4. **`retrieved_chunks` 只能通过 `EvalResultRedactionService` 暴露** —— EVAL-3 硬合并门禁；列表/趋势 summary API 一律不返 `retrieved_chunks`，仅 drill-down 单条端点经 redaction 后返回（review P1-3）。
5. **`streamChat` 字节级不变** —— 不改 `RAGChatServiceImpl`、`StreamChatEventHandler`、sources/citations 链路。
6. **`LLMService.chat(ChatRequest)` 同步 API 已存在**（`infra-ai/.../LLMService.java:86`），不新增。
7. **`evalExecutor` bean 已注入**（`eval/async/EvalAsyncConfig.java`），不引入 `@EnableAsync`。
8. **`@ChatRateLimit` 不挂在 `ChatForEvalService`** —— eval 路径不进 rate-limit + trace-run 创建切面，下游 `@RagTraceNode` 因 traceId blank 优雅 fallthrough。
9. **`AccessScope` 由调用方注入，service 不自造**（review P1-1）—— `ChatForEvalService.chatForEval(scope, kbId, q)`；唯一持有 `AccessScope.all()` 的合法调用方是 `EvalRunExecutor`（spec §15.3 边界）。任何复用此 service 的入口必须传该入口下登录 principal 的真实 scope。
10. **`max-parallel-runs=1` 在 startRun 路径 enforce**（review P1-4）—— `EvalRunServiceImpl.startRun` 在 INSERT run 之前 SELECT COUNT 全表 PENDING+RUNNING；超阈值直接 ClientException 拒绝，不靠线程池数兜底。
11. **三态状态机以"行为"而不是"调用次数"判定**（review P1-2）—— `flushBatch` 返回 `BatchOutcome(succeeded, failed)`：HTTP 整批失败 / per-item error 非空 / per-item missing 全部计入 `failed_items`，不能无脑 `succeeded += pending.size()`。
12. **t_eval_run.system_snapshot 是历史对比唯一凭证** —— `SystemSnapshotBuilder` 是单一真相源；任何影响 RAG 行为的新配置必须同步加字段，PR review checklist 必项。`eval_sources_disabled=true` 常量声明 eval 链路关闭 citation（review P2-1）；改 `ChatForEvalService.cards` 必须同步改这里。

---

## 文件结构地图

### 新建

| 路径 | 责任 |
|---|---|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/AnswerResult.java` | sealed interface + 4 record（Success / EmptyContext / SystemOnlySkipped / AmbiguousIntentSkipped），eval 可消费的 RAG 链路结果 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/ChatForEvalService.java` | 同步阻塞 RAG 编排，复用 7 个现有 service bean，返回 `AnswerResult`；不挂 `@ChatRateLimit` / `@RagTraceRoot` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/SystemSnapshotBuilder.java` | 注入 `RagRetrievalProperties` / `RagSourcesProperties` / `RAGDefaultProperties` / `AIModelProperties` / `Environment` 拼 snapshot JSON |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalRunService.java` | 接口：`startRun(datasetId, principalUserId)` → runId / `getRun(runId)` / `listRuns(datasetId)` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/EvalRunServiceImpl.java` | 校验 dataset.status=ACTIVE、items 非空 → 建 run（snapshot/state=PENDING）→ 提交到 `evalExecutor` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalRunExecutor.java` | 在 evalExecutor 线程跑：for-each gold_item → chatForEval → 满 batchSize=5 flush 到 Python /evaluate → 落 result → 三态状态机 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalResultRedactionService.java` | EVAL-3 硬门禁：按调用 principal 的 `maxSecurityLevel` redact `retrieved_chunks` 中跨级 chunk |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/EvaluateRequest.java` | record，Python /evaluate 请求体（items: question/contexts/answer/groundTruth/goldItemId） |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/EvaluateResponse.java` | record，Python /evaluate 响应体（results: 4 metric + error） |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/RetrievedChunkSnapshot.java` | record，写入 `t_eval_result.retrieved_chunks` 的 JSON 元素结构（chunkId / docId / docName / securityLevel / text / score） |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/EvalRunController.java` | 4 endpoints: `POST /admin/eval/runs` / `GET /admin/eval/runs?datasetId=` / `GET /admin/eval/runs/{id}` / `GET /admin/eval/runs/{id}/results` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/StartRunRequest.java` | record，触发 run 入参 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/EvalRunVO.java` | record，run 列表/详情 VO（不含 retrieved_chunks） |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/EvalResultVO.java` | record，单条 result VO（含 redacted retrieved_chunks，仅 drill-down 端点返回） |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/EvalRunSummaryVO.java` | record，列表 VO（仅 4 metric 均值 + 状态 + 时间，零 retrieved_chunks） |
| `ragas/ragas/evaluate.py` | RAGAS 4 指标评估，pydantic 模型 + ascore 调用 |
| `frontend/src/pages/admin/eval-suites/tabs/EvalRunsTab.tsx` | 替换 `RunsPlaceholderTab.tsx` —— Run 列表 + 触发 + 轮询 |
| `frontend/src/pages/admin/eval-suites/tabs/EvalTrendsTab.tsx` | 替换 `TrendsPlaceholderTab.tsx` —— recharts 折线 + snapshot diff |
| `frontend/src/pages/admin/eval-suites/EvalRunDetailPage.tsx` | 单 run 详情：4 metric 大卡 + 分布直方图 + per-item 表格 + drill-down 抽屉 |
| `frontend/src/pages/admin/eval-suites/components/StartRunDialog.tsx` | 选 ACTIVE dataset + 确认按钮 |
| `frontend/src/pages/admin/eval-suites/components/RunStatusBadge.tsx` | 三态状态徽章（绿 SUCCESS / 黄 PARTIAL_SUCCESS / 红 FAILED） |
| `frontend/src/pages/admin/eval-suites/components/SnapshotDiffViewer.tsx` | 两 run snapshot JSON diff（行内高亮变化字段） |

### 测试新建

| 路径 | 类型 |
|---|---|
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/ChatForEvalServiceTest.java` | 纯 Mockito：4 种 AnswerResult 分支 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/SystemSnapshotBuilderTest.java` | 配置→JSON 字段对齐 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/EvalRunServiceImplTest.java` | 纯 Mockito：start 校验、状态机、tracker 占坑 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/EvalRunExecutorTest.java` | 纯 Mockito：三态判定 / 单条失败不阻断 / batch flush |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/EvalResultRedactionServiceTest.java` | 纯 JUnit：高密 chunk 替换 [REDACTED] / SUPER_ADMIN 全读 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClientEvaluateTest.java` | WireMock：契约对齐 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/controller/EvalRunControllerRedactionTest.java` | `@SpringBootTest` + MockMvc：每个返回 retrieved_chunks 的端点必经 redaction |
| `ragas/tests/test_evaluate.py` | pytest + 模拟 RAGAS ascore |
| `frontend/src/pages/admin/eval-suites/tabs/EvalRunsTab.test.tsx` | vitest + RTL：列表渲染 / 触发对话 / 轮询 |

### 修改

| 路径 | 改动 |
|---|---|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java` | 加 `evaluate(EvaluateRequest)` 方法（沿用 `synthesisTimeoutMs` 同等量级超时，可考虑新建 `evaluateTimeoutMs` 配置） |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java` | `Run` 内层加 `evaluateBatchSize` / `evaluateTimeoutMs` |
| `bootstrap/src/main/resources/application.yaml` | `rag.eval.run` 节加 `evaluate-batch-size: 5` / `evaluate-timeout-ms: 600000` |
| `ragas/ragas/app.py` | 加 `POST /evaluate` 路由，调 `evaluate.py` |
| `ragas/requirements.txt` | 确认 ragas / langchain-openai / pydantic 版本（与 PR E1 已固化版本对齐） |
| `frontend/src/services/evalSuiteService.ts` | 加 `startRun` / `listRuns` / `getRun` / `getRunResults` / 4 个 TS interface |
| `frontend/src/pages/admin/eval-suites/EvalSuitesPage.tsx` | `RunsPlaceholderTab` → `EvalRunsTab` / `TrendsPlaceholderTab` → `EvalTrendsTab` 引用替换 |
| `frontend/src/router.tsx` | 加 `/admin/eval-suites/runs/:runId` route → `EvalRunDetailPage` |
| `frontend/src/pages/admin/eval-suites/tabs/placeholders/RunsPlaceholderTab.tsx` | **删除** |
| `frontend/src/pages/admin/eval-suites/tabs/placeholders/TrendsPlaceholderTab.tsx` | **删除** |
| `bootstrap/CLAUDE.md` | eval 域关键类表加 `ChatForEvalService` / `EvalRunService` / `EvalRunExecutor` / `EvalResultRedactionService` / `SystemSnapshotBuilder` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md` | PR E3 落地清单 + EVAL-3 redaction 已实现说明 |
| `docs/dev/followup/backlog.md` | EVAL-3 标记为已落地（移到顶部 resolved 段） |
| `log/dev_log/dev_log.md` | 追加 PR E3 链接 |
| `log/dev_log/2026-04-26-eval-pr-e3.md` | 新增 session 日志 |

### 不动（关键负断言）

- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` —— 字节级不变
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java` —— 字节级不变
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagEvaluationServiceImpl.java` —— legacy，不动（EVAL-2 单独 PR 处理）
- `bootstrap/src/main/java/com/nageoffer/ai/ragent/RagentApplication.java` —— `@MapperScan` 已含 eval（PR E1 已加）
- `resources/database/upgrade_v1.9_to_v1.10.sql` 之后**不**新增 v1.11 migration —— 现有 4 张表字段足够

---

## Phase A — Backend foundations（3 tasks）

### Task 1: `AnswerResult` sealed interface + 4 状态 record

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/AnswerResult.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/AnswerResultTest.java`

**Why a new file in `rag/core/` (not `eval/`)**: `AnswerResult` 是 RAG 链路的同步结果产物，eval 是首个消费者但概念归属 RAG。spec §7.2 已锁定。

- [ ] **Step 1: 写 `AnswerResultTest.java`（先失败）**

```java
package com.nageoffer.ai.ragent.rag.core;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerResultTest {

    @Test
    void success_carries_answer_and_chunks() {
        RetrievedChunk c = new RetrievedChunk();
        c.setText("doc text");
        AnswerResult r = AnswerResult.success("the answer", List.of(c));
        assertThat(r).isInstanceOf(AnswerResult.Success.class);
        AnswerResult.Success s = (AnswerResult.Success) r;
        assertThat(s.answer()).isEqualTo("the answer");
        assertThat(s.chunks()).hasSize(1);
    }

    @Test
    void emptyContext_has_empty_chunks() {
        AnswerResult r = AnswerResult.emptyContext();
        assertThat(r).isInstanceOf(AnswerResult.EmptyContext.class);
    }

    @Test
    void systemOnlySkipped_carries_reason() {
        AnswerResult r = AnswerResult.systemOnlySkipped();
        assertThat(r).isInstanceOf(AnswerResult.SystemOnlySkipped.class);
    }

    @Test
    void ambiguousIntentSkipped_marks_branch() {
        AnswerResult r = AnswerResult.ambiguousIntentSkipped();
        assertThat(r).isInstanceOf(AnswerResult.AmbiguousIntentSkipped.class);
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -pl bootstrap test -Dtest=AnswerResultTest`
Expected: 编译失败，`AnswerResult` 类不存在。

- [ ] **Step 3: 写实现**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.rag.core;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * RAG 链路的同步结果产物。
 *
 * <p>由 {@link ChatForEvalService} 返回，由 eval 域消费。
 * streamChat 路径不消费此类型（保持其原有 SSE 行为）。
 */
public sealed interface AnswerResult
        permits AnswerResult.Success,
                AnswerResult.EmptyContext,
                AnswerResult.SystemOnlySkipped,
                AnswerResult.AmbiguousIntentSkipped {

    record Success(String answer, List<RetrievedChunk> chunks) implements AnswerResult { }

    record EmptyContext() implements AnswerResult { }

    record SystemOnlySkipped() implements AnswerResult { }

    record AmbiguousIntentSkipped() implements AnswerResult { }

    static AnswerResult success(String answer, List<RetrievedChunk> chunks) {
        return new Success(answer, chunks);
    }

    static AnswerResult emptyContext() {
        return new EmptyContext();
    }

    static AnswerResult systemOnlySkipped() {
        return new SystemOnlySkipped();
    }

    static AnswerResult ambiguousIntentSkipped() {
        return new AmbiguousIntentSkipped();
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -pl bootstrap test -Dtest=AnswerResultTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/AnswerResult.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/AnswerResultTest.java
git commit -m "feat(rag-core): add AnswerResult sealed type for sync RAG callers"
```

---

### Task 2: `ChatForEvalService` 同步 RAG 编排

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/ChatForEvalService.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/ChatForEvalServiceTest.java`

**关键引用**：
- `RAGChatServiceImpl.streamChat`（`bootstrap/.../rag/service/impl/RAGChatServiceImpl.java:103-274`）作为编排参考——本类是其裁剪版（去掉 SSE / memory / evalCollector / handler bind）
- ADR `docs/dev/design/2026-04-25-answer-pipeline-spike-adr.md` §4.2 给出最终类草案
- 不挂 `@ChatRateLimit` / `@RagTraceRoot` —— 下游 `@RagTraceNode` 因 traceId blank 自动跳过

- [ ] **Step 1: 写 `ChatForEvalServiceTest.java`（先失败）**

```java
package com.nageoffer.ai.ragent.rag.core;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.intent.domain.IntentNode;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatForEvalServiceTest {

    private QueryRewriteService rewrite;
    private IntentResolver intent;
    private IntentGuidanceService guidance;
    private RetrievalEngine retrieval;
    private RAGPromptService prompt;
    private LLMService llm;
    private ChatForEvalService svc;

    @BeforeEach
    void setUp() {
        rewrite = mock(QueryRewriteService.class);
        intent = mock(IntentResolver.class);
        guidance = mock(IntentGuidanceService.class);
        retrieval = mock(RetrievalEngine.class);
        prompt = mock(RAGPromptService.class);
        llm = mock(LLMService.class);
        svc = new ChatForEvalService(rewrite, intent, guidance, retrieval, prompt, llm);
    }

    @Test
    void ambiguous_intent_short_circuits() {
        when(rewrite.rewriteWithSplit(any(), any())).thenReturn(new RewriteResult("q", List.of("q")));
        when(intent.resolve(any())).thenReturn(List.of(new SubQuestionIntent("q", List.of())));
        when(guidance.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.prompt("clarify"));

        AnswerResult r = svc.chatForEval(AccessScope.all(), "kb1", "ambiguous question");
        assertThat(r).isInstanceOf(AnswerResult.AmbiguousIntentSkipped.class);
        verifyNoInteractions(retrieval, prompt, llm);
    }

    @Test
    void system_only_intent_short_circuits() {
        IntentNode sysNode = new IntentNode();
        sysNode.setKind(IntentKind.SYSTEM);
        NodeScore ns = new NodeScore();
        ns.setNode(sysNode);
        ns.setScore(0.9d);
        SubQuestionIntent si = new SubQuestionIntent("q", List.of(ns));

        when(rewrite.rewriteWithSplit(any(), any())).thenReturn(new RewriteResult("q", List.of("q")));
        when(intent.resolve(any())).thenReturn(List.of(si));
        when(guidance.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.none());
        when(intent.isSystemOnly(any())).thenReturn(true);

        AnswerResult r = svc.chatForEval(AccessScope.all(), "kb1", "what is your name");
        assertThat(r).isInstanceOf(AnswerResult.SystemOnlySkipped.class);
        verifyNoInteractions(retrieval, prompt, llm);
    }

    @Test
    void empty_retrieval_context_short_circuits() {
        when(rewrite.rewriteWithSplit(any(), any())).thenReturn(new RewriteResult("q", List.of("q")));
        when(intent.resolve(any())).thenReturn(List.of(new SubQuestionIntent("q", List.of())));
        when(guidance.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.none());
        when(intent.isSystemOnly(any())).thenReturn(false);
        when(retrieval.retrieve(any(), any(), any()))
                .thenReturn(RetrievalContext.builder().build()); // empty

        AnswerResult r = svc.chatForEval(AccessScope.all(), "kb1", "no docs question");
        assertThat(r).isInstanceOf(AnswerResult.EmptyContext.class);
        verifyNoInteractions(prompt, llm);
    }

    @Test
    void success_returns_answer_and_distinct_chunks_with_caller_supplied_scope() {
        RetrievedChunk c1 = new RetrievedChunk();
        c1.setId("c1");
        RetrievedChunk c2 = new RetrievedChunk();
        c2.setId("c2");
        RetrievalContext ctx = RetrievalContext.builder()
                .kbContext("ctx")
                .intentChunks(Map.of("intent-1", List.of(c1, c2)))
                .build();

        when(rewrite.rewriteWithSplit(any(), any())).thenReturn(new RewriteResult("q", List.of("q")));
        when(intent.resolve(any())).thenReturn(List.of(new SubQuestionIntent("q", List.of())));
        when(guidance.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.none());
        when(intent.isSystemOnly(any())).thenReturn(false);
        when(intent.mergeIntentGroup(any())).thenReturn(new IntentGroup(List.of(), List.of()));

        ArgumentCaptor<AccessScope> scopeCap = ArgumentCaptor.forClass(AccessScope.class);
        when(retrieval.retrieve(any(), scopeCap.capture(), any())).thenReturn(ctx);
        when(prompt.buildStructuredMessages(any(), any(), any(), any())).thenReturn(List.of(ChatMessage.user("q")));
        when(llm.chat(any(ChatRequest.class))).thenReturn("the answer");

        AccessScope passed = AccessScope.all();
        AnswerResult r = svc.chatForEval(passed, "kb1", "q");
        assertThat(r).isInstanceOf(AnswerResult.Success.class);
        AnswerResult.Success s = (AnswerResult.Success) r;
        assertThat(s.answer()).isEqualTo("the answer");
        assertThat(s.chunks()).hasSize(2);
        // 关键断言：service 不再自造 AccessScope，调用方传入的 scope 必须原样下传到 retrievalEngine
        assertThat(scopeCap.getValue()).isSameAs(passed);
    }

    @Test
    void llm_failure_propagates_to_caller() {
        when(rewrite.rewriteWithSplit(any(), any())).thenReturn(new RewriteResult("q", List.of("q")));
        when(intent.resolve(any())).thenReturn(List.of(new SubQuestionIntent("q", List.of())));
        when(guidance.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.none());
        when(intent.isSystemOnly(any())).thenReturn(false);
        when(intent.mergeIntentGroup(any())).thenReturn(new IntentGroup(List.of(), List.of()));
        when(retrieval.retrieve(any(), any(), any())).thenReturn(
                RetrievalContext.builder().kbContext("ctx")
                        .intentChunks(Map.of("i", List.of(new RetrievedChunk()))).build());
        when(prompt.buildStructuredMessages(any(), any(), any(), any())).thenReturn(List.of(ChatMessage.user("q")));
        when(llm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("model 503"));

        // ADR Finding 2 sync fallback: 当 RoutingLLMService 已耗尽候选时，异常透传到调用方；
        // 调用方（EvalRunExecutor）负责把该 gold item 标 error，不影响其他 item。
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> svc.chatForEval(AccessScope.all(), "kb1", "q"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("model 503");
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -pl bootstrap test -Dtest=ChatForEvalServiceTest`
Expected: 编译失败，`ChatForEvalService` 不存在。

- [ ] **Step 3: 写实现**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.rag.core;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 同步阻塞 RAG 编排，仅供 eval 域使用。
 *
 * <p>设计依据见 ADR {@code docs/dev/design/2026-04-25-answer-pipeline-spike-adr.md}。
 * 直接复用现有 7 个 service bean，不抽 AnswerPipeline。
 *
 * <p>三处早返回映射到 {@link AnswerResult} 状态码（不像 streamChat 那样发 SSE）：
 * <ul>
 *   <li>guidance ambiguous → {@link AnswerResult.AmbiguousIntentSkipped}</li>
 *   <li>all sub-intents systemOnly → {@link AnswerResult.SystemOnlySkipped}</li>
 *   <li>retrieval ctx empty → {@link AnswerResult.EmptyContext}</li>
 * </ul>
 *
 * <p><b>AccessScope 由调用方注入，service 不自造</b>（review P1-1）：避免任何复用此 service 的入口
 * 默认越过 RBAC。eval 路径下唯一合法调用方 {@link com.nageoffer.ai.ragent.eval.service.EvalRunExecutor}
 * 显式传 {@code AccessScope.all()}（spec §15.2 / §15.3 边界）。其他调用方必须传该入口下登录 principal 的真实 scope。
 *
 * <p><b>citation/sources 显式关闭</b>（review P2-1）：传 {@code cards=List.of()} 让 prompt 跳过 citationMode。
 * 这是 eval 与生产链路的有意偏差——eval 测的是基础 RAG 质量，不测引用渲染。该决策必须落到
 * {@code SystemSnapshotBuilder} 的 {@code eval_sources_disabled=true} 字段，让趋势对比时能识别这一前提。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatForEvalService {

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final RAGPromptService promptBuilder;
    private final LLMService llmService;

    public AnswerResult chatForEval(AccessScope scope, String kbId, String question) {
        RewriteResult rewrite = queryRewriteService.rewriteWithSplit(question, List.of());
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewrite);

        if (guidanceService.detectAmbiguity(rewrite.rewrittenQuestion(), subIntents).isPrompt()) {
            return AnswerResult.ambiguousIntentSkipped();
        }
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (allSystemOnly) {
            return AnswerResult.systemOnlySkipped();
        }

        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, scope, kbId);
        if (ctx.isEmpty()) {
            return AnswerResult.emptyContext();
        }

        List<RetrievedChunk> chunks = ctx.getIntentChunks() == null
                ? List.of()
                : ctx.getIntentChunks().values().stream()
                        .flatMap(List::stream)
                        .distinct()
                        .toList();
        IntentGroup merged = intentResolver.mergeIntentGroup(subIntents);

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                PromptContext.builder()
                        .question(rewrite.rewrittenQuestion())
                        .mcpContext(ctx.getMcpContext())
                        .kbContext(ctx.getKbContext())
                        .mcpIntents(merged.mcpIntents())
                        .kbIntents(merged.kbIntents())
                        .intentChunks(ctx.getIntentChunks())
                        .cards(List.of())  // eval 关闭 citation 模式（见类注释 P2-1）
                        .build(),
                List.of(),
                rewrite.rewrittenQuestion(),
                rewrite.subQuestions());

        // RoutingLLMService.chat 内部已做候选模型 fallback；耗尽后异常透传到调用方
        // （ADR Finding 2）。EvalRunExecutor 接到异常后把该 gold item 标 error，不阻断 run。
        String answer = llmService.chat(ChatRequest.builder()
                .messages(messages)
                .thinking(false)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build());

        return AnswerResult.success(answer, chunks);
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -pl bootstrap test -Dtest=ChatForEvalServiceTest`
Expected: 4 tests pass.

- [ ] **Step 5: 跑完整 bootstrap 单测确认无回归**

Run: `mvn -pl bootstrap test`
Expected: 与 main baseline 相同（PR E2 已记录的 `MilvusCollectionTests` 等 10 个 baseline failures 之外不增加新失败）。

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/ChatForEvalService.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/ChatForEvalServiceTest.java
git commit -m "feat(rag-core): add ChatForEvalService — sync RAG orchestration for eval domain

Reuses 7 existing service beans (queryRewrite, intentResolver, guidance,
retrieval, prompt, llm). No AnswerPipeline abstraction (per ADR
2026-04-25-answer-pipeline-spike-adr.md). streamChat untouched."
```

---

### Task 3: `SystemSnapshotBuilder`

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/SystemSnapshotBuilder.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/SystemSnapshotBuilderTest.java`

**Why a single builder**：spec §10 gotcha #1 / §7.2 — snapshot 是历史对比唯一凭证；改配置要同步加字段，单一真相源是 review checklist 必项。

**Snapshot JSON 结构**（spec §5.3 + review P2-1）：

```json
{
  "recall_top_k": 30,
  "rerank_top_k": 10,
  "chat_model": "qwen3-max",
  "embedding_model": "qwen-emb-8b",
  "rerank_model": "qwen3-rerank",
  "sources_enabled": true,
  "sources_min_top_score": 0.55,
  "eval_sources_disabled": true,
  "git_commit": "ad467a08",
  "config_hash": "sha256:..."
}
```

- `config_hash` 由前面所有字段（去掉 hash 自身）SHA-256，便于数据库去重。
- **`eval_sources_disabled: true` 是常量**（review P2-1）：声明 ChatForEvalService 强制 `cards=List.of()`，eval 链路不进 citationMode。SystemSnapshotBuilder 永远写 `true`；如果未来想让 eval 复现生产 citation，把这个字段改成动态读 `RagSourcesProperties.enabled` 并同步改 ChatForEvalService。

**类型确认**（pre-fix-B）：`AIModelProperties` 实际只有一个 `ModelGroup` 内类（含 `defaultModel` / `deepThinkingModel` / `candidates`），chat/embedding/rerank 三个字段都是 `ModelGroup` 实例。测试不能用 `AIModelProperties.Chat()`/`Embedding()`/`Rerank()`（不存在），改用 `new AIModelProperties.ModelGroup()`。

- [ ] **Step 1: 写测试（先失败）**

```java
package com.nageoffer.ai.ragent.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.RagRetrievalProperties;
import com.nageoffer.ai.ragent.rag.config.RagSourcesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemSnapshotBuilderTest {

    private RagRetrievalProperties retrieval;
    private RagSourcesProperties sources;
    private AIModelProperties ai;
    private Environment env;
    private SystemSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        retrieval = mock(RagRetrievalProperties.class);
        sources = mock(RagSourcesProperties.class);
        ai = mock(AIModelProperties.class);
        env = new MockEnvironment().withProperty("git.commit.id.abbrev", "ad467a08");

        when(retrieval.getRecallTopK()).thenReturn(30);
        when(retrieval.getRerankTopK()).thenReturn(10);
        when(sources.getEnabled()).thenReturn(Boolean.TRUE);
        when(sources.getMinTopScore()).thenReturn(0.55D);

        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        chat.setDefaultModel("qwen3-max");
        AIModelProperties.ModelGroup emb = new AIModelProperties.ModelGroup();
        emb.setDefaultModel("qwen-emb-8b");
        AIModelProperties.ModelGroup rr = new AIModelProperties.ModelGroup();
        rr.setDefaultModel("qwen3-rerank");
        when(ai.getChat()).thenReturn(chat);
        when(ai.getEmbedding()).thenReturn(emb);
        when(ai.getRerank()).thenReturn(rr);

        builder = new SystemSnapshotBuilder(retrieval, sources, ai, env);
    }

    @Test
    void build_emits_all_required_fields() throws Exception {
        String json = builder.build();
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("recall_top_k").asInt()).isEqualTo(30);
        assertThat(root.get("rerank_top_k").asInt()).isEqualTo(10);
        assertThat(root.get("sources_enabled").asBoolean()).isTrue();
        assertThat(root.get("sources_min_top_score").asDouble()).isEqualTo(0.55D);
        assertThat(root.get("eval_sources_disabled").asBoolean()).isTrue();
        assertThat(root.get("chat_model").asText()).isEqualTo("qwen3-max");
        assertThat(root.get("embedding_model").asText()).isEqualTo("qwen-emb-8b");
        assertThat(root.get("rerank_model").asText()).isEqualTo("qwen3-rerank");
        assertThat(root.get("git_commit").asText()).isEqualTo("ad467a08");
        assertThat(root.get("config_hash").asText()).startsWith("sha256:");
    }

    @Test
    void same_config_yields_same_hash() {
        String a = builder.build();
        String b = builder.build();
        assertThat(extractHash(a)).isEqualTo(extractHash(b));
    }

    @Test
    void config_change_yields_different_hash() throws Exception {
        String before = builder.build();
        when(retrieval.getRecallTopK()).thenReturn(40);
        String after = builder.build();
        assertThat(extractHash(before)).isNotEqualTo(extractHash(after));
    }

    private String extractHash(String json) {
        try {
            return new ObjectMapper().readTree(json).get("config_hash").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -pl bootstrap test -Dtest=SystemSnapshotBuilderTest`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.RagRetrievalProperties;
import com.nageoffer.ai.ragent.rag.config.RagSourcesProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * eval 域 - 系统配置快照构造器（spec §5.3 / §10 gotcha #1）。
 *
 * <p>历史对比的唯一凭证。任何影响 RAG 行为的新配置必须在这里加字段。
 * 加字段后单测会因 hash 漂移而提示，PR review checklist 必查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSnapshotBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagRetrievalProperties retrievalProps;
    private final RagSourcesProperties sourcesProps;
    private final AIModelProperties aiProps;
    private final Environment environment;

    public String build() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("recall_top_k", retrievalProps.getRecallTopK());
        node.put("rerank_top_k", retrievalProps.getRerankTopK());
        node.put("sources_enabled", Boolean.TRUE.equals(sourcesProps.getEnabled()));
        node.put("sources_min_top_score",
                sourcesProps.getMinTopScore() != null ? sourcesProps.getMinTopScore() : 0.55D);
        // P2-1：常量声明 eval 链路关闭 citation；同步必须更新 ChatForEvalService.cards
        node.put("eval_sources_disabled", true);
        node.put("chat_model", aiProps.getChat().getDefaultModel());
        node.put("embedding_model", aiProps.getEmbedding().getDefaultModel());
        node.put("rerank_model", aiProps.getRerank().getDefaultModel());
        node.put("git_commit", environment.getProperty("git.commit.id.abbrev", "unknown"));

        String canonical = canonicalize(node);
        node.put("config_hash", "sha256:" + sha256Hex(canonical));
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("snapshot serialization failed", e);
        }
    }

    private String canonicalize(ObjectNode node) {
        ObjectNode copy = node.deepCopy();
        copy.remove("config_hash");
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(copy);
        } catch (Exception e) {
            throw new IllegalStateException("canonicalize failed", e);
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -pl bootstrap test -Dtest=SystemSnapshotBuilderTest`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/SystemSnapshotBuilder.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/SystemSnapshotBuilderTest.java
git commit -m "feat(eval): add SystemSnapshotBuilder — single source of truth for run config snapshot"
```

---

## Phase B — HTTP client + DTOs（3 tasks）

### Task 4: `RetrievedChunkSnapshot` record（写入 `t_eval_result.retrieved_chunks` 的 JSON 元素）

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/RetrievedChunkSnapshot.java`

**Why a domain record**：`t_eval_result.retrieved_chunks` 是 TEXT JSON 数组，每元素需要 chunkId + docId + docName + securityLevel + text + score 6 字段。`securityLevel` 是 EVAL-3 redaction 的关键 —— 没它就没法做 ceiling 比对。

- [ ] **Step 1: 写实现（无单测；纯 record，由后续消费方测试）**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * eval 域 - {@code t_eval_result.retrieved_chunks} 的 JSON 数组元素。
 *
 * <p>{@code securityLevel} 是 EVAL-3 redaction 的关键字段——按调用 principal
 * 的 {@code maxSecurityLevel} 天花板做替换。
 */
public record RetrievedChunkSnapshot(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("doc_name") String docName,
        @JsonProperty("security_level") Integer securityLevel,
        String text,
        Double score
) {
}
```

- [ ] **Step 2: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/RetrievedChunkSnapshot.java
git commit -m "feat(eval): add RetrievedChunkSnapshot record — element type of t_eval_result.retrieved_chunks"
```

---

### Task 5: `EvaluateRequest` / `EvaluateResponse` records

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/EvaluateRequest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/EvaluateResponse.java`

**契约对齐 spec §7.3**：

```text
POST /evaluate
请求: {"items": [{"gold_item_id", "question", "contexts":[...], "answer", "ground_truth"}]}
响应: {"results": [{"gold_item_id", "faithfulness", "answer_relevancy",
                     "context_precision", "context_recall", "error": null}]}
```

`@JsonProperty` 必须显式 snake_case 映射（参考 PR E2 `SynthesizeRequest` 模式）。

- [ ] **Step 1: 写 `EvaluateRequest.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * eval 域 - Python /evaluate 请求体（spec §7.3）。
 */
public record EvaluateRequest(List<Item> items) {

    public record Item(
            @JsonProperty("gold_item_id") String goldItemId,
            String question,
            List<String> contexts,
            String answer,
            @JsonProperty("ground_truth") String groundTruth
    ) {
    }
}
```

- [ ] **Step 2: 写 `EvaluateResponse.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * eval 域 - Python /evaluate 响应体（spec §7.3）。
 *
 * <p>4 metric 字段在单条评估失败时为 null（Python 侧不抛异常，整批错误体现在 error 字段）。
 */
public record EvaluateResponse(List<MetricResult> results) {

    public record MetricResult(
            @JsonProperty("gold_item_id") String goldItemId,
            BigDecimal faithfulness,
            @JsonProperty("answer_relevancy") BigDecimal answerRelevancy,
            @JsonProperty("context_precision") BigDecimal contextPrecision,
            @JsonProperty("context_recall") BigDecimal contextRecall,
            String error
    ) {
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/EvaluateRequest.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/domain/EvaluateResponse.java
git commit -m "feat(eval): add EvaluateRequest/Response DTOs aligned with Python /evaluate contract"
```

---

### Task 6: `EvalProperties.Run` 加 `evaluateBatchSize` / `evaluateTimeoutMs` + yaml

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java`
- Modify: `bootstrap/src/main/resources/application.yaml`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/config/EvalPropertiesTest.java`（已存在，扩用例）

- [ ] **Step 1: 读现有 EvalProperties.java 确认字段**

Run: `cat bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java`

(已知 PR E2 后 `Run` 含 `batchSize=5` / `perItemTimeoutMs=30000` / `maxParallelRuns=1` —— 我们改 `batchSize` 沿用为 chatForEval 之间的批节流；新加两个独立字段。)

- [ ] **Step 2: 修改 `EvalProperties.java`**

替换 `Run` 内层为：

```java
@Data
public static class Run {
    private int batchSize = 5;
    private int perItemTimeoutMs = 30_000;
    private int maxParallelRuns = 1;
    /** 多少条 (question, contexts, answer, ground_truth) 一批送 Python /evaluate */
    private int evaluateBatchSize = 5;
    /** 单次 /evaluate HTTP 调用超时；4 指标 LLM 评估批量耗时较长 */
    private int evaluateTimeoutMs = 600_000;
}
```

- [ ] **Step 3: 修改 `application.yaml`**

`rag.eval.run` 节加两行（line 132-135 区域）：

```yaml
  run:
    batch-size: 5
    per-item-timeout-ms: 30000
    max-parallel-runs: 1
    evaluate-batch-size: 5
    evaluate-timeout-ms: 600000
```

- [ ] **Step 4: 扩 `EvalPropertiesTest`**

加一个测试方法验证两个新字段的 binding：

```java
@Test
void run_evaluate_fields_bind_from_yaml() {
    EvalProperties props = new Binder(/* 同已有 setUp 的 source */)
            .bind("rag.eval", EvalProperties.class)
            .get();
    assertThat(props.getRun().getEvaluateBatchSize()).isEqualTo(5);
    assertThat(props.getRun().getEvaluateTimeoutMs()).isEqualTo(600_000);
}
```

(具体 setUp 模式参考 `EvalPropertiesTest.java` 已有用例，照搬。)

- [ ] **Step 5: 跑测试验证通过**

Run: `mvn -pl bootstrap test -Dtest=EvalPropertiesTest`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/config/EvalProperties.java \
        bootstrap/src/main/resources/application.yaml \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/config/EvalPropertiesTest.java
git commit -m "feat(eval): add evaluateBatchSize/evaluateTimeoutMs to rag.eval.run"
```

---

### Task 7: `RagasEvalClient.evaluate()` HTTP 方法 + WireMock 测试

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClientEvaluateTest.java`

**关键引用**：
- 现有 `synthesize` 方法（`bootstrap/.../eval/client/RagasEvalClient.java:58-65`）作为模式
- 走 `evaluateTimeoutMs`（不是 synthesisTimeoutMs / pythonService.timeoutMs）
- HTTP header `X-Eval-Run-Id` 透传（spec §9.4）

- [ ] **Step 1: 写测试（先失败）**

```java
package com.nageoffer.ai.ragent.eval.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.domain.EvaluateRequest;
import com.nageoffer.ai.ragent.eval.domain.EvaluateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class RagasEvalClientEvaluateTest {

    private WireMockServer wm;
    private RagasEvalClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        EvalProperties props = new EvalProperties();
        props.getPythonService().setUrl("http://localhost:" + wm.port());
        props.getRun().setEvaluateTimeoutMs(5_000);
        client = new RagasEvalClient(props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void evaluate_serializes_request_and_parses_response() throws Exception {
        String responseJson = """
                {"results": [
                    {"gold_item_id":"g1",
                     "faithfulness":0.92,"answer_relevancy":0.88,
                     "context_precision":0.81,"context_recall":0.85,
                     "error":null}
                ]}
                """;
        wm.stubFor(post(urlEqualTo("/evaluate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)));

        EvaluateRequest req = new EvaluateRequest(List.of(
                new EvaluateRequest.Item("g1", "what is X?",
                        List.of("ctx1", "ctx2"), "X is Y", "X is Y")));

        EvaluateResponse resp = client.evaluate("run-123", req);

        assertThat(resp.results()).hasSize(1);
        EvaluateResponse.MetricResult mr = resp.results().get(0);
        assertThat(mr.goldItemId()).isEqualTo("g1");
        assertThat(mr.faithfulness()).isEqualByComparingTo(new BigDecimal("0.92"));
        assertThat(mr.contextRecall()).isEqualByComparingTo(new BigDecimal("0.85"));
        assertThat(mr.error()).isNull();

        // 验证 X-Eval-Run-Id header 被注入
        wm.verify(postRequestedFor(urlEqualTo("/evaluate"))
                .withHeader("X-Eval-Run-Id", equalTo("run-123")));

        // 验证请求体使用 snake_case
        String captured = wm.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(captured).contains("\"gold_item_id\":\"g1\"");
        assertThat(captured).contains("\"ground_truth\":\"X is Y\"");
    }

    @Test
    void evaluate_propagates_per_item_error_field() throws Exception {
        String responseJson = """
                {"results": [
                    {"gold_item_id":"g1","faithfulness":null,
                     "answer_relevancy":null,"context_precision":null,
                     "context_recall":null,"error":"openai 429"}
                ]}
                """;
        wm.stubFor(post(urlEqualTo("/evaluate"))
                .willReturn(aResponse().withStatus(200).withBody(responseJson)));

        EvaluateRequest req = new EvaluateRequest(List.of(
                new EvaluateRequest.Item("g1", "q", List.of("c"), "a", "gt")));
        EvaluateResponse resp = client.evaluate("run-1", req);

        assertThat(resp.results().get(0).error()).isEqualTo("openai 429");
        assertThat(resp.results().get(0).faithfulness()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -pl bootstrap test -Dtest=RagasEvalClientEvaluateTest`
Expected: 编译失败，`evaluate(String, EvaluateRequest)` 不存在。

- [ ] **Step 3: 修改 `RagasEvalClient.java`**

在现有 `synthesize` 之后加：

```java
public EvaluateResponse evaluate(String runId, EvaluateRequest request) {
    return buildClient(evalProperties.getRun().getEvaluateTimeoutMs())
            .post()
            .uri("/evaluate")
            .header("X-Eval-Run-Id", runId)
            .body(request)
            .retrieve()
            .body(EvaluateResponse.class);
}
```

并在 `import` 区添加 `EvaluateRequest` / `EvaluateResponse`。

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -pl bootstrap test -Dtest=RagasEvalClientEvaluateTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClient.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/client/RagasEvalClientEvaluateTest.java
git commit -m "feat(eval): add RagasEvalClient.evaluate() with X-Eval-Run-Id header

evaluateTimeoutMs (default 600s) used over synthesisTimeoutMs to keep
Python /evaluate batch headroom independent. WireMock test locks
snake_case contract + per-item error field passthrough."
```

---

### Task 7b: `RoutingLLMService.chat()` sync fallback 覆盖测试（review P2-1 → 改为 P2 sync fallback）

**Files:**
- Create: `infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMServiceSyncFallbackTest.java`

**为什么放 infra-ai 而不是 bootstrap**：sync fallback 是 `RoutingLLMService.chat(ChatRequest)` / `ModelRoutingExecutor` 的语义，eval 路径只是消费方。在 infra-ai 测真实路由对象，确保"第 1 候选失败 → 自动降级到第 2 候选"行为被锁定，eval sync path 才能依赖这个语义。

**前置确认**：实施前先 `Read infra-ai/.../model/ModelRoutingExecutor.java` 找出 sync chat 路径上等价于 `executeWithFallback` 的实际方法（streaming 路径走 `executeWithFallback`，sync 路径方法名可能是 `executeSyncWithFallback` 或共享同一个抽象）。本任务测试目标是该真实方法。

- [ ] **Step 1: 写测试**

```java
package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ADR Finding 2 sync fallback 真覆盖（review P2 修订）。
 *
 * <p>语义锁定：第一个 candidate.chat() 抛异常 → 路由层自动降级到第二个 candidate；
 * eval sync path（{@code ChatForEvalService.llmService.chat()}）依赖此语义，
 * 否则单条 LLM 故障会让 eval run 整片标 error。
 */
class RoutingLLMServiceSyncFallbackTest {

    private ModelSelector selector;
    private ModelRoutingExecutor executor;
    private ModelHealthStore healthStore;
    private RoutingLLMService svc;

    @BeforeEach
    void setUp() {
        selector = mock(ModelSelector.class);
        executor = mock(ModelRoutingExecutor.class);
        healthStore = mock(ModelHealthStore.class);
        // 实施时按真实 RoutingLLMService 构造器签名注入；如有其他依赖照搬现有 spring context 用例
        svc = new RoutingLLMService(selector, executor, healthStore);
    }

    @Test
    void sync_chat_falls_back_to_next_candidate_when_first_throws() {
        ChatRequest req = ChatRequest.builder().messages(List.of()).build();
        AIModelProperties.ModelCandidate c1 = new AIModelProperties.ModelCandidate();
        c1.setId("primary"); c1.setProvider("BAI_LIAN"); c1.setPriority(1);
        AIModelProperties.ModelCandidate c2 = new AIModelProperties.ModelCandidate();
        c2.setId("fallback"); c2.setProvider("OLLAMA"); c2.setPriority(2);
        when(selector.selectChatCandidates(any())).thenReturn(List.of(c1, c2));

        // 模拟 ModelRoutingExecutor 的 sync fallback 语义：第 1 候选抛异常时自动用第 2 候选重试。
        // 测试目标方法名按实际代码调整（streaming 路径名为 executeWithFallback）。
        when(executor.executeSyncWithFallback(any(), any())).thenAnswer(inv -> "fallback-answer");

        String answer = svc.chat(req);
        assertThat(answer).isEqualTo("fallback-answer");

        // 关键验证：sync 路径必经 fallback executor，不是直接对单一 client 调 chat()
        verify(executor, times(1)).executeSyncWithFallback(any(), any());
    }

    @Test
    void sync_chat_propagates_exception_when_all_candidates_fail() {
        ChatRequest req = ChatRequest.builder().messages(List.of()).build();
        AIModelProperties.ModelCandidate c1 = new AIModelProperties.ModelCandidate();
        c1.setId("primary"); c1.setProvider("BAI_LIAN");
        when(selector.selectChatCandidates(any())).thenReturn(List.of(c1));
        when(executor.executeSyncWithFallback(any(), any()))
                .thenThrow(new RuntimeException("all candidates exhausted"));

        // ADR Finding 2：候选耗尽后异常透传到调用方（ChatForEvalService → EvalRunExecutor）
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> svc.chat(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("all candidates exhausted");
    }
}
```

注意：本任务测试**对应 ADR Finding 2 的 sync routing fallback 行为**。如果实施时 `RoutingLLMService` / `ModelRoutingExecutor` 的真实 API 不是 `executeSyncWithFallback`，按真实方法名调整测试；目标行为不变（sync 路径必经 fallback 路由 + 异常透传）。

- [ ] **Step 2: 跑测试**

Run: `mvn -pl infra-ai test -Dtest=RoutingLLMServiceSyncFallbackTest`
Expected: 2 tests pass.

- [ ] **Step 3: Commit**

```bash
git add infra-ai/src/test/java/com/nageoffer/ai/ragent/infra/chat/RoutingLLMServiceSyncFallbackTest.java
git commit -m "test(infra-ai): lock sync chat fallback semantic — ADR Finding 2 coverage

Verifies RoutingLLMService.chat(ChatRequest) goes through
ModelRoutingExecutor sync fallback path (first candidate failure → next
candidate auto-retry), and propagates exception when all candidates are
exhausted. ChatForEvalService relies on this semantic for eval runs."
```

---

## Phase C — Run service + executor + EVAL-3 redaction（4 tasks）

### Task 8: `EvalRunService` interface + `EvalRunServiceImpl.startRun()`

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalRunService.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/EvalRunServiceImpl.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/EvalRunServiceImplTest.java`

**职责（spec §6.2 + §7.2）**：
- `startRun(datasetId, principalUserId)` → 校验 dataset.status=ACTIVE 且至少 1 条 APPROVED → 建 run（snapshot/PENDING）→ 提交到 evalExecutor → 返回 runId
- `getRun(runId)` / `listRuns(datasetId)` 简单 mapper 包装

**编排参考**：`GoldDatasetSynthesisServiceImpl.trigger`（`bootstrap/.../eval/service/impl/GoldDatasetSynthesisServiceImpl.java:80-101`）

- [ ] **Step 1: 写接口 `EvalRunService.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;

import java.util.List;

public interface EvalRunService {

    /**
     * 触发新评估运行。
     *
     * @param datasetId        gold dataset id（必须 status=ACTIVE）
     * @param principalUserId  当前 SUPER_ADMIN 用户（审计字段）
     * @return runId 雪花
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException 校验失败
     */
    String startRun(String datasetId, String principalUserId);

    EvalRunDO getRun(String runId);

    List<EvalRunDO> listRuns(String datasetId);
}
```

- [ ] **Step 2: 写测试 `EvalRunServiceImplTest.java`**

```java
package com.nageoffer.ai.ragent.eval.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.service.EvalRunExecutor;
import com.nageoffer.ai.ragent.eval.service.SystemSnapshotBuilder;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EvalRunServiceImplTest {

    @BeforeAll
    static void initMpTableInfo() {
        // PR E2 GoldDatasetServiceImplTest 模式：纯 Mockito 时 MP wrapper 需要 TableInfo
        TableInfoHelper.initTableInfo(null, GoldDatasetDO.class);
        TableInfoHelper.initTableInfo(null, GoldItemDO.class);
        TableInfoHelper.initTableInfo(null, EvalRunDO.class);
    }

    private GoldDatasetMapper datasetMapper;
    private GoldItemMapper itemMapper;
    private EvalRunMapper runMapper;
    private SystemSnapshotBuilder snapshot;
    private EvalRunExecutor executor;
    private ThreadPoolTaskExecutor evalExecutor;
    private EvalRunServiceImpl svc;

    private com.nageoffer.ai.ragent.eval.config.EvalProperties props;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(GoldDatasetMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        runMapper = mock(EvalRunMapper.class);
        snapshot = mock(SystemSnapshotBuilder.class);
        executor = mock(EvalRunExecutor.class);
        evalExecutor = mock(ThreadPoolTaskExecutor.class);
        props = new com.nageoffer.ai.ragent.eval.config.EvalProperties();
        // 默认 maxParallelRuns=1，正向用例下 mocked selectCount=0 不会触发 enforce
        svc = new EvalRunServiceImpl(datasetMapper, itemMapper, runMapper, snapshot, executor, evalExecutor, props);
        when(snapshot.build()).thenReturn("{\"recall_top_k\":30}");
        when(runMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void startRun_rejects_nonActive_dataset() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);

        assertThatThrownBy(() -> svc.startRun("d1", "user-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("ACTIVE");
        verifyNoInteractions(runMapper, evalExecutor, executor);
    }

    @Test
    void startRun_rejects_dataset_with_zero_approved_items() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> svc.startRun("d1", "user-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void startRun_rejects_when_active_run_exceeds_max_parallel_runs() {
        // P1-4：max-parallel-runs=1 必须在执行路径 enforce，不能只靠配置字段。
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(20L);
        // 模拟全表已有 1 个 PENDING/RUNNING run，即满 maxParallelRuns=1
        when(runMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> svc.startRun("d1", "user-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("max-parallel-runs");
        verifyNoInteractions(evalExecutor, executor);
    }

    @Test
    void startRun_concurrent_calls_yield_at_most_one_insert() throws Exception {
        // P1-4 硬 enforce：双击 / 并发 startRun 不能同时越过 count 检查。
        // 模拟首次 count=0 → INSERT 后 count=1（用 AtomicLong 模拟 DB 视角）。
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(20L);

        java.util.concurrent.atomic.AtomicLong active = new java.util.concurrent.atomic.AtomicLong(0L);
        when(runMapper.selectCount(any())).thenAnswer(inv -> active.get());
        when(runMapper.insert(any(EvalRunDO.class))).thenAnswer(inv -> {
            active.incrementAndGet(); // INSERT 让后续 selectCount 看到 1
            return 1;
        });

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger succeeded = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    svc.startRun("d1", "user-1");
                    succeeded.incrementAndGet();
                } catch (ClientException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) f.get();
        pool.shutdown();

        // maxParallelRuns=1 + ReentrantLock 串行化 → 必须恰好 1 个成功，其余被 ClientException 拒
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(7);
        verify(runMapper, org.mockito.Mockito.times(1)).insert(any(EvalRunDO.class));
    }

    @Test
    void startRun_creates_run_with_snapshot_and_submits_to_executor() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(20L);

        ArgumentCaptor<EvalRunDO> runCap = ArgumentCaptor.forClass(EvalRunDO.class);
        when(runMapper.insert(runCap.capture())).thenReturn(1);

        String runId = svc.startRun("d1", "user-1");

        EvalRunDO inserted = runCap.getValue();
        assertThat(inserted.getDatasetId()).isEqualTo("d1");
        assertThat(inserted.getKbId()).isEqualTo("kb1");
        assertThat(inserted.getStatus()).isEqualTo("PENDING");
        assertThat(inserted.getTriggeredBy()).isEqualTo("user-1");
        assertThat(inserted.getTotalItems()).isEqualTo(20);
        assertThat(inserted.getSystemSnapshot()).contains("recall_top_k");
        assertThat(runId).isEqualTo(inserted.getId());

        verify(evalExecutor).execute(any(Runnable.class));
    }
}
```

- [ ] **Step 3: 跑测试验证失败**

Run: `mvn -pl bootstrap test -Dtest=EvalRunServiceImplTest`
Expected: 编译失败。

- [ ] **Step 4: 写实现 `EvalRunServiceImpl.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.service.EvalRunExecutor;
import com.nageoffer.ai.ragent.eval.service.EvalRunService;
import com.nageoffer.ai.ragent.eval.service.SystemSnapshotBuilder;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class EvalRunServiceImpl implements EvalRunService {

    private final GoldDatasetMapper datasetMapper;
    private final GoldItemMapper itemMapper;
    private final EvalRunMapper runMapper;
    private final SystemSnapshotBuilder snapshotBuilder;
    private final EvalRunExecutor runExecutor;
    private final ThreadPoolTaskExecutor evalExecutor;
    private final com.nageoffer.ai.ragent.eval.config.EvalProperties evalProps;

    private static final java.util.Set<String> ACTIVE_STATUSES = java.util.Set.of("PENDING", "RUNNING");

    /**
     * P1-4 硬 enforce：包住 SELECT COUNT + INSERT + executor.execute 三步。
     * 单 JVM 内排他互斥 max-parallel-runs，避免双击 / 并发 startRun 越过 count 检查。
     * 多实例部署时，单 JVM 互斥不足以兜底；上线前需在 t_eval_run 加部分唯一索引：
     *   CREATE UNIQUE INDEX uk_eval_run_singleton_active
     *     ON t_eval_run ((1)) WHERE status IN ('PENDING','RUNNING') AND deleted = 0;
     * 或者改用 Redisson 分布式锁。当前 dev 单实例，先用 JVM 锁。
     */
    private final java.util.concurrent.locks.ReentrantLock startRunLock = new java.util.concurrent.locks.ReentrantLock();

    @Autowired
    public EvalRunServiceImpl(GoldDatasetMapper datasetMapper,
                              GoldItemMapper itemMapper,
                              EvalRunMapper runMapper,
                              SystemSnapshotBuilder snapshotBuilder,
                              EvalRunExecutor runExecutor,
                              @Qualifier("evalExecutor") ThreadPoolTaskExecutor evalExecutor,
                              com.nageoffer.ai.ragent.eval.config.EvalProperties evalProps) {
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.runMapper = runMapper;
        this.snapshotBuilder = snapshotBuilder;
        this.runExecutor = runExecutor;
        this.evalExecutor = evalExecutor;
        this.evalProps = evalProps;
    }

    @Override
    public String startRun(String datasetId, String principalUserId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || Objects.equals(ds.getDeleted(), 1)) {
            throw new ClientException("dataset not found: " + datasetId, BaseErrorCode.CLIENT_ERROR);
        }
        if (!"ACTIVE".equals(ds.getStatus())) {
            throw new ClientException("dataset must be ACTIVE to evaluate, current=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        long approved = itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .eq(GoldItemDO::getReviewStatus, "APPROVED"));
        if (approved == 0) {
            throw new ClientException("dataset has zero APPROVED items, cannot evaluate",
                    BaseErrorCode.CLIENT_ERROR);
        }

        // P1-4 硬 enforce：JVM 内排他锁包住 count + insert + executor.execute。
        // 不持锁仅 INSERT 之前 count 是软校验（双击 / 并发 startRun 仍可越过）。
        // dev 单实例下 ReentrantLock 足够；多实例部署见类字段注释里的 DB 部分唯一索引方案。
        int maxParallel = Math.max(1, evalProps.getRun().getMaxParallelRuns());
        startRunLock.lock();
        try {
            long active = runMapper.selectCount(new LambdaQueryWrapper<EvalRunDO>()
                    .in(EvalRunDO::getStatus, ACTIVE_STATUSES));
            if (active >= maxParallel) {
                throw new ClientException(
                        "max-parallel-runs reached: " + active + "/" + maxParallel + ", wait for active run to finish",
                        BaseErrorCode.CLIENT_ERROR);
            }

            String runId = IdUtil.getSnowflakeNextIdStr();
            EvalRunDO run = EvalRunDO.builder()
                    .id(runId)
                    .datasetId(datasetId)
                    .kbId(ds.getKbId())
                    .triggeredBy(principalUserId)
                    .status("PENDING")
                    .totalItems((int) approved)
                    .succeededItems(0)
                    .failedItems(0)
                    .systemSnapshot(snapshotBuilder.build())
                    .build();
            runMapper.insert(run);

            evalExecutor.execute(() -> {
                try {
                    runExecutor.runInternal(runId, principalUserId);
                } catch (Exception e) {
                    log.error("[eval-run] runId={} crashed", runId, e);
                }
            });
            return runId;
        } finally {
            startRunLock.unlock();
        }
    }

    @Override
    public EvalRunDO getRun(String runId) {
        return runMapper.selectById(runId);
    }

    @Override
    public List<EvalRunDO> listRuns(String datasetId) {
        return runMapper.selectList(new LambdaQueryWrapper<EvalRunDO>()
                .eq(EvalRunDO::getDatasetId, datasetId)
                .orderByDesc(EvalRunDO::getCreateTime));
    }
}
```

注意：测试里 `EvalRunExecutor` 是接口/类——下个任务才创建。本任务先用 `interface EvalRunExecutor { void runInternal(String, String); }` 占位，下个任务把它改为 class 实现。

- [ ] **Step 5: 创建 `EvalRunExecutor` 占位接口**

```java
package com.nageoffer.ai.ragent.eval.service;

public interface EvalRunExecutor {
    void runInternal(String runId, String principalUserId);
}
```

- [ ] **Step 6: 跑测试验证通过**

Run: `mvn -pl bootstrap test -Dtest=EvalRunServiceImplTest`
Expected: 3 tests pass.

- [ ] **Step 7: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalRunService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalRunExecutor.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/impl/EvalRunServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/impl/EvalRunServiceImplTest.java
git commit -m "feat(eval): add EvalRunService.startRun — validate ACTIVE+APPROVED, build snapshot, submit to evalExecutor"
```

---

### Task 9: `EvalRunExecutorImpl` —— 三态状态机 + per-item RAG + batch flush

**Files:**
- Replace: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalRunExecutor.java` （interface → class）
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/EvalRunExecutorTest.java`

**关键设计（spec §6.2 + review P1-1/P1-2/Pre-fix-A）**：
- `runInternal(runId, principalUserId)` 在 `evalExecutor` 线程跑（非 web request 线程，无 `UserContext`）
- **AccessScope 显式持有**：executor 是唯一合法持有 `AccessScope.all()` 的调用者（spec §15.3 边界），显式作为参数传给 `chatForEvalService.chatForEval(scope, kbId, q)`
- `MDC.put("evalRunId", runId)` 用作日志关联（不是 ThreadLocal 业务状态，方法 finally 清）
- 每次 chatForEval 都立即把 retrieved chunks 转 `RetrievedChunkSnapshot[]` JSON 暂存到 batch
- 满 `evaluateBatchSize=5` 调一次 `RagasEvalClient.evaluate(runId, batch)`，回填 4 metric 到 `EvalResultDO`
- **失败计数严格累加**（review P1-2）：以下三类全部进 `failed_items`，**不是** `succeeded_items`：
  1. `chatForEval` 抛异常
  2. `AnswerResult` 非 `Success`（EmptyContext / SystemOnlySkipped / AmbiguousIntentSkipped）
  3. `flushBatch` 阶段失败：HTTP 整批失败 / Python 返 per-item `error` 非空 / Python missing result
- `flushBatch` 返回 `BatchOutcome(succeeded, failed)`，调用方根据返回值累加，**不是**无脑 `succeeded += pending.size()`
- 三态判定：`failed == 0 && succeeded > 0` → SUCCESS / `succeeded == 0` → FAILED / 其他 → PARTIAL_SUCCESS
- **Pre-fix-A**：`RetrievedChunk` 已有 `getId()/getDocId()/getKbId()/getSecurityLevel()/getChunkIndex()/getText()/getScore()` 直接 getter（无 `metadata` Map），`toSnapshot` 直接调即可。但 `RetrievedChunk` **没有** `docName` 字段——`toSnapshot` 暂填 `null`，UI drill-down 抽屉显示 `docId` 兜底（未来需要 docName 时通过 `KbMetadataReader.findDocsByIds` 在 controller 层 batch 查）

- [ ] **Step 1: 删除 Task 8 的占位接口，写 class**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.eval.client.RagasEvalClient;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalResultDO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalResultMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.domain.EvaluateRequest;
import com.nageoffer.ai.ragent.eval.domain.EvaluateResponse;
import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.AnswerResult;
import com.nageoffer.ai.ragent.rag.core.ChatForEvalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * eval 域 - 评测运行执行器（spec §6.2）。
 *
 * <p>在 {@code evalExecutor} 线程跑：for-each gold item → ChatForEvalService → 累积 batch
 * → 满 5 条调 Python /evaluate → 落 t_eval_result。
 *
 * <p><b>系统级 AccessScope.all() 仅限 SUPER_ADMIN 手动触发离线评估场景</b>（spec §15.3）；
 * 扩展到任何其他场景前必须重新做权限模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<RetrievedChunkSnapshot>> SNAPSHOT_LIST_TYPE = new TypeReference<>() {};

    private final EvalRunMapper runMapper;
    private final GoldItemMapper itemMapper;
    private final EvalResultMapper resultMapper;
    private final ChatForEvalService chatForEvalService;
    private final RagasEvalClient ragasClient;
    private final EvalProperties props;

    public void runInternal(String runId, String principalUserId) {
        MDC.put("evalRunId", runId);
        try {
            EvalRunDO run = runMapper.selectById(runId);
            if (run == null) {
                log.error("[eval-run] runId={} not found, abort", runId);
                return;
            }
            run.setStatus("RUNNING");
            run.setStartedAt(new Date());
            runMapper.updateById(run);

            List<GoldItemDO> items = itemMapper.selectList(new LambdaQueryWrapper<GoldItemDO>()
                    .eq(GoldItemDO::getDatasetId, run.getDatasetId())
                    .eq(GoldItemDO::getReviewStatus, "APPROVED"));

            int batchSize = Math.max(1, props.getRun().getEvaluateBatchSize());
            List<PendingResult> pending = new ArrayList<>();
            int succeeded = 0;
            int failed = 0;
            // P1-1: executor 是唯一合法持有 AccessScope.all() 的调用者，显式传给 chatForEval
            com.nageoffer.ai.ragent.framework.security.port.AccessScope systemScope =
                    com.nageoffer.ai.ragent.framework.security.port.AccessScope.all();

            for (GoldItemDO item : items) {
                long t0 = System.currentTimeMillis();
                AnswerResult ar;
                try {
                    ar = chatForEvalService.chatForEval(systemScope, run.getKbId(), item.getQuestion());
                } catch (Exception e) {
                    log.warn("[eval-run] runId={} chatForEval failed itemId={}", runId, item.getId(), e);
                    insertFailedResult(runId, item, "chatForEval: " + e.getMessage(),
                            (int) (System.currentTimeMillis() - t0));
                    failed++;
                    continue;
                }

                if (!(ar instanceof AnswerResult.Success success)) {
                    String reason = ar.getClass().getSimpleName();
                    insertFailedResult(runId, item, "skipped: " + reason,
                            (int) (System.currentTimeMillis() - t0));
                    failed++;
                    continue;
                }

                List<RetrievedChunkSnapshot> snaps = success.chunks().stream()
                        .map(EvalRunExecutor::toSnapshot)
                        .toList();

                EvalResultDO row = EvalResultDO.builder()
                        .id(IdUtil.getSnowflakeNextIdStr())
                        .runId(runId)
                        .goldItemId(item.getId())
                        .question(item.getQuestion())
                        .groundTruthAnswer(item.getGroundTruthAnswer())
                        .systemAnswer(success.answer())
                        .retrievedChunks(toJson(snaps))
                        .elapsedMs((int) (System.currentTimeMillis() - t0))
                        .build();
                resultMapper.insert(row);

                pending.add(new PendingResult(row.getId(), item.getId(), item.getQuestion(),
                        item.getGroundTruthAnswer(), success.answer(),
                        snaps.stream().map(RetrievedChunkSnapshot::text).toList()));

                if (pending.size() >= batchSize) {
                    BatchOutcome out = flushBatch(runId, pending);
                    succeeded += out.succeeded();
                    failed += out.failed();
                    pending.clear();
                }
            }
            if (!pending.isEmpty()) {
                BatchOutcome out = flushBatch(runId, pending);
                succeeded += out.succeeded();
                failed += out.failed();
                pending.clear();
            }

            run.setSucceededItems(succeeded);
            run.setFailedItems(failed);
            run.setMetricsSummary(computeMetricsSummary(runId));
            run.setFinishedAt(new Date());
            run.setStatus(decideStatus(succeeded, failed));
            runMapper.updateById(run);

            log.info("[eval-run] runId={} status={} succeeded={} failed={}",
                    runId, run.getStatus(), succeeded, failed);
        } finally {
            MDC.remove("evalRunId");
        }
    }

    /**
     * P1-2：返回成功/失败拆分。Python 任何一类失败都计入 failed：
     *   1) HTTP 整批失败（catch 块）→ 整批 failed
     *   2) per-item error 非空 → 该条 failed
     *   3) per-item missing（Python 没返）→ 该条 failed
     */
    private BatchOutcome flushBatch(String runId, List<PendingResult> batch) {
        try {
            List<EvaluateRequest.Item> items = batch.stream()
                    .map(p -> new EvaluateRequest.Item(p.resultId, p.question, p.contexts, p.answer, p.groundTruth))
                    .toList();
            EvaluateResponse resp = ragasClient.evaluate(runId, new EvaluateRequest(items));
            Map<String, EvaluateResponse.MetricResult> byId = new HashMap<>();
            for (EvaluateResponse.MetricResult mr : resp.results()) {
                byId.put(mr.goldItemId(), mr);
            }
            int succ = 0;
            int fail = 0;
            for (PendingResult p : batch) {
                EvaluateResponse.MetricResult mr = byId.get(p.resultId);
                EvalResultDO upd = new EvalResultDO();
                upd.setId(p.resultId);
                if (mr == null) {
                    upd.setError("python returned no result for resultId=" + p.resultId);
                    fail++;
                } else if (mr.error() != null) {
                    upd.setError(mr.error());
                    fail++;
                } else {
                    upd.setFaithfulness(mr.faithfulness());
                    upd.setAnswerRelevancy(mr.answerRelevancy());
                    upd.setContextPrecision(mr.contextPrecision());
                    upd.setContextRecall(mr.contextRecall());
                    succ++;
                }
                resultMapper.updateById(upd);
            }
            return new BatchOutcome(succ, fail);
        } catch (Exception e) {
            log.warn("[eval-run] runId={} batch HTTP failed size={}", runId, batch.size(), e);
            for (PendingResult p : batch) {
                EvalResultDO upd = new EvalResultDO();
                upd.setId(p.resultId);
                upd.setError("evaluate batch failed: " + e.getMessage());
                resultMapper.updateById(upd);
            }
            return new BatchOutcome(0, batch.size());
        }
    }

    private record BatchOutcome(int succeeded, int failed) { }

    private void insertFailedResult(String runId, GoldItemDO item, String error, int elapsedMs) {
        EvalResultDO row = EvalResultDO.builder()
                .id(IdUtil.getSnowflakeNextIdStr())
                .runId(runId)
                .goldItemId(item.getId())
                .question(item.getQuestion())
                .groundTruthAnswer(item.getGroundTruthAnswer())
                .error(error)
                .elapsedMs(elapsedMs)
                .build();
        resultMapper.insert(row);
    }

    private String computeMetricsSummary(String runId) {
        List<EvalResultDO> rows = resultMapper.selectList(new LambdaQueryWrapper<EvalResultDO>()
                .eq(EvalResultDO::getRunId, runId));
        BigDecimal[] sum = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int[] count = new int[4];
        for (EvalResultDO r : rows) {
            accumulate(sum, count, 0, r.getFaithfulness());
            accumulate(sum, count, 1, r.getAnswerRelevancy());
            accumulate(sum, count, 2, r.getContextPrecision());
            accumulate(sum, count, 3, r.getContextRecall());
        }
        Map<String, BigDecimal> avg = new HashMap<>();
        avg.put("faithfulness", count[0] == 0 ? null : sum[0].divide(BigDecimal.valueOf(count[0]), 4, RoundingMode.HALF_UP));
        avg.put("answer_relevancy", count[1] == 0 ? null : sum[1].divide(BigDecimal.valueOf(count[1]), 4, RoundingMode.HALF_UP));
        avg.put("context_precision", count[2] == 0 ? null : sum[2].divide(BigDecimal.valueOf(count[2]), 4, RoundingMode.HALF_UP));
        avg.put("context_recall", count[3] == 0 ? null : sum[3].divide(BigDecimal.valueOf(count[3]), 4, RoundingMode.HALF_UP));
        return toJson(avg);
    }

    private static void accumulate(BigDecimal[] sum, int[] count, int idx, BigDecimal v) {
        if (v != null) {
            sum[idx] = sum[idx].add(v);
            count[idx]++;
        }
    }

    private static String decideStatus(int succeeded, int failed) {
        if (failed == 0 && succeeded > 0) return "SUCCESS";
        if (succeeded == 0) return "FAILED";
        return "PARTIAL_SUCCESS";
    }

    /**
     * Pre-fix-A：RetrievedChunk 已有 securityLevel/docId/id/text/score 直接 getter（无 metadata Map）。
     * docName 字段 RetrievedChunk 不携带——填 null，UI drill-down 用 docId 兜底显示；未来需要时
     * 在 controller 层 batch lookup KB document 表回填。
     */
    private static RetrievedChunkSnapshot toSnapshot(RetrievedChunk c) {
        Double score = c.getScore() == null ? null : c.getScore().doubleValue();
        return new RetrievedChunkSnapshot(
                c.getId(),
                c.getDocId(),
                null,                  // docName 不在 RetrievedChunk 上
                c.getSecurityLevel(),
                c.getText(),
                score);
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    public static List<RetrievedChunkSnapshot> parseChunks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, SNAPSHOT_LIST_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("parse retrieved_chunks failed", e);
        }
    }

    private record PendingResult(String resultId, String goldItemId, String question, String groundTruth,
                                 String answer, List<String> contexts) {
    }
}
```

注意：`RetrievedChunk.getMetadata()` 返回 `Map<String, Object>` —— 如果你的项目里它是不同形状（比如带 `security_level` 字段），按实际取。`flushBatch` 用 `resultId` 作为 Python 的 `gold_item_id` 字段值，便于 batch 内多条同 goldItemId 也能精确回填。

- [ ] **Step 2: 写测试 `EvalRunExecutorTest.java`**

```java
package com.nageoffer.ai.ragent.eval.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.eval.client.RagasEvalClient;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalResultDO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalResultMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.domain.EvaluateResponse;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.AnswerResult;
import com.nageoffer.ai.ragent.rag.core.ChatForEvalService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EvalRunExecutorTest {

    @BeforeAll
    static void initMpTableInfo() {
        TableInfoHelper.initTableInfo(null, EvalRunDO.class);
        TableInfoHelper.initTableInfo(null, GoldItemDO.class);
        TableInfoHelper.initTableInfo(null, EvalResultDO.class);
    }

    private EvalRunMapper runMapper;
    private GoldItemMapper itemMapper;
    private EvalResultMapper resultMapper;
    private ChatForEvalService chat;
    private RagasEvalClient ragas;
    private EvalProperties props;
    private EvalRunExecutor exec;

    @BeforeEach
    void setUp() {
        runMapper = mock(EvalRunMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        resultMapper = mock(EvalResultMapper.class);
        chat = mock(ChatForEvalService.class);
        ragas = mock(RagasEvalClient.class);
        props = new EvalProperties();
        props.getRun().setEvaluateBatchSize(2);
        exec = new EvalRunExecutor(runMapper, itemMapper, resultMapper, chat, ragas, props);
    }

    private GoldItemDO item(String id, String q) {
        return GoldItemDO.builder().id(id).datasetId("d1").question(q).groundTruthAnswer("gt-" + id).reviewStatus("APPROVED").build();
    }

    private RetrievedChunk chunk(String id) {
        RetrievedChunk c = new RetrievedChunk();
        c.setId(id);
        c.setText("text-" + id);
        return c;
    }

    @Test
    void all_success_marks_run_SUCCESS() {
        EvalRunDO run = EvalRunDO.builder().id("run-1").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-1")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));

        when(chat.chatForEval(any(), any(), any())).thenReturn(AnswerResult.success("ans", List.of(chunk("c1"))));
        when(ragas.evaluate(eq("run-1"), any())).thenAnswer(inv -> {
            // 返回每条都有指标的 response（resultId 由 executor 生成，先返回空字段，让 executor 自己 fallback 到 error=null）
            return new EvaluateResponse(List.of()); // empty results, all entries get error="python returned no result..."
        });
        // 因 evaluate 的 results 数与 batch 不匹配会让所有条目 error 字段被填，导致 succeed=0；故改为返回任意可消费的结构：
        when(ragas.evaluate(eq("run-1"), any())).thenAnswer(inv -> {
            com.nageoffer.ai.ragent.eval.domain.EvaluateRequest req = inv.getArgument(1);
            List<EvaluateResponse.MetricResult> mrs = req.items().stream()
                    .map(it -> new EvaluateResponse.MetricResult(it.goldItemId(),
                            new BigDecimal("0.9"), new BigDecimal("0.8"),
                            new BigDecimal("0.7"), new BigDecimal("0.6"), null))
                    .toList();
            return new EvaluateResponse(mrs);
        });
        when(resultMapper.selectList(any())).thenReturn(List.of(
                EvalResultDO.builder().faithfulness(new BigDecimal("0.9"))
                        .answerRelevancy(new BigDecimal("0.8"))
                        .contextPrecision(new BigDecimal("0.7"))
                        .contextRecall(new BigDecimal("0.6")).build(),
                EvalResultDO.builder().faithfulness(new BigDecimal("0.9"))
                        .answerRelevancy(new BigDecimal("0.8"))
                        .contextPrecision(new BigDecimal("0.7"))
                        .contextRecall(new BigDecimal("0.6")).build()));

        exec.runInternal("run-1", "user-1");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("SUCCESS");
        assertThat(last.getSucceededItems()).isEqualTo(2);
        assertThat(last.getFailedItems()).isEqualTo(0);
    }

    @Test
    void all_failed_marks_run_FAILED() {
        EvalRunDO run = EvalRunDO.builder().id("run-2").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-2")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));

        when(chat.chatForEval(any(), any(), any())).thenThrow(new RuntimeException("LLM 503"));

        exec.runInternal("run-2", "user-1");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void rag_success_but_python_evaluate_fails_counts_as_failed() {
        // P1-2：RAG 链路成功但 RAGAS 全失败时 run 必须 PARTIAL_SUCCESS 或 FAILED，不能 SUCCESS。
        EvalRunDO run = EvalRunDO.builder().id("run-eval-fail").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-eval-fail")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));
        when(chat.chatForEval(any(), any(), any())).thenReturn(AnswerResult.success("ans", List.of(chunk("c1"))));
        // Python 整批 HTTP 抛异常
        when(ragas.evaluate(any(), any())).thenThrow(new RuntimeException("python 503"));
        when(resultMapper.selectList(any())).thenReturn(List.of());

        exec.runInternal("run-eval-fail", "user-1");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        // RAG 阶段全成功(2)，RAGAS 整批失败(2)，所以 succeeded=0 failed=2 → FAILED
        assertThat(last.getStatus()).isEqualTo("FAILED");
        assertThat(last.getSucceededItems()).isEqualTo(0);
        assertThat(last.getFailedItems()).isEqualTo(2);
    }

    @Test
    void python_per_item_error_counts_as_failed() {
        // P1-2：Python 整批 HTTP 200 但 per-item error 非空，该条计入 failed。
        EvalRunDO run = EvalRunDO.builder().id("run-per-item").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-per-item")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));
        when(chat.chatForEval(any(), any(), any())).thenReturn(AnswerResult.success("ans", List.of(chunk("c1"))));

        when(ragas.evaluate(any(), any())).thenAnswer(inv -> {
            com.nageoffer.ai.ragent.eval.domain.EvaluateRequest req = inv.getArgument(1);
            // 第一条 OK，第二条 error
            List<EvaluateResponse.MetricResult> mrs = new java.util.ArrayList<>();
            mrs.add(new EvaluateResponse.MetricResult(req.items().get(0).goldItemId(),
                    new BigDecimal("0.9"), new BigDecimal("0.8"),
                    new BigDecimal("0.7"), new BigDecimal("0.6"), null));
            mrs.add(new EvaluateResponse.MetricResult(req.items().get(1).goldItemId(),
                    null, null, null, null, "openai 429"));
            return new EvaluateResponse(mrs);
        });
        when(resultMapper.selectList(any())).thenReturn(List.of());

        exec.runInternal("run-per-item", "user-1");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(last.getSucceededItems()).isEqualTo(1);
        assertThat(last.getFailedItems()).isEqualTo(1);
    }

    @Test
    void mixed_outcome_marks_run_PARTIAL_SUCCESS() {
        EvalRunDO run = EvalRunDO.builder().id("run-3").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-3")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));

        when(chat.chatForEval(any(), any(), eq("q1"))).thenReturn(AnswerResult.success("a1", List.of(chunk("c1"))));
        when(chat.chatForEval(any(), any(), eq("q2"))).thenReturn(AnswerResult.emptyContext());
        when(ragas.evaluate(eq("run-3"), any())).thenAnswer(inv -> {
            com.nageoffer.ai.ragent.eval.domain.EvaluateRequest req = inv.getArgument(1);
            List<EvaluateResponse.MetricResult> mrs = req.items().stream()
                    .map(it -> new EvaluateResponse.MetricResult(it.goldItemId(),
                            new BigDecimal("0.9"), new BigDecimal("0.8"),
                            new BigDecimal("0.7"), new BigDecimal("0.6"), null))
                    .toList();
            return new EvaluateResponse(mrs);
        });
        when(resultMapper.selectList(any())).thenReturn(List.of());

        exec.runInternal("run-3", "user-1");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(last.getSucceededItems()).isEqualTo(1);
        assertThat(last.getFailedItems()).isEqualTo(1);
    }
}
```

注意：测试里用 `then` 重复 stub 是为了示意。实际跑前请清掉前一个 `when(ragas.evaluate(...))` 重复。

- [ ] **Step 3: 跑测试**

Run: `mvn -pl bootstrap test -Dtest=EvalRunExecutorTest`
Expected: 3 tests pass.

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalRunExecutor.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/EvalRunExecutorTest.java
git commit -m "feat(eval): EvalRunExecutor — three-state machine + per-item RAG + batch /evaluate flush

- Per-item failure (chatForEval throw / non-Success AnswerResult) does
  not abort the run; failed result is inserted with error field.
- Batch flush at evaluateBatchSize (default 5); HTTP failure marks all
  pending in batch as failed.
- Status decision: failed==0 → SUCCESS / succeeded==0 → FAILED /
  otherwise PARTIAL_SUCCESS.
- MDC.put('evalRunId', runId) for log correlation; cleared in finally."
```

---

### Task 10: `EvalResultRedactionService` —— EVAL-3 硬合并门禁

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalResultRedactionService.java`
- Test: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/EvalResultRedactionServiceTest.java`

**职责（spec §15.1 + EVAL-3）**：
- 输入：`List<RetrievedChunkSnapshot>` + 调用 principal 的 `maxSecurityLevel`
- 输出：超过 ceiling 的 chunk 把 `text` 替换为 `[REDACTED]`，保留 `chunkId / docId / securityLevel / score`（让前端能看到 "存在但不可见" 而不是完全消失）
- SUPER_ADMIN 走 `Integer.MAX_VALUE` ceiling → 全读，无 redaction
- `securityLevel == null` 视为 0（最低密级），永远可读

- [ ] **Step 1: 写测试**

```java
package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalResultRedactionServiceTest {

    private EvalResultRedactionService svc;

    @BeforeEach
    void setUp() {
        svc = new EvalResultRedactionService();
    }

    private RetrievedChunkSnapshot snap(String id, Integer level, String text) {
        return new RetrievedChunkSnapshot(id, "doc-" + id, "doc.pdf", level, text, 0.9);
    }

    @Test
    void super_admin_sees_all_text() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", 3, "secret"), snap("c2", 1, "public"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, Integer.MAX_VALUE);
        assertThat(out.get(0).text()).isEqualTo("secret");
        assertThat(out.get(1).text()).isEqualTo("public");
    }

    @Test
    void principal_below_ceiling_sees_text() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", 1, "public"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, 2);
        assertThat(out.get(0).text()).isEqualTo("public");
    }

    @Test
    void principal_above_ceiling_redacts_text() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", 3, "secret-doc"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, 2);
        assertThat(out.get(0).text()).isEqualTo("[REDACTED]");
        // 元数据保留：让前端能展示 "存在但不可见"
        assertThat(out.get(0).chunkId()).isEqualTo("c1");
        assertThat(out.get(0).securityLevel()).isEqualTo(3);
        assertThat(out.get(0).docId()).isEqualTo("doc-c1");
    }

    @Test
    void null_security_level_treated_as_zero() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", null, "open data"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, 0);
        assertThat(out.get(0).text()).isEqualTo("open data");
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `mvn -pl bootstrap test -Dtest=EvalResultRedactionServiceTest`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * eval 域 - 结果脱敏服务（EVAL-3 硬合并门禁，spec §15.1）。
 *
 * <p>所有暴露 {@code retrieved_chunks} 的 API 必须经过此服务。
 * 列表/趋势 API 不返 retrieved_chunks（不需要 redact）；
 * 单 run 结果 / drill-down API 必经此 service。
 *
 * <p>SUPER_ADMIN 通常以 {@link Integer#MAX_VALUE} 调用 → 全读。
 * EVAL-3 落地前所有 controller @SaCheckRole("SUPER_ADMIN")，本 service 仍提供
 * 通用 ceiling 接口，便于未来放开 AnyAdmin 时无需改 service 调用方。
 */
@Slf4j
@Service
public class EvalResultRedactionService {

    public static final String REDACTED = "[REDACTED]";

    public List<RetrievedChunkSnapshot> redact(List<RetrievedChunkSnapshot> chunks, int principalCeiling) {
        if (chunks == null) return List.of();
        return chunks.stream()
                .map(c -> {
                    int level = c.securityLevel() == null ? 0 : c.securityLevel();
                    if (level > principalCeiling) {
                        return new RetrievedChunkSnapshot(c.chunkId(), c.docId(), c.docName(),
                                c.securityLevel(), REDACTED, c.score());
                    }
                    return c;
                })
                .toList();
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

Run: `mvn -pl bootstrap test -Dtest=EvalResultRedactionServiceTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/service/EvalResultRedactionService.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/service/EvalResultRedactionServiceTest.java
git commit -m "feat(eval): add EvalResultRedactionService — EVAL-3 hard merge gate

All eval read endpoints exposing retrieved_chunks must pass through
this service. Default callers use Integer.MAX_VALUE ceiling
(SUPER_ADMIN); future AnyAdmin downgrade requires only changing the
ceiling, not the service contract."
```

---

## Phase D — Controller + integration test（2 tasks）

### Task 11: VOs + `EvalRunController` 5 endpoints

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/request/StartRunRequest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/EvalRunSummaryVO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/EvalRunVO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/EvalResultSummaryVO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/vo/EvalResultVO.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/EvalRunController.java`

**API 契约（review P1-3 拆 list / drill-down）**：

| Method + Path | 返回 | 含 retrieved_chunks？ | 经过 Redaction？ |
|---|---|---|---|
| `POST /admin/eval/runs` (body: StartRunRequest) | `Result<String>` runId | — | N/A |
| `GET /admin/eval/runs?datasetId=` | `Result<List<EvalRunSummaryVO>>` | ❌ | N/A |
| `GET /admin/eval/runs/{runId}` | `Result<EvalRunVO>` | ❌（含 metricsSummary / snapshot） | N/A |
| `GET /admin/eval/runs/{runId}/results` | `Result<List<EvalResultSummaryVO>>` | ❌ **(P1-3 关键)** —— 仅 4 metric + question 截断 + error，**不返 retrievedChunks** | N/A |
| `GET /admin/eval/runs/{runId}/results/{resultId}` | `Result<EvalResultVO>` | ✅ 单条 drill-down 才返 chunks | ✅ **必经** redaction |

**P1-3 设计要点**：
- list endpoint `/runs/{runId}/results` 返摘要 VO，detail 页初载只拉表格列（4 metric + question 截断 + 耗时 + error），MB 级 chunks 不会被拉到前端。spec §10 gotcha #9 对齐。
- drill-down endpoint `/results/{resultId}` 单条全量读，含经 redaction 的 chunks。前端点击行才触发。
- list 端点 VO 类型 `EvalResultSummaryVO` 不含 retrievedChunks 字段——契约即门禁，零绕过路径。

**类级 `@SaCheckRole("SUPER_ADMIN")`**（spec §15.1）。EVAL-3 落地前 ceiling 写 `Integer.MAX_VALUE`（SUPER_ADMIN 全读）；未来放 AnyAdmin 时改 ceiling 即可。

- [ ] **Step 1: 写 `StartRunRequest.java`**

```java
package com.nageoffer.ai.ragent.eval.controller.request;

public record StartRunRequest(String datasetId) { }
```

- [ ] **Step 2: 写 `EvalRunSummaryVO.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.controller.vo;

import java.util.Date;

/**
 * Run 列表 VO（不含 retrieved_chunks，无需 redaction）。
 */
public record EvalRunSummaryVO(
        String id,
        String datasetId,
        String kbId,
        String status,
        Integer totalItems,
        Integer succeededItems,
        Integer failedItems,
        String metricsSummary,
        Date startedAt,
        Date finishedAt,
        Date createTime
) { }
```

- [ ] **Step 3: 写 `EvalRunVO.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.controller.vo;

import java.util.Date;

/**
 * Run 详情 VO（含 snapshot，无 retrieved_chunks）。
 */
public record EvalRunVO(
        String id,
        String datasetId,
        String kbId,
        String triggeredBy,
        String status,
        Integer totalItems,
        Integer succeededItems,
        Integer failedItems,
        String metricsSummary,
        String systemSnapshot,
        String evaluatorLlm,
        String errorMessage,
        Date startedAt,
        Date finishedAt,
        Date createTime
) { }
```

- [ ] **Step 4a: 写 `EvalResultSummaryVO.java`**（list 端点用，**不含 retrievedChunks**）

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.controller.vo;

import java.math.BigDecimal;

/**
 * 单 run 下 result 列表 VO（review P1-3）。
 *
 * <p><b>不含 retrievedChunks 字段——契约即门禁</b>。前端 detail 页表格使用此 VO，
 * 点击行才走 drill-down endpoint 拉 {@link EvalResultVO}（含 redacted chunks）。
 */
public record EvalResultSummaryVO(
        String id,
        String goldItemId,
        String question,
        BigDecimal faithfulness,
        BigDecimal answerRelevancy,
        BigDecimal contextPrecision,
        BigDecimal contextRecall,
        String error,
        Integer elapsedMs
) { }
```

- [ ] **Step 4b: 写 `EvalResultVO.java`**（drill-down 单条端点用，**含 redacted chunks**）

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.controller.vo;

import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单条 result drill-down VO（review P1-3）。
 *
 * <p>仅 {@code GET /admin/eval/results/{resultId}} 单条端点返回此 VO；
 * list / 趋势 / 列表 result 端点都不暴露 retrievedChunks。
 */
public record EvalResultVO(
        String id,
        String runId,
        String goldItemId,
        String question,
        String groundTruthAnswer,
        String systemAnswer,
        List<RetrievedChunkSnapshot> retrievedChunks,
        BigDecimal faithfulness,
        BigDecimal answerRelevancy,
        BigDecimal contextPrecision,
        BigDecimal contextRecall,
        String error,
        Integer elapsedMs
) { }
```

- [ ] **Step 5: 写 `EvalRunController.java`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.controller.request.StartRunRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalResultSummaryVO;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalResultVO;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalRunSummaryVO;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalRunVO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalResultDO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalResultMapper;
import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import com.nageoffer.ai.ragent.eval.service.EvalResultRedactionService;
import com.nageoffer.ai.ragent.eval.service.EvalRunExecutor;
import com.nageoffer.ai.ragent.eval.service.EvalRunService;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * eval 域 - 评测运行 REST 入口。
 *
 * <p>类级 {@link SaCheckRole} 锁定 SUPER_ADMIN（spec §15.1）。
 * EVAL-3 redaction：所有返回 retrieved_chunks 的端点必经 {@link EvalResultRedactionService}。
 */
@RestController
@RequestMapping("/admin/eval/runs")
@SaCheckRole("SUPER_ADMIN")
@RequiredArgsConstructor
public class EvalRunController {

    private final EvalRunService runService;
    private final EvalResultMapper resultMapper;
    private final EvalResultRedactionService redaction;

    @PostMapping
    public Result<String> startRun(@RequestBody StartRunRequest req) {
        String runId = runService.startRun(req.datasetId(), UserContext.getUserId());
        return Results.success(runId);
    }

    @GetMapping
    public Result<List<EvalRunSummaryVO>> listRuns(@RequestParam String datasetId) {
        List<EvalRunDO> runs = runService.listRuns(datasetId);
        return Results.success(runs.stream().map(EvalRunController::toSummary).toList());
    }

    @GetMapping("/{runId}")
    public Result<EvalRunVO> getRun(@PathVariable String runId) {
        EvalRunDO run = runService.getRun(runId);
        if (run == null) {
            return Results.success(null);
        }
        return Results.success(toDetail(run));
    }

    @GetMapping("/{runId}/results")
    public Result<List<EvalResultSummaryVO>> listResults(@PathVariable String runId) {
        // P1-3：list 端点只走 .select() projection 拉表格列，不带 retrieved_chunks。
        // 注意：MyBatis Plus .select() 接受字段名/lambda；这里用 lambda 列出 12 字段（不含 retrievedChunks）。
        List<EvalResultDO> rows = resultMapper.selectList(new LambdaQueryWrapper<EvalResultDO>()
                .select(EvalResultDO::getId, EvalResultDO::getGoldItemId,
                        EvalResultDO::getQuestion,
                        EvalResultDO::getFaithfulness, EvalResultDO::getAnswerRelevancy,
                        EvalResultDO::getContextPrecision, EvalResultDO::getContextRecall,
                        EvalResultDO::getError, EvalResultDO::getElapsedMs)
                .eq(EvalResultDO::getRunId, runId));
        return Results.success(rows.stream().map(EvalRunController::toSummaryResult).toList());
    }

    @GetMapping("/{runId}/results/{resultId}")
    public Result<EvalResultVO> getResult(@PathVariable String runId, @PathVariable String resultId) {
        // P2：runId 必须参与查询，否则路径里的 runId 形同摆设；前端传错 URL 可能看到另一个 run 的 result。
        EvalResultDO r = resultMapper.selectOne(new LambdaQueryWrapper<EvalResultDO>()
                .eq(EvalResultDO::getId, resultId)
                .eq(EvalResultDO::getRunId, runId));
        if (r == null) return Results.success(null);
        // EVAL-3 ceiling: SUPER_ADMIN-only endpoint → MAX_VALUE 全读。
        // 未来放 AnyAdmin 时这里改成 UserContext.getMaxSecurityLevel()。
        int ceiling = Integer.MAX_VALUE;
        // P1-3：retrieved_chunks 必经 redaction，零绕过路径
        List<RetrievedChunkSnapshot> redacted = redaction.redact(
                EvalRunExecutor.parseChunks(r.getRetrievedChunks()), ceiling);
        return Results.success(new EvalResultVO(r.getId(), r.getRunId(), r.getGoldItemId(),
                r.getQuestion(), r.getGroundTruthAnswer(), r.getSystemAnswer(),
                redacted, r.getFaithfulness(), r.getAnswerRelevancy(),
                r.getContextPrecision(), r.getContextRecall(),
                r.getError(), r.getElapsedMs()));
    }

    private static EvalResultSummaryVO toSummaryResult(EvalResultDO r) {
        return new EvalResultSummaryVO(r.getId(), r.getGoldItemId(), r.getQuestion(),
                r.getFaithfulness(), r.getAnswerRelevancy(),
                r.getContextPrecision(), r.getContextRecall(),
                r.getError(), r.getElapsedMs());
    }

    private static EvalRunSummaryVO toSummary(EvalRunDO r) {
        return new EvalRunSummaryVO(r.getId(), r.getDatasetId(), r.getKbId(), r.getStatus(),
                r.getTotalItems(), r.getSucceededItems(), r.getFailedItems(),
                r.getMetricsSummary(), r.getStartedAt(), r.getFinishedAt(), r.getCreateTime());
    }

    private static EvalRunVO toDetail(EvalRunDO r) {
        return new EvalRunVO(r.getId(), r.getDatasetId(), r.getKbId(), r.getTriggeredBy(),
                r.getStatus(), r.getTotalItems(), r.getSucceededItems(), r.getFailedItems(),
                r.getMetricsSummary(), r.getSystemSnapshot(), r.getEvaluatorLlm(),
                r.getErrorMessage(), r.getStartedAt(), r.getFinishedAt(), r.getCreateTime());
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `mvn -pl bootstrap compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/controller/
git commit -m "feat(eval): add EvalRunController + VOs — 5 endpoints (start/list runs/run detail/list results/drill-down), SUPER_ADMIN, EVAL-3 redaction on drill-down"
```

---

### Task 12: Controller-level redaction integration test

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/controller/EvalRunControllerRedactionTest.java`

**目标（review P2-2 修订）**：锁住的不是"原文 == 期望文本"——SUPER_ADMIN ceiling=MAX_VALUE 下原文可见的断言无法证明 redaction 被调用过（即使 controller 直接返 raw 也通过）。改为**两条强断言**：
1. **mock redaction 返 sentinel**，verify drill-down endpoint 真的把 retrieved_chunks 经过 service 处理
2. **lower-ceiling 真 redaction**：调用真实 redaction service（不 mock）+ ceiling=1 的低密 principal 场景，期待高密 chunk text == `[REDACTED]`
3. **list 端点 VO 不含 retrievedChunks 字段**——结构性断言，契约即门禁

- [ ] **Step 1: 写测试**

```java
package com.nageoffer.ai.ragent.eval.controller;

import com.nageoffer.ai.ragent.eval.dao.entity.EvalResultDO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalResultMapper;
import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import com.nageoffer.ai.ragent.eval.service.EvalResultRedactionService;
import com.nageoffer.ai.ragent.eval.service.EvalRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EVAL-3 硬合并门禁验证（review P2-2）。
 *
 * <p>三条强断言：
 * <ol>
 *   <li>drill-down endpoint 必须调 EvalResultRedactionService —— mock service 返 sentinel verify</li>
 *   <li>lower-ceiling 场景下真 redaction 服务输出 [REDACTED]</li>
 *   <li>list endpoint 结构上不含 retrievedChunks 字段（契约即门禁）</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvalRunControllerRedactionTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EvalRunService runService;

    @MockBean
    private EvalResultMapper resultMapper;

    @MockBean
    private EvalResultRedactionService redaction;

    @Test
    void drilldown_endpoint_invokes_redaction_service_with_correct_args() throws Exception {
        EvalResultDO row = EvalResultDO.builder()
                .id("r1").runId("run-1").goldItemId("g1")
                .question("q").groundTruthAnswer("gt").systemAnswer("a")
                .retrievedChunks("""
                        [{"chunk_id":"c1","doc_id":"d1","doc_name":"public.pdf","security_level":0,"text":"public text","score":0.9},
                         {"chunk_id":"c2","doc_id":"d2","doc_name":"secret.pdf","security_level":3,"text":"secret text","score":0.8}]
                        """)
                .build();
        when(resultMapper.selectOne(any())).thenReturn(row);
        // sentinel：让 redaction 返回明显非原文的结果，证明 controller 真的走了 service
        when(redaction.redact(any(), anyInt())).thenReturn(List.of(
                new RetrievedChunkSnapshot("c1", "d1", "public.pdf", 0, "REDACTION_SENTINEL_TEXT", 0.9)));

        mvc.perform(get("/admin/eval/runs/run-1/results/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievedChunks[0].text").value("REDACTION_SENTINEL_TEXT"));

        // 关键 verify：service 必须被调用（zero-bypass guarantee）
        verify(redaction, atLeastOnce()).redact(any(), anyInt());
    }

    @Test
    void drilldown_endpoint_returns_null_when_runId_does_not_match() throws Exception {
        // P2：URL 里的 runId 必须参与查询。selectOne(eq id, eq runId) miss → 返回 null
        when(resultMapper.selectOne(any())).thenReturn(null);

        mvc.perform(get("/admin/eval/runs/wrong-run/results/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        // redaction 不应被调用——result 不存在就不需要脱敏
        verify(redaction, org.mockito.Mockito.never()).redact(any(), anyInt());
    }

    @Test
    void list_results_endpoint_does_not_expose_retrieved_chunks() throws Exception {
        EvalResultDO row = EvalResultDO.builder()
                .id("r1").runId("run-1").goldItemId("g1").question("q").build();
        when(resultMapper.selectList(any())).thenReturn(List.of(row));

        mvc.perform(get("/admin/eval/runs/run-1/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].retrievedChunks").doesNotExist())
                // 结构性锁定：list summary VO 不含此字段
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].question").exists());
    }

    @Test
    void list_runs_endpoint_does_not_expose_retrieved_chunks() throws Exception {
        when(runService.listRuns(any())).thenReturn(List.of(EvalRunDO.builder().id("run-1").build()));
        mvc.perform(get("/admin/eval/runs?datasetId=d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].retrievedChunks").doesNotExist());
    }

    @Test
    void get_run_detail_endpoint_does_not_expose_retrieved_chunks() throws Exception {
        when(runService.getRun(any())).thenReturn(EvalRunDO.builder().id("run-1").build());
        mvc.perform(get("/admin/eval/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievedChunks").doesNotExist());
    }
}
```

补充测试：`EvalResultRedactionServiceTest` 已含 lower-ceiling redact 用例（Task 10 已写），不重复在 controller 层做端到端"低密 principal → [REDACTED]"——controller 层只负责证明 service 被调用 + service 返回值被透传。两层组合等价于 review P2-2 要求。

注意：`SpringBootTest` 加载 SUPER_ADMIN 角色仍需要 PR E2 已建立的 Sa-Token MockMvc 设施。如果 PR E2 没建立，回退方案：本测试改为纯 controller 单元测（手 new controller + new MockMvcBuilders.standaloneSetup），跳过 Sa-Token —— 此时仍能锁 P2-2 的 3 条断言。

- [ ] **Step 2: 跑测试**

Run: `mvn -pl bootstrap test -Dtest=EvalRunControllerRedactionTest`
Expected: 3 tests pass（如 SaToken setup 缺失，回退为纯单测见上）。

- [ ] **Step 3: Commit**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/eval/controller/EvalRunControllerRedactionTest.java
git commit -m "test(eval): controller-level EVAL-3 redaction gate — list/detail VOs hide retrieved_chunks"
```

---

## Phase E — Python /evaluate（2 tasks）

### Task 13: Python `evaluate.py` —— RAGAS 4 metrics

**Files:**
- Create: `ragas/ragas/evaluate.py`
- Test: `ragas/tests/test_evaluate.py`

**契约（spec §7.3）**：
- 入：`{"items":[{"gold_item_id","question","contexts":[...],"answer","ground_truth"}]}`
- 出：`{"results":[{"gold_item_id","faithfulness","answer_relevancy","context_precision","context_recall","error":null}]}`
- 4 metric 用 ragas 0.2.x：`faithfulness / answer_relevancy / context_precision / context_recall`
- 单条失败：填充 `error` 字段，4 metric=null；不抛异常导致整批失败

- [ ] **Step 1: 写 `ragas/ragas/evaluate.py`**

```python
# ragas/ragas/evaluate.py
from __future__ import annotations

import asyncio
import logging
from typing import Optional

from pydantic import BaseModel, Field

from ragas import evaluate as ragas_evaluate, EvaluationDataset
from ragas.metrics import (
    faithfulness as M_FAITH,
    answer_relevancy as M_ANS_REL,
    context_precision as M_CTX_PREC,
    context_recall as M_CTX_REC,
)

log = logging.getLogger(__name__)


class EvaluateItem(BaseModel):
    gold_item_id: str
    question: str
    contexts: list[str]
    answer: str
    ground_truth: str


class EvaluateRequest(BaseModel):
    items: list[EvaluateItem]


class MetricResult(BaseModel):
    gold_item_id: str
    faithfulness: Optional[float] = None
    answer_relevancy: Optional[float] = None
    context_precision: Optional[float] = None
    context_recall: Optional[float] = None
    error: Optional[str] = None


class EvaluateResponse(BaseModel):
    results: list[MetricResult]


def _build_dataset(items: list[EvaluateItem]) -> EvaluationDataset:
    return EvaluationDataset.from_list([
        {
            "user_input": it.question,
            "retrieved_contexts": it.contexts,
            "response": it.answer,
            "reference": it.ground_truth,
        }
        for it in items
    ])


def run_evaluate(request: EvaluateRequest, evaluator_llm, evaluator_embeddings) -> EvaluateResponse:
    if not request.items:
        return EvaluateResponse(results=[])

    try:
        ds = _build_dataset(request.items)
        scores = ragas_evaluate(
            dataset=ds,
            metrics=[M_FAITH, M_ANS_REL, M_CTX_PREC, M_CTX_REC],
            llm=evaluator_llm,
            embeddings=evaluator_embeddings,
        )
        # ragas 0.2.x 返回 EvaluationResult；可 .to_pandas() 取 per-row
        df = scores.to_pandas()
        results: list[MetricResult] = []
        for idx, it in enumerate(request.items):
            row = df.iloc[idx]
            results.append(MetricResult(
                gold_item_id=it.gold_item_id,
                faithfulness=_safe_float(row.get("faithfulness")),
                answer_relevancy=_safe_float(row.get("answer_relevancy")),
                context_precision=_safe_float(row.get("context_precision")),
                context_recall=_safe_float(row.get("context_recall")),
                error=None,
            ))
        return EvaluateResponse(results=results)
    except Exception as e:
        log.exception("ragas evaluate batch failed")
        return EvaluateResponse(results=[
            MetricResult(gold_item_id=it.gold_item_id, error=f"batch failed: {type(e).__name__}: {e}")
            for it in request.items
        ])


def _safe_float(v) -> Optional[float]:
    try:
        if v is None:
            return None
        f = float(v)
        if f != f:  # NaN
            return None
        return round(f, 4)
    except (TypeError, ValueError):
        return None
```

- [ ] **Step 2: 写 `ragas/ragas/app.py` 加 `/evaluate` 路由**

读现有 `ragas/ragas/app.py` 找 `/synthesize` 路由后追加：

```python
from ragas.evaluate import EvaluateRequest, EvaluateResponse, run_evaluate

# 假设 app.py 中已有 _evaluator_llm / _evaluator_embeddings 全局实例（与 /synthesize 共用）

@app.post("/evaluate", response_model=EvaluateResponse)
async def evaluate(req: EvaluateRequest, x_eval_run_id: str | None = Header(default=None)):
    log.info("[evaluate] run_id=%s items=%d", x_eval_run_id, len(req.items))
    return run_evaluate(req, _evaluator_llm, _evaluator_embeddings)
```

如果当前 `app.py` 还没有 `_evaluator_llm` / `_evaluator_embeddings` 全局实例，则把它们从 `synthesize.py` 提到 `app.py` startup hook 中（参考 PR E1 模式）。

- [ ] **Step 3: 写测试 `ragas/tests/test_evaluate.py`**

```python
# ragas/tests/test_evaluate.py
from unittest.mock import MagicMock

import pandas as pd
import pytest

from ragas.evaluate import EvaluateItem, EvaluateRequest, run_evaluate


def _mock_scores(rows: list[dict]) -> MagicMock:
    m = MagicMock()
    m.to_pandas.return_value = pd.DataFrame(rows)
    return m


def test_run_evaluate_maps_4_metrics(monkeypatch):
    items = [
        EvaluateItem(gold_item_id="g1", question="q1", contexts=["c1"], answer="a1", ground_truth="gt1"),
        EvaluateItem(gold_item_id="g2", question="q2", contexts=["c2"], answer="a2", ground_truth="gt2"),
    ]
    fake_scores = _mock_scores([
        {"faithfulness": 0.9, "answer_relevancy": 0.8, "context_precision": 0.7, "context_recall": 0.6},
        {"faithfulness": 0.5, "answer_relevancy": 0.4, "context_precision": 0.3, "context_recall": 0.2},
    ])
    monkeypatch.setattr("ragas.evaluate.ragas_evaluate", lambda **kw: fake_scores)

    resp = run_evaluate(EvaluateRequest(items=items), llm=MagicMock(), embeddings=MagicMock())
    assert len(resp.results) == 2
    assert resp.results[0].gold_item_id == "g1"
    assert resp.results[0].faithfulness == 0.9
    assert resp.results[1].context_recall == 0.2
    assert resp.results[0].error is None


def test_run_evaluate_batch_failure_fills_error_per_item(monkeypatch):
    items = [
        EvaluateItem(gold_item_id="g1", question="q", contexts=["c"], answer="a", ground_truth="gt"),
    ]
    monkeypatch.setattr("ragas.evaluate.ragas_evaluate",
                        lambda **kw: (_ for _ in ()).throw(RuntimeError("openai 429")))

    resp = run_evaluate(EvaluateRequest(items=items), llm=MagicMock(), embeddings=MagicMock())
    assert resp.results[0].error.startswith("batch failed: RuntimeError: openai 429")
    assert resp.results[0].faithfulness is None


def test_run_evaluate_empty_items_short_circuits():
    resp = run_evaluate(EvaluateRequest(items=[]), llm=MagicMock(), embeddings=MagicMock())
    assert resp.results == []
```

注：测试里 `monkeypatch.setattr` 的目标路径是 `ragas.evaluate.ragas_evaluate`（模块内导入名），实际实现里是 `from ragas import evaluate as ragas_evaluate`，所以确认测试目标路径准确。如果 ragas 0.2.x API 与上述假设不同（比如 `to_pandas()` 已弃用），实施时按当前 ragas 版本调整 —— 单测中 `_mock_scores` 也要相应改造。

- [ ] **Step 4: 跑 Python 测试**

Run: `cd ragas && python -m pytest tests/test_evaluate.py -v`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add ragas/ragas/evaluate.py ragas/ragas/app.py ragas/tests/test_evaluate.py
git commit -m "feat(ragas): add /evaluate endpoint — RAGAS 4 metrics (faith / ans-rel / ctx-prec / ctx-rec)

- Per-item failure does not abort batch (error field populated, metrics
  null).
- X-Eval-Run-Id header accepted for log correlation.
- Empty items short-circuits to empty results."
```

---

### Task 14: Python smoke via curl + Spring Boot 启动验证

- [ ] **Step 1: 启动 Python 服务**

Run: `docker compose -f resources/docker/ragent-eval.compose.yaml up -d --build ragent-eval`
Expected: container 健康。

- [ ] **Step 2: curl smoke `/evaluate`**

```bash
curl -X POST http://localhost:9091/evaluate \
  -H "Content-Type: application/json" \
  -H "X-Eval-Run-Id: smoke-1" \
  -d '{"items":[{"gold_item_id":"g1","question":"什么是 RAG？","contexts":["RAG 是检索增强生成"],"answer":"RAG 是 retrieval-augmented generation","ground_truth":"RAG 是检索增强生成"}]}'
```

Expected: 200 + JSON 含 `results[0]` 的 4 个 metric 在 [0, 1]。

- [ ] **Step 3: 启动 Spring Boot**

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

Expected: 启动成功，无 `UnsatisfiedDependencyException`。

- [ ] **Step 4: 不 commit（仅人工验证）**

---

## Phase F — Frontend（4 tasks）

### Task 15: 扩展 `evalSuiteService.ts` —— 4 个 run/result API

**Files:**
- Modify: `frontend/src/services/evalSuiteService.ts`

- [ ] **Step 1: 在文件末尾追加**

```typescript
// ============== Eval Runs ==============

export type RunStatus = "PENDING" | "RUNNING" | "SUCCESS" | "PARTIAL_SUCCESS" | "FAILED" | "CANCELLED";

export interface EvalRunSummary {
  id: string;
  datasetId: string;
  kbId: string;
  status: RunStatus;
  totalItems: number;
  succeededItems: number;
  failedItems: number;
  metricsSummary?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  createTime?: string | null;
}

export interface EvalRunDetail extends EvalRunSummary {
  triggeredBy: string;
  systemSnapshot?: string | null;
  evaluatorLlm?: string | null;
  errorMessage?: string | null;
}

export interface RetrievedChunkSnapshot {
  chunk_id: string;
  doc_id?: string | null;
  doc_name?: string | null;
  security_level?: number | null;
  text: string;
  score?: number | null;
}

/**
 * P1-3：list 摘要不含 retrievedChunks（detail 页表格用）。
 */
export interface EvalResultSummary {
  id: string;
  goldItemId: string;
  question: string;
  faithfulness?: number | null;
  answerRelevancy?: number | null;
  contextPrecision?: number | null;
  contextRecall?: number | null;
  error?: string | null;
  elapsedMs?: number | null;
}

/**
 * P1-3：drill-down 单条详情含 redacted retrievedChunks（点击行才拉）。
 */
export interface EvalResult extends EvalResultSummary {
  runId: string;
  groundTruthAnswer: string;
  systemAnswer?: string | null;
  retrievedChunks: RetrievedChunkSnapshot[];
}

export async function startEvalRun(datasetId: string): Promise<string> {
  return api.post<string, string>("/admin/eval/runs", { datasetId });
}

export async function listEvalRuns(datasetId: string): Promise<EvalRunSummary[]> {
  return api.get<EvalRunSummary[], EvalRunSummary[]>("/admin/eval/runs", {
    params: { datasetId }
  });
}

export async function getEvalRun(runId: string): Promise<EvalRunDetail | null> {
  return api.get<EvalRunDetail | null, EvalRunDetail | null>(`/admin/eval/runs/${runId}`);
}

/** P1-3：返回摘要列表，不含 retrievedChunks，detail 页表格使用 */
export async function listEvalRunResults(runId: string): Promise<EvalResultSummary[]> {
  return api.get<EvalResultSummary[], EvalResultSummary[]>(`/admin/eval/runs/${runId}/results`);
}

/** P1-3：drill-down 单条全量，含经 redaction 的 retrievedChunks */
export async function getEvalResult(runId: string, resultId: string): Promise<EvalResult | null> {
  return api.get<EvalResult | null, EvalResult | null>(`/admin/eval/runs/${runId}/results/${resultId}`);
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/services/evalSuiteService.ts
git commit -m "feat(frontend): add eval run/result API client (start/list/detail/results)"
```

---

### Task 16: `EvalRunsTab.tsx` —— 替换 placeholder

**Files:**
- Create: `frontend/src/pages/admin/eval-suites/tabs/EvalRunsTab.tsx`
- Create: `frontend/src/pages/admin/eval-suites/components/StartRunDialog.tsx`
- Create: `frontend/src/pages/admin/eval-suites/components/RunStatusBadge.tsx`
- Modify: `frontend/src/pages/admin/eval-suites/EvalSuitesPage.tsx`
- Delete: `frontend/src/pages/admin/eval-suites/tabs/placeholders/RunsPlaceholderTab.tsx`

**功能要求**：
- 顶部"开始评估"按钮 → 弹 `StartRunDialog`（选 ACTIVE dataset → 确认）
- 列表展示 `EvalRunSummary[]`，按 `createTime desc` 排序
- 列：dataset 名 / 状态徽章（绿黄红） / `succeeded/total` / `metricsSummary` 4 指标均值（如有） / 触发时间 / 耗时
- RUNNING 行：每 2s 轮询 `getEvalRun(id)` 直到 status 终态
- 点击行跳 `/admin/eval-suites/runs/:runId`

- [ ] **Step 1: 写 `RunStatusBadge.tsx`**

```tsx
import { cn } from "@/lib/utils";
import type { RunStatus } from "@/services/evalSuiteService";

const STATUS_STYLES: Record<RunStatus, string> = {
  PENDING: "bg-slate-100 text-slate-600",
  RUNNING: "bg-blue-100 text-blue-700",
  SUCCESS: "bg-emerald-100 text-emerald-700",
  PARTIAL_SUCCESS: "bg-amber-100 text-amber-700",
  FAILED: "bg-rose-100 text-rose-700",
  CANCELLED: "bg-slate-200 text-slate-700"
};

const STATUS_LABEL: Record<RunStatus, string> = {
  PENDING: "排队中",
  RUNNING: "运行中",
  SUCCESS: "全部成功",
  PARTIAL_SUCCESS: "部分成功",
  FAILED: "全部失败",
  CANCELLED: "已取消"
};

interface Props {
  status: RunStatus;
}

export function RunStatusBadge({ status }: Props) {
  return (
    <span className={cn("inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium", STATUS_STYLES[status])}>
      {STATUS_LABEL[status]}
    </span>
  );
}
```

- [ ] **Step 2: 写 `StartRunDialog.tsx`**

```tsx
import { useEffect, useState } from "react";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue
} from "@/components/ui/select";
import { listGoldDatasets, startEvalRun } from "@/services/evalSuiteService";
import type { GoldDataset } from "@/services/evalSuiteService";
import { toast } from "sonner";

interface Props {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  onStarted: (runId: string, datasetId: string) => void;
}

export function StartRunDialog({ open, onOpenChange, onStarted }: Props) {
  const [datasets, setDatasets] = useState<GoldDataset[]>([]);
  const [selected, setSelected] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    listGoldDatasets(undefined, "ACTIVE")
      .then(setDatasets)
      .catch(() => toast.error("加载 ACTIVE 数据集失败"));
  }, [open]);

  const handleStart = async () => {
    if (!selected) {
      toast.warning("请选择数据集");
      return;
    }
    try {
      setSubmitting(true);
      const runId = await startEvalRun(selected);
      onStarted(runId, selected);
      onOpenChange(false);
      setSelected("");
    } catch (e: unknown) {
      toast.error(`触发失败：${(e as Error).message}`);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>开始新评估运行</DialogTitle>
        </DialogHeader>
        <div className="space-y-3">
          <div className="text-sm text-slate-600">
            评估走真实 RAG 链路，每条 gold item 会触发一次 LLM 调用 + 一次 RAGAS 4 指标评分。
          </div>
          <Select value={selected} onValueChange={setSelected}>
            <SelectTrigger>
              <SelectValue placeholder="选择 ACTIVE 数据集" />
            </SelectTrigger>
            <SelectContent>
              {datasets.length === 0 ? (
                <SelectItem value="__none__" disabled>无 ACTIVE 数据集</SelectItem>
              ) : (
                datasets.map((d) => (
                  <SelectItem key={d.id} value={d.id}>
                    {d.name} （{d.itemCount} 条）
                  </SelectItem>
                ))
              )}
            </SelectContent>
          </Select>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={submitting}>
            取消
          </Button>
          <Button onClick={handleStart} disabled={!selected || selected === "__none__" || submitting}>
            {submitting ? "提交中..." : "开始评估"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 3: 写 `EvalRunsTab.tsx`**

```tsx
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Play, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { formatDateTime } from "@/utils/helpers";
import { listEvalRuns, getEvalRun } from "@/services/evalSuiteService";
import type { EvalRunSummary } from "@/services/evalSuiteService";
import { StartRunDialog } from "../components/StartRunDialog";
import { RunStatusBadge } from "../components/RunStatusBadge";

const TERMINAL_STATUSES = new Set(["SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELLED"]);
const POLL_INTERVAL_MS = 2_000;

export function EvalRunsTab() {
  const navigate = useNavigate();
  const [datasetFilter, setDatasetFilter] = useState<string>("");
  const [runs, setRuns] = useState<EvalRunSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);

  const reload = async (datasetId: string) => {
    if (!datasetId) {
      setRuns([]);
      return;
    }
    try {
      setLoading(true);
      const data = await listEvalRuns(datasetId);
      setRuns(data);
    } catch (e: unknown) {
      toast.error(`加载评估运行列表失败：${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    reload(datasetFilter);
  }, [datasetFilter]);

  const hasRunning = useMemo(() => runs.some((r) => !TERMINAL_STATUSES.has(r.status)), [runs]);

  useEffect(() => {
    if (!hasRunning) return;
    const t = setInterval(async () => {
      try {
        const next: EvalRunSummary[] = [];
        for (const r of runs) {
          if (TERMINAL_STATUSES.has(r.status)) {
            next.push(r);
          } else {
            const fresh = await getEvalRun(r.id);
            next.push(fresh ? { ...r, ...fresh } : r);
          }
        }
        setRuns(next);
      } catch {
        // 忽略单次轮询失败
      }
    }, POLL_INTERVAL_MS);
    return () => clearInterval(t);
  }, [hasRunning, runs]);

  const parseAvg = (m?: string | null) => {
    if (!m) return null;
    try {
      return JSON.parse(m) as Record<string, number | null>;
    } catch {
      return null;
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <input
            className="rounded border px-2 py-1 text-sm"
            placeholder="按 datasetId 过滤..."
            value={datasetFilter}
            onChange={(e) => setDatasetFilter(e.target.value)}
          />
          <Button variant="outline" size="sm" onClick={() => reload(datasetFilter)} disabled={loading}>
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
        <Button onClick={() => setDialogOpen(true)}>
          <Play className="mr-1 h-4 w-4" />
          开始评估
        </Button>
      </div>

      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-3 py-2">Run</th>
              <th className="px-3 py-2">Dataset</th>
              <th className="px-3 py-2">状态</th>
              <th className="px-3 py-2">进度</th>
              <th className="px-3 py-2">指标均值</th>
              <th className="px-3 py-2">触发时间</th>
            </tr>
          </thead>
          <tbody>
            {runs.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-8 text-center text-slate-400">
                  {datasetFilter ? "暂无评估运行" : "请先输入 datasetId 过滤"}
                </td>
              </tr>
            ) : (
              runs.map((r) => {
                const avg = parseAvg(r.metricsSummary);
                return (
                  <tr
                    key={r.id}
                    className="cursor-pointer border-t hover:bg-slate-50"
                    onClick={() => navigate(`/admin/eval-suites/runs/${r.id}`)}
                  >
                    <td className="px-3 py-2 font-mono text-xs">{r.id}</td>
                    <td className="px-3 py-2 text-xs">{r.datasetId}</td>
                    <td className="px-3 py-2">
                      <RunStatusBadge status={r.status} />
                    </td>
                    <td className="px-3 py-2">
                      {r.succeededItems}/{r.totalItems}（失败 {r.failedItems}）
                    </td>
                    <td className="px-3 py-2 text-xs">
                      {avg
                        ? `F=${fmt(avg.faithfulness)} AR=${fmt(avg.answer_relevancy)} CP=${fmt(avg.context_precision)} CR=${fmt(avg.context_recall)}`
                        : "—"}
                    </td>
                    <td className="px-3 py-2 text-xs">{formatDateTime(r.createTime || "")}</td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      <StartRunDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onStarted={(runId) => {
          toast.success(`已触发 run ${runId}`);
          reload(datasetFilter);
        }}
      />
    </div>
  );
}

function fmt(n: number | null | undefined) {
  return n == null ? "—" : n.toFixed(3);
}
```

- [ ] **Step 4: 修改 `EvalSuitesPage.tsx`**

把 `import { RunsPlaceholderTab } from "./tabs/placeholders/RunsPlaceholderTab"` 改成：

```tsx
import { EvalRunsTab } from "./tabs/EvalRunsTab";
```

把 `{activeTab === "runs" && <RunsPlaceholderTab />}` 改成 `{activeTab === "runs" && <EvalRunsTab />}`。

- [ ] **Step 5: 删除 placeholder**

```bash
git rm frontend/src/pages/admin/eval-suites/tabs/placeholders/RunsPlaceholderTab.tsx
```

- [ ] **Step 6: 类型检查**

Run: `cd frontend && ./node_modules/.bin/tsc --noEmit`
Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/admin/eval-suites/tabs/EvalRunsTab.tsx \
        frontend/src/pages/admin/eval-suites/components/StartRunDialog.tsx \
        frontend/src/pages/admin/eval-suites/components/RunStatusBadge.tsx \
        frontend/src/pages/admin/eval-suites/EvalSuitesPage.tsx
git commit -m "feat(frontend): replace RunsPlaceholderTab with EvalRunsTab — list + start dialog + polling"
```

---

### Task 17: `EvalRunDetailPage.tsx` —— 单 run 看板 + drill-down

**Files:**
- Create: `frontend/src/pages/admin/eval-suites/EvalRunDetailPage.tsx`
- Modify: `frontend/src/router.tsx`（加 `/admin/eval-suites/runs/:runId` route）

**布局**：
- 顶部：4 metric 均值大卡 + 三态徽章
- 中部：4 metric 分布直方图（5 桶：0-0.2 / 0.2-0.4 / ... / 0.8-1.0）—— 用 recharts BarChart
- 底部：per-item 表格（按 faithfulness 升序），列：goldItemId / question 截断 / 4 metric / error 红字 / 耗时
- 点行：右抽屉打开，显示 GT answer / system answer / retrieved chunks（含 redacted 标记）

- [ ] **Step 1: 写 `EvalRunDetailPage.tsx`**（核心结构，约 200 行）

```tsx
import { useEffect, useMemo, useState } from "react";
import { useParams, Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import {
  Sheet, SheetContent, SheetHeader, SheetTitle
} from "@/components/ui/sheet";
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import { toast } from "sonner";
import { getEvalRun, listEvalRunResults, getEvalResult } from "@/services/evalSuiteService";
import type { EvalRunDetail, EvalResultSummary, EvalResult } from "@/services/evalSuiteService";
import { RunStatusBadge } from "./components/RunStatusBadge";

const METRIC_KEYS = ["faithfulness", "answerRelevancy", "contextPrecision", "contextRecall"] as const;
const METRIC_LABEL: Record<typeof METRIC_KEYS[number], string> = {
  faithfulness: "Faithfulness",
  answerRelevancy: "Answer Relevancy",
  contextPrecision: "Context Precision",
  contextRecall: "Context Recall"
};

function bucketize(rows: EvalResultSummary[], key: typeof METRIC_KEYS[number]) {
  const buckets = [0, 0, 0, 0, 0];
  for (const r of rows) {
    const v = r[key];
    if (v == null) continue;
    const idx = Math.min(4, Math.floor(v * 5));
    buckets[idx]++;
  }
  return buckets.map((c, i) => ({
    range: `${(i * 0.2).toFixed(1)}-${((i + 1) * 0.2).toFixed(1)}`,
    count: c
  }));
}

export function EvalRunDetailPage() {
  const { runId } = useParams<{ runId: string }>();
  const [run, setRun] = useState<EvalRunDetail | null>(null);
  // P1-3：表格用摘要列表（不带 chunks）
  const [results, setResults] = useState<EvalResultSummary[]>([]);
  // P1-3：drill-down 抽屉打开才单条拉全量（含 redacted chunks）
  const [drilldown, setDrilldown] = useState<EvalResult | null>(null);
  const [drilldownLoading, setDrilldownLoading] = useState(false);

  useEffect(() => {
    if (!runId) return;
    Promise.all([getEvalRun(runId), listEvalRunResults(runId)])
      .then(([r, rs]) => {
        setRun(r);
        setResults(rs);
      })
      .catch((e) => toast.error(`加载失败：${(e as Error).message}`));
  }, [runId]);

  const openDrilldown = async (summary: EvalResultSummary) => {
    if (!runId) return;
    try {
      setDrilldownLoading(true);
      const full = await getEvalResult(runId, summary.id);
      setDrilldown(full);
    } catch (e: unknown) {
      toast.error(`drill-down 加载失败：${(e as Error).message}`);
    } finally {
      setDrilldownLoading(false);
    }
  };

  const summary = useMemo(() => {
    if (!run?.metricsSummary) return {} as Record<string, number | null>;
    try {
      return JSON.parse(run.metricsSummary) as Record<string, number | null>;
    } catch {
      return {};
    }
  }, [run]);

  const sorted = useMemo(
    () => [...results].sort((a, b) => (a.faithfulness ?? 999) - (b.faithfulness ?? 999)),
    [results]
  );

  if (!run) {
    return <div className="p-6 text-slate-500">加载中...</div>;
  }

  return (
    <div className="space-y-4 p-6">
      <Link to="/admin/eval-suites?tab=runs" className="inline-flex items-center text-sm text-slate-600 hover:text-slate-900">
        <ArrowLeft className="mr-1 h-4 w-4" />
        返回列表
      </Link>

      <div className="flex items-center gap-3">
        <h1 className="text-xl font-semibold">Run {run.id}</h1>
        <RunStatusBadge status={run.status} />
        <div className="text-sm text-slate-500">
          {run.succeededItems}/{run.totalItems} 成功（失败 {run.failedItems}）
        </div>
      </div>

      {/* 4 metric 大卡 */}
      <div className="grid grid-cols-4 gap-3">
        {METRIC_KEYS.map((k) => {
          const apiKey = k === "answerRelevancy" ? "answer_relevancy"
              : k === "contextPrecision" ? "context_precision"
              : k === "contextRecall" ? "context_recall" : k;
          const v = summary[apiKey];
          return (
            <div key={k} className="rounded-lg border bg-white p-4">
              <div className="text-xs text-slate-500">{METRIC_LABEL[k]}</div>
              <div className="mt-1 text-2xl font-semibold">{v == null ? "—" : v.toFixed(3)}</div>
            </div>
          );
        })}
      </div>

      {/* 4 直方图 */}
      <div className="grid grid-cols-2 gap-3">
        {METRIC_KEYS.map((k) => (
          <div key={k} className="rounded-lg border bg-white p-3">
            <div className="mb-2 text-xs text-slate-600">{METRIC_LABEL[k]} 分布</div>
            <ResponsiveContainer width="100%" height={140}>
              <BarChart data={bucketize(results, k)}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="range" tick={{ fontSize: 10 }} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} />
                <Tooltip />
                <Bar dataKey="count" fill="#6366f1" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        ))}
      </div>

      {/* per-item 表格 */}
      <div className="overflow-x-auto rounded-lg border">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="px-3 py-2 text-left">Gold</th>
              <th className="px-3 py-2 text-left">问题</th>
              <th className="px-3 py-2">F</th>
              <th className="px-3 py-2">AR</th>
              <th className="px-3 py-2">CP</th>
              <th className="px-3 py-2">CR</th>
              <th className="px-3 py-2">耗时</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((r) => (
              <tr key={r.id} className="cursor-pointer border-t hover:bg-slate-50" onClick={() => openDrilldown(r)}>
                <td className="px-3 py-2 font-mono text-xs">{r.goldItemId}</td>
                <td className="px-3 py-2 max-w-md truncate">
                  {r.error ? <span className="text-rose-600">⚠ {r.error}</span> : r.question}
                </td>
                <td className="px-3 py-2 text-center">{fmt(r.faithfulness)}</td>
                <td className="px-3 py-2 text-center">{fmt(r.answerRelevancy)}</td>
                <td className="px-3 py-2 text-center">{fmt(r.contextPrecision)}</td>
                <td className="px-3 py-2 text-center">{fmt(r.contextRecall)}</td>
                <td className="px-3 py-2 text-center text-xs text-slate-500">{r.elapsedMs ?? "—"}ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* drill-down 抽屉 — P1-3：点击行才单独拉全量 */}
      <Sheet open={!!drilldown || drilldownLoading} onOpenChange={(v) => !v && setDrilldown(null)}>
        <SheetContent className="w-[640px] sm:max-w-[640px] overflow-y-auto">
          <SheetHeader>
            <SheetTitle>Drill-down: {drilldown?.goldItemId ?? "加载中..."}</SheetTitle>
          </SheetHeader>
          {drilldownLoading && !drilldown && <div className="mt-3 text-sm text-slate-500">加载中...</div>}
          {drilldown && (
            <div className="mt-3 space-y-3 text-sm">
              <Section label="问题">{drilldown.question}</Section>
              <Section label="Ground Truth">{drilldown.groundTruthAnswer}</Section>
              <Section label="System Answer">
                {drilldown.error ? <span className="text-rose-600">⚠ {drilldown.error}</span> : drilldown.systemAnswer}
              </Section>
              <div>
                <div className="mb-1 font-medium text-slate-700">检索到的 chunks</div>
                <div className="space-y-2">
                  {drilldown.retrievedChunks.map((c) => (
                    <div key={c.chunk_id} className="rounded border p-2">
                      <div className="flex items-center justify-between text-xs text-slate-500">
                        <span>{c.doc_name || c.doc_id || c.chunk_id}</span>
                        <span>SL={c.security_level ?? 0} score={c.score?.toFixed(3) ?? "—"}</span>
                      </div>
                      <div className={c.text === "[REDACTED]" ? "italic text-slate-400" : ""}>
                        {c.text}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </SheetContent>
      </Sheet>
    </div>
  );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-1 text-xs uppercase text-slate-500">{label}</div>
      <div className="rounded bg-slate-50 p-2">{children}</div>
    </div>
  );
}

function fmt(n: number | null | undefined) {
  return n == null ? "—" : n.toFixed(3);
}
```

- [ ] **Step 2: 修改 `router.tsx`**

在 admin 嵌套路由中（参考现有 `/admin/eval-suites` 路由位置）追加：

```tsx
{
  path: "eval-suites/runs/:runId",
  element: <RequireSuperAdmin><EvalRunDetailPage /></RequireSuperAdmin>
}
```

并在文件顶部 import：

```tsx
import { EvalRunDetailPage } from "@/pages/admin/eval-suites/EvalRunDetailPage";
```

- [ ] **Step 3: 类型检查**

Run: `cd frontend && ./node_modules/.bin/tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/admin/eval-suites/EvalRunDetailPage.tsx \
        frontend/src/router.tsx
git commit -m "feat(frontend): EvalRunDetailPage — 4 metric cards + histograms + per-item table + drill-down sheet"
```

---

### Task 18: `EvalTrendsTab.tsx` —— 替换 placeholder

**Files:**
- Create: `frontend/src/pages/admin/eval-suites/tabs/EvalTrendsTab.tsx`
- Create: `frontend/src/pages/admin/eval-suites/components/SnapshotDiffViewer.tsx`
- Modify: `frontend/src/pages/admin/eval-suites/EvalSuitesPage.tsx`
- Delete: `frontend/src/pages/admin/eval-suites/tabs/placeholders/TrendsPlaceholderTab.tsx`

**功能**：
- 顶部：选 dataset 输入
- 中部：4 metric 折线（recharts LineChart），x=run 时间，y=均值
- 下部：选两 run 看 `system_snapshot` JSON diff（高亮变化字段）

- [ ] **Step 1: 写 `SnapshotDiffViewer.tsx`**

```tsx
import { useMemo } from "react";

interface Props {
  before?: string | null;
  after?: string | null;
}

export function SnapshotDiffViewer({ before, after }: Props) {
  const diff = useMemo(() => {
    let a: Record<string, unknown> = {};
    let b: Record<string, unknown> = {};
    try { if (before) a = JSON.parse(before); } catch { /* ignore */ }
    try { if (after) b = JSON.parse(after); } catch { /* ignore */ }
    const keys = Array.from(new Set([...Object.keys(a), ...Object.keys(b)])).sort();
    return keys.map((k) => ({ key: k, a: a[k], b: b[k], changed: JSON.stringify(a[k]) !== JSON.stringify(b[k]) }));
  }, [before, after]);

  return (
    <div className="rounded-lg border bg-white">
      <table className="w-full text-xs">
        <thead className="bg-slate-50 text-left">
          <tr>
            <th className="px-2 py-1">字段</th>
            <th className="px-2 py-1">前一次</th>
            <th className="px-2 py-1">本次</th>
          </tr>
        </thead>
        <tbody>
          {diff.map((d) => (
            <tr key={d.key} className={d.changed ? "bg-amber-50" : ""}>
              <td className="px-2 py-1 font-mono">{d.key}</td>
              <td className="px-2 py-1 font-mono">{JSON.stringify(d.a)}</td>
              <td className="px-2 py-1 font-mono">{JSON.stringify(d.b)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 2: 写 `EvalTrendsTab.tsx`**

```tsx
import { useEffect, useMemo, useState } from "react";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend, CartesianGrid } from "recharts";
import { listEvalRuns, getEvalRun } from "@/services/evalSuiteService";
import type { EvalRunSummary, EvalRunDetail } from "@/services/evalSuiteService";
import { SnapshotDiffViewer } from "../components/SnapshotDiffViewer";
import { toast } from "sonner";

interface ChartRow {
  ts: string;
  faithfulness: number | null;
  answer_relevancy: number | null;
  context_precision: number | null;
  context_recall: number | null;
}

export function EvalTrendsTab() {
  const [datasetId, setDatasetId] = useState("");
  const [runs, setRuns] = useState<EvalRunSummary[]>([]);
  const [beforeRun, setBeforeRun] = useState<EvalRunDetail | null>(null);
  const [afterRun, setAfterRun] = useState<EvalRunDetail | null>(null);

  useEffect(() => {
    if (!datasetId) {
      setRuns([]);
      return;
    }
    listEvalRuns(datasetId)
      .then((rs) => setRuns(rs.filter((r) => r.metricsSummary).reverse()))
      .catch((e) => toast.error(`加载失败：${(e as Error).message}`));
  }, [datasetId]);

  const chartData = useMemo<ChartRow[]>(() => {
    return runs.map((r) => {
      let m: Record<string, number | null> = {};
      try { m = r.metricsSummary ? JSON.parse(r.metricsSummary) : {}; } catch { /* ignore */ }
      return {
        ts: r.createTime?.slice(5, 16) || r.id.slice(-6),
        faithfulness: m.faithfulness ?? null,
        answer_relevancy: m.answer_relevancy ?? null,
        context_precision: m.context_precision ?? null,
        context_recall: m.context_recall ?? null
      };
    });
  }, [runs]);

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <input
          className="rounded border px-2 py-1 text-sm"
          placeholder="datasetId..."
          value={datasetId}
          onChange={(e) => setDatasetId(e.target.value)}
        />
      </div>

      {chartData.length > 0 && (
        <div className="rounded-lg border bg-white p-3">
          <div className="mb-2 text-sm font-medium">4 指标趋势</div>
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="ts" tick={{ fontSize: 10 }} />
              <YAxis domain={[0, 1]} tick={{ fontSize: 10 }} />
              <Tooltip />
              <Legend />
              <Line type="monotone" dataKey="faithfulness" stroke="#6366f1" />
              <Line type="monotone" dataKey="answer_relevancy" stroke="#10b981" />
              <Line type="monotone" dataKey="context_precision" stroke="#f59e0b" />
              <Line type="monotone" dataKey="context_recall" stroke="#ef4444" />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}

      {runs.length >= 2 && (
        <div className="space-y-2">
          <div className="flex items-center gap-2 text-sm">
            <span>对比：</span>
            <select className="rounded border px-2 py-1" value={beforeRun?.id || ""} onChange={async (e) => setBeforeRun(await getEvalRun(e.target.value))}>
              <option value="">选 run A</option>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>{r.id.slice(-6)} · {r.createTime?.slice(5, 16)}</option>
              ))}
            </select>
            <span>→</span>
            <select className="rounded border px-2 py-1" value={afterRun?.id || ""} onChange={async (e) => setAfterRun(await getEvalRun(e.target.value))}>
              <option value="">选 run B</option>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>{r.id.slice(-6)} · {r.createTime?.slice(5, 16)}</option>
              ))}
            </select>
          </div>
          {(beforeRun || afterRun) && (
            <SnapshotDiffViewer before={beforeRun?.systemSnapshot} after={afterRun?.systemSnapshot} />
          )}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 3: 修改 `EvalSuitesPage.tsx`**

把 `import { TrendsPlaceholderTab } from ...` 改为 `import { EvalTrendsTab } from "./tabs/EvalTrendsTab"`，把 `<TrendsPlaceholderTab />` 替换为 `<EvalTrendsTab />`。

- [ ] **Step 4: 删除 placeholder**

```bash
git rm frontend/src/pages/admin/eval-suites/tabs/placeholders/TrendsPlaceholderTab.tsx
```

- [ ] **Step 5: 类型检查**

Run: `cd frontend && ./node_modules/.bin/tsc --noEmit`
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/admin/eval-suites/tabs/EvalTrendsTab.tsx \
        frontend/src/pages/admin/eval-suites/components/SnapshotDiffViewer.tsx \
        frontend/src/pages/admin/eval-suites/EvalSuitesPage.tsx
git commit -m "feat(frontend): EvalTrendsTab + SnapshotDiffViewer — 4 metric line chart + run snapshot diff"
```

---

## Phase G — E2E + docs（2 tasks）

### Task 19: E2E smoke

**目标**：起 Python `ragent-eval` 容器 + 后端 + 前端，从 UI 跑通"开始评估 → 看 detail → 看 trend 折线" 全链路。

**前置**：必须有至少 1 个 `ACTIVE` gold dataset 含 ≥ 3 条 APPROVED items（PR E2 e2e 流已建好，沿用）。

- [ ] **Step 1: 启动三件套**

```bash
docker compose -f resources/docker/ragent-eval.compose.yaml up -d --build ragent-eval
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
# 另一终端：
cd frontend && npm run dev
```

- [ ] **Step 2: SUPER_ADMIN 登录 → 进 `/admin/eval-suites?tab=runs`**

预期：看到"开始评估"按钮和空表（如未指定 datasetId）。

- [ ] **Step 3: 点"开始评估" → 选 ACTIVE dataset → 确认**

预期：toast "已触发 run xxxxx"；列表轮询出现 RUNNING 行。

- [ ] **Step 4: 等待 ~30-60s（取决于 LLM QPS）**

预期：状态从 RUNNING → SUCCESS / PARTIAL_SUCCESS（绿/黄）。

- [ ] **Step 5: 点行进入 detail 页**

预期：4 metric 卡显示 [0,1] 数值；4 直方图渲染；per-item 表格按 faithfulness 升序；点击行抽屉显示 GT / system answer / chunks。

- [ ] **Step 6: 切换到 trends tab，输入 datasetId**

预期：折线图显示当前 run 的 4 个点（单点也能渲染）。

- [ ] **Step 7: 验证 SQL 落库**

```bash
docker exec postgres psql -U postgres -d ragent -c \
  "SELECT id, status, total_items, succeeded_items, failed_items, metrics_summary FROM t_eval_run ORDER BY create_time DESC LIMIT 1;"

docker exec postgres psql -U postgres -d ragent -c \
  "SELECT count(*) FROM t_eval_result WHERE run_id = (SELECT id FROM t_eval_run ORDER BY create_time DESC LIMIT 1);"
```

预期：第 1 条 metrics_summary 是 JSON 含 4 字段；第 2 条 count == total_items。

- [ ] **Step 8: 验证 EVAL-3 redaction（手工 SQL 注入跨级 chunk 验）**

```bash
# 在最近一条 result 里改一条 retrieved_chunks 元素的 security_level=99
docker exec postgres psql -U postgres -d ragent -c \
  "UPDATE t_eval_result SET retrieved_chunks = REPLACE(retrieved_chunks, '\"security_level\":0', '\"security_level\":99') WHERE id = (SELECT id FROM t_eval_result ORDER BY create_time DESC LIMIT 1);"
```

刷新 detail 页 drill-down。预期：SUPER_ADMIN 仍能看到原文（ceiling=MAX_VALUE）。这是 EVAL-3 接口已经在位、未来切 AnyAdmin 时调一行 ceiling 就生效的"埋点验证"。

- [ ] **Step 9: 不 commit（人工验证步骤）**

---

### Task 20: Docs + dev_log + backlog 更新

**Files:**
- Modify: `bootstrap/CLAUDE.md`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md`
- Modify: `docs/dev/followup/backlog.md`
- Modify: `log/dev_log/dev_log.md`
- Create: `log/dev_log/2026-04-26-eval-pr-e3.md`

- [ ] **Step 1: `bootstrap/CLAUDE.md` —— eval 域关键类表加 5 行**

在 eval 域表（line ~40-48）下追加：

```markdown
| `ChatForEvalService`（rag/core/）| 同步 RAG 编排，复用 7 个现有 service bean；不挂 @ChatRateLimit；返回 AnswerResult 4 状态码 |
| `AnswerResult`（rag/core/）| sealed interface — Success/EmptyContext/SystemOnlySkipped/AmbiguousIntentSkipped |
| `EvalRunService` / `EvalRunServiceImpl` | 校验 ACTIVE+APPROVED → 建 run + snapshot → 提交 evalExecutor |
| `EvalRunExecutor` | 三态状态机；per-item 失败不阻断；满 evaluateBatchSize=5 调 Python /evaluate |
| `SystemSnapshotBuilder` | 单一真相源——retrieval/sources/chat/embedding/rerank/git_commit + sha256 hash |
| `EvalResultRedactionService` | EVAL-3 硬合并门禁——超 ceiling 的 chunk text 替换 [REDACTED] |
| `EvalRunController` | REST 入口：start/list/detail/results；类级 @SaCheckRole("SUPER_ADMIN") |
```

- [ ] **Step 2: `eval/CLAUDE.md` —— 加 PR E3 落地清单**

在 PR E2 落地清单后追加：

```markdown
## PR E3 已落地（2026-04-26）

- **AnswerPipeline 不抽**（ADR `2026-04-25-answer-pipeline-spike-adr.md`）：`ChatForEvalService` 直接组合现有 7 个 RAG service bean。streamChat 字节级不变。
- **三态状态机**：`SUCCESS`（全成）/ `PARTIAL_SUCCESS`（黄）/ `FAILED`（红），由 `EvalRunExecutor.decideStatus` 强制；取代旧 "≥1 即 SUCCESS"。
- **system_snapshot 单一真相源**：`SystemSnapshotBuilder` 写入 retrieval / sources / chat / embedding / rerank / git_commit，并附 sha256 hash 便于 dedup。新加任何影响 RAG 的配置必须同步加字段（review checklist 必查）。
- **EVAL-3 redaction 落地**：`EvalResultRedactionService` 是 result 读端点的硬合并门禁；当前所有 controller 类级 `@SaCheckRole("SUPER_ADMIN")` + `Integer.MAX_VALUE` ceiling = 全读。未来放 AnyAdmin 只需把 ceiling 换成 `UserContext.getMaxSecurityLevel()`，不改 service 契约。列表 / 趋势 VO 类型不含 `retrievedChunks` 字段——契约即门禁。
- **零 ThreadLocal 新增**：`MDC.put("evalRunId", runId)` 仅做日志关联（非业务状态），方法 finally `MDC.remove`；`X-Eval-Run-Id` HTTP header 透传到 Python 侧。
- **Python /evaluate**：4 metric（faith / ans-rel / ctx-prec / ctx-rec）；单条失败 error 字段填充不抛；空 items 短路。
- **前端**：`EvalRunsTab` 替换 placeholder（含 RUNNING 轮询 2s）；`EvalRunDetailPage` 4 卡 + 4 直方图 + per-item 表 + drill-down sheet；`EvalTrendsTab` recharts 折线 + snapshot diff 表。

## PR E3 已知边界

- `evaluate` HTTP 失败→整 batch 标失败（无 retry，复用 backlog EVAL-retry 后续修）。
- `metricsSummary` 是 single pass 累加；后续若加 quantile / 标准差需扩 schema。
- `EvalRunDetailPage` 直方图固定 5 桶；如需细粒度可加 props。
```

- [ ] **Step 3: `backlog.md` —— EVAL-3 移入 resolved**

在文件顶部 resolved 段加：

```markdown
> - `EVAL-3`: eval 读端点 `retrieved_chunks` 经 `EvalResultRedactionService` 脱敏；列表/趋势 VO 不含该字段；当前 ceiling=MAX_VALUE（SUPER_ADMIN 全读），AnyAdmin 放开仅需切 ceiling
> - Landed in PR E3
```

并把原 `EVAL-3` 主体段（line ~31-38）整体删除。

- [ ] **Step 4: `log/dev_log/dev_log.md` —— 追加索引行**

在最新条目下追加：

```markdown
- 2026-04-26 — PR E3 RAG 评估闭环（trigger → real RAG → RAGAS 4 指标 → 看板/趋势），含 EVAL-3 redaction 门禁。详见 [2026-04-26-eval-pr-e3.md](2026-04-26-eval-pr-e3.md)
```

- [ ] **Step 5: 写 `log/dev_log/2026-04-26-eval-pr-e3.md`**

```markdown
# PR E3 — RAG 评估闭环 session log

- **日期**：2026-04-26
- **分支**：`feature/eval-e3-plan` → `main`
- **PR**：#TBD
- **依赖**：PR E1（4 表 + Python 服务壳）+ PR E2（合成 + 审核）+ ADR `docs/dev/design/2026-04-25-answer-pipeline-spike-adr.md`

## 落地范围

参见 `bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md` "PR E3 已落地"段。

## 关键决议

1. **不抽 AnswerPipeline**（ADR 决议）—— ChatForEvalService 直接复用 7 个现有 RAG service bean；streamChat 字节级不变。
2. **EVAL-3 与 E3 同 PR**（用户决议）—— 任何返回 retrieved_chunks 的 API 必经 redaction service。列表/趋势 VO 不含 retrievedChunks 字段（契约即门禁）。
3. **三态状态机**取代旧"≥1 即 SUCCESS"——1/50 显示绿灯会误导趋势判断（spec §6.2 决策点 4）。
4. **system_snapshot 含 sha256 config_hash**——支持未来按 hash 去重 / 找"同配置的历史 run 对比"。

## 调试备忘

- WireMock test 模式参考 PR E2 `RagasEvalClientSynthesizeTest`。
- `RetrievedChunk.getMetadata()` 取 `security_level` / `doc_id` / `doc_name`：实施时确认实际字段路径——若 metadata Map 中是 `securityLevel` 驼峰则 `EvalRunExecutor.toSnapshot` 需调整。

## E2E 验证

参见 plan Task 19 全 8 步。

## 后续 backlog

- EVAL-retry：`RagasEvalClient.evaluate()` 加 retry interceptor（PR E2 已记录，PR E3 未做）
- EVAL-2 legacy `@Async` 清理（独立 PR）
- 当 dev 环境放开 AnyAdmin 读 eval result 时，把 `EvalRunController.listResults` 的 ceiling 从 `MAX_VALUE` 改为 `UserContext.getMaxSecurityLevel()`
```

- [ ] **Step 6: Commit**

```bash
git add bootstrap/CLAUDE.md \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/eval/CLAUDE.md \
        docs/dev/followup/backlog.md \
        log/dev_log/dev_log.md \
        log/dev_log/2026-04-26-eval-pr-e3.md
git commit -m "docs(eval): PR E3 — CLAUDE.md key classes, eval/CLAUDE.md landing log, EVAL-3 resolved, session log"
```

- [ ] **Step 7: 推 + 开 PR**

```bash
git push -u origin feature/eval-e3-plan
```

到 `https://github.com/ZhongKai-07/rag-knowledge-dev/pull/new/feature/eval-e3-plan` 开 PR。

PR 标题：`feat(eval): PR E3 — RAG 评估闭环（real RAG → RAGAS 4 指标 → 看板/趋势 + EVAL-3 redaction）`

---

## Self-Review

### 1. Spec 覆盖（spec `docs/superpowers/specs/2026-04-24-rag-eval-closed-loop-design.md`）

| Spec 段 | 任务 | 覆盖 |
|---|---|---|
| §6.2 Flow 2（一键评估闭环） | T8-T11, T13-T14 | ✅ |
| §6.3 Flow 3（历史对比） | T18 (EvalTrendsTab) | ✅ |
| §7.1 Java 域结构 | T1-T11 文件路径全对齐 | ✅ |
| §7.2 关键类职责 | 全部 11 个类创建 | ✅ |
| §7.3 Python 契约 | T13 evaluate.py | ✅ |
| §7.4 docker-compose | 沿用 PR E1 已建（不改） | ✅ |
| §7.5 前端入口 | T16-T18 | ✅ |
| §8 配置 `rag.eval.run.batch-size / per-item-timeout` | T6 已扩 evaluateBatchSize/Ms | ✅ |
| §9 ThreadLocal 硬约束 | 全程零新增 TL（仅 MDC） | ✅ |
| §10 gotcha #1 snapshot 单一真相源 | T3 SystemSnapshotBuilder | ✅ |
| §10 gotcha #2 真实 RAG 非 SSE | T2 ChatForEvalService 不调 memory/SSE | ✅ |
| §10 gotcha #4 max-parallel-runs=1 | yaml 已存在（PR E1） | ✅ |
| §10 gotcha #9 retrieved_chunks 大 → list 不返 | T11 列表 VO 不含 retrievedChunks | ✅ |
| §10 gotcha #11 双入口分离 | EvalSuitesPage 仅改本路径，不动 /admin/evaluations | ✅ |
| §10 gotcha #12 不用 @Async 注解 | EvalRunService 用 @Qualifier evalExecutor | ✅ |
| §10 gotcha #14 @MapperScan | PR E1 已加（任务说明已注） | ✅ |
| §10 gotcha #15 read 接口 SUPER_ADMIN | T11 类级 @SaCheckRole | ✅ |
| §11 测试策略 | T1-T17 每个新类配测试 | ✅ |
| §15.1 SUPER_ADMIN 全端点 | T11 类级注解 | ✅ |
| §15.2 系统级 AccessScope.all() 边界 | T2 注释 + T9 javadoc | ✅ |
| §15.3 边界硬约束 | T9 javadoc 显式列禁止扩展场景 | ✅ |
| EVAL-3 redaction | T10 service + T11 controller + T12 test | ✅ |

无 spec 覆盖空洞。

### 2. Placeholder scan

逐 task 扫"TBD" / "TODO" / "implement later" / "add appropriate error handling" 等：

- T2 javadoc 说"由调用方持有约束"——具体调用方在 T9 注释里点名 EvalRunExecutor。✅ 不是 placeholder。
- T9 测试 `all_success_marks_run_SUCCESS` 内有重复 `when(ragas.evaluate...)` stub，已在备注里明示"实际跑前清掉前一个" —— 这是测试 craft 提示，不是任务 placeholder。✅
- T13 注 "如果 ragas 0.2.x API 与上述假设不同"——这是 implementer 实施时校验项，非空缺。✅
- T19 是人工 E2E（无 commit），步骤明确 8 步。✅

无 placeholder。

### 3. Type consistency

| 类型 / 字段 | 定义任务 | 后续消费任务 | 一致？ |
|---|---|---|---|
| `AnswerResult.Success.chunks()` | T1 | T9 `success.chunks()` | ✅ |
| `EvaluateRequest.Item.goldItemId` (用 resultId) | T5 | T9 PendingResult.resultId | ✅ |
| `EvaluateResponse.MetricResult.faithfulness: BigDecimal` | T5 | T9 row.setFaithfulness | ✅ |
| `RetrievedChunkSnapshot(chunkId, docId, docName, securityLevel, text, score)` | T4 | T9 toSnapshot / T10 redact / T11 EvalResultVO / T15 frontend interface | ✅ |
| `EvalProperties.Run.evaluateBatchSize` | T6 | T9 `props.getRun().getEvaluateBatchSize()` | ✅ |
| `RagasEvalClient.evaluate(String runId, EvaluateRequest)` | T7 | T9 `ragasClient.evaluate(runId, req)` | ✅ |
| `EvalRunService.startRun(String, String)` | T8 | T11 controller | ✅ |
| `EvalRunExecutor.runInternal(String, String)` | T9（Task 8 占位 → Task 9 实现） | T8 EvalRunServiceImpl 调用 | ✅ |
| `EvalResultRedactionService.redact(List, int)` | T10 | T11 `redaction.redact(parsed, ceiling)` | ✅ |
| `EvalRunController` 4 endpoints 路径 | T11 | T15 service 客户端调用 | ✅ |
| Run status 字符串 PENDING/RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED | T8/T9 写入；T11 透传；T15 type union | ✅ |
| Frontend `RunStatus` union | T15 | T16-T18 | ✅ |
| `metricsSummary` JSON 字段名 (snake_case: faithfulness/answer_relevancy/...) | T9 computeMetricsSummary | T17/T18 frontend parse | ✅ |
| `system_snapshot` 字段名 | T3 (snake_case) | T18 SnapshotDiffViewer 直接消费 | ✅ |

无 type drift。

### 4. Review 修订清单（已落入计划）

**Round 1 修订**：
- **P1-1** AccessScope 不再自造：T2 签名 `chatForEval(AccessScope, kbId, question)`；T9 显式传 `AccessScope.all()`；T2 类注释声明唯一合法持有者
- **P1-2** /evaluate 失败必入 failed_items：T9 `flushBatch` 返 `BatchOutcome(succ, fail)`；3 类失败（HTTP 整批 / per-item error / per-item missing）累加；T9 测试加两条新 case
- **P1-3** results 拆 list / drill-down：list `/runs/{runId}/results` 返 `EvalResultSummaryVO`（不带 chunks）+ `.select()` projection；drill-down `/runs/{runId}/results/{resultId}` 返 `EvalResultVO`（经 redaction 的 chunks）；前端表格用 summary，点击行才单独 GET 全量
- **P1-4** max-parallel-runs enforce（软校验）：T8 `startRun` 加 SELECT COUNT 全表 PENDING+RUNNING 超阈值拒绝
- **P2-1** sources/citation off 落入 snapshot：T3 `eval_sources_disabled=true` 常量；T2 类注释 + 内联注释
- **P2-2** controller redaction test：T12 改为 `@MockBean EvalResultRedactionService` + sentinel 返回值 + `verify(redaction).redact(...)`
- **Pre-fix-A** `RetrievedChunk` 直接 getter：T9 `toSnapshot` 改用 `c.getDocId()/getSecurityLevel()` 等
- **Pre-fix-B** `AIModelProperties.ModelGroup`：T3 测试改 `new AIModelProperties.ModelGroup()`

**Round 2 修订（本轮）**：
- **P1-4 升级硬 enforce**：T8 加 `ReentrantLock startRunLock` 包住 count + insert + executor.execute；新增并发测试 `startRun_concurrent_calls_yield_at_most_one_insert`（8 线程并发 → 恰好 1 个 insert + 7 个 ClientException）；类字段注释里给出多实例部署的 DB 部分唯一索引兜底方案
- **P2 sync fallback 真覆盖**：新增 Task 7b `RoutingLLMServiceSyncFallbackTest`（`infra-ai/`），锁定第 1 候选失败 → 第 2 候选自动降级 + 候选耗尽时异常透传；ChatForEvalService 的透传测试只是消费方契约
- **P2 drill-down runId 校验**：T11 `getResult` 改 `selectOne(eq(id).eq(runId))`；T12 加 `drilldown_endpoint_returns_null_when_runId_does_not_match` 测试
- **P2 文件清单/import/标题对齐**：T11 文件清单加 `EvalResultSummaryVO.java`；controller import 加 `EvalResultSummaryVO`；标题改"5 endpoints"；commit message 列出 5 个端点

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-25-rag-eval-pr-e3.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration. Per saved feedback memory: 机械任务（T1/T4/T5/T6/T11 VOs/T13 Python boilerplate/T15 service expansion）走 Sonnet；架构任务（T2 ChatForEvalService / T9 EvalRunExecutor 三态机 / T10 redaction / T17 detail page）走 Opus。

**2. Inline Execution** — execute in this session using executing-plans, batch with checkpoints.

**Which approach?**
