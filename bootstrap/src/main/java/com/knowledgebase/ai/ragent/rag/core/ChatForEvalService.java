/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.knowledgebase.ai.ragent.rag.core;

import com.knowledgebase.ai.ragent.framework.convention.ChatMessage;
import com.knowledgebase.ai.ragent.framework.convention.ChatRequest;
import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.chat.LLMService;
import com.knowledgebase.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentResolver;
import com.knowledgebase.ai.ragent.rag.core.prompt.PromptContext;
import com.knowledgebase.ai.ragent.rag.core.prompt.RAGPromptService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
import com.knowledgebase.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.knowledgebase.ai.ragent.rag.core.rewrite.RewriteResult;
import com.knowledgebase.ai.ragent.rag.dto.IntentGroup;
import com.knowledgebase.ai.ragent.rag.dto.RetrievalContext;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 同步阻塞 RAG 编排，仅供 eval 域使用。
 *
 * <p>设计依据见 ADR {@code docs/dev/design/2026-04-25-answer-pipeline-spike-adr.md}。
 * 直接复用现有 6 个 service bean，不抽 AnswerPipeline (scope 由调用方传入，见 P1-1 + PR4 P1#2)。
 *
 * <p>三处早返回映射到 {@link AnswerResult} 状态码（不像 streamChat 那样发 SSE）：
 * <ul>
 *   <li>guidance ambiguous → {@link AnswerResult.AmbiguousIntentSkipped}</li>
 *   <li>all sub-intents systemOnly → {@link AnswerResult.SystemOnlySkipped}</li>
 *   <li>retrieval ctx empty → {@link AnswerResult.EmptyContext}</li>
 * </ul>
 *
 * <p><b>RetrievalScope 由调用方注入，service 不自造</b>（review P1-1 + PR4 P1#2）：避免任何复用此 service 的入口
 * 默认越过 RBAC。eval 路径下唯一合法调用方 {@code EvalRunExecutor}
 * 显式传 all-scope sentinel（spec §15.3 边界）。其他调用方必须传该入口下登录 principal 的真实 RetrievalScope。
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

    public AnswerResult chatForEval(RetrievalScope scope, String question) {
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

        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, scope);
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
                        .cards(List.of())
                        .build(),
                List.of(),
                rewrite.rewrittenQuestion(),
                rewrite.subQuestions());

        String answer = llmService.chat(ChatRequest.builder()
                .messages(messages)
                .thinking(false)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build());

        return AnswerResult.success(answer, chunks);
    }
}
