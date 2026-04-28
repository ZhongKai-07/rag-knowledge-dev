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
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.infra.chat.LLMService;
import com.knowledgebase.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.knowledgebase.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentResolver;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.prompt.RAGPromptService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.knowledgebase.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.knowledgebase.ai.ragent.rag.core.rewrite.RewriteResult;
import com.knowledgebase.ai.ragent.rag.dto.IntentGroup;
import com.knowledgebase.ai.ragent.rag.dto.RetrievalContext;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import com.knowledgebase.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
                .thenReturn(RetrievalContext.builder().build());

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

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> svc.chatForEval(AccessScope.all(), "kb1", "q"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("model 503");
    }
}
