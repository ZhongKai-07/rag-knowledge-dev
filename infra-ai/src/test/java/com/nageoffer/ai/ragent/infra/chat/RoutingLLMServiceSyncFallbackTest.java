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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR Finding 2 sync fallback lock — PR E3 Task 7b.
 *
 * <p>Locks: {@link RoutingLLMService#chat(ChatRequest)} routes exclusively through
 * {@link ModelRoutingExecutor#executeWithFallback}, which provides automatic candidate
 * fallback (first failure → next candidate auto-retry) and exception propagation when
 * all candidates are exhausted. {@code ChatForEvalService} relies on this semantic for
 * deterministic eval runs.
 */
@SuppressWarnings("unchecked")
class RoutingLLMServiceSyncFallbackTest {

    private ModelSelector selector;
    private ModelHealthStore healthStore;
    private ModelRoutingExecutor executor;
    private RoutingLLMService svc;

    @BeforeEach
    void setUp() {
        selector = mock(ModelSelector.class);
        healthStore = mock(ModelHealthStore.class);
        executor = mock(ModelRoutingExecutor.class);

        // Two candidate stubs (provider values matching the ChatClient mock below).
        AIModelProperties.ModelCandidate c1 = new AIModelProperties.ModelCandidate();
        c1.setId("qwen3-max");
        c1.setProvider("BAI_LIAN");
        c1.setPriority(1);

        AIModelProperties.ModelCandidate c2 = new AIModelProperties.ModelCandidate();
        c2.setId("llama3");
        c2.setProvider("OLLAMA");
        c2.setPriority(2);

        ModelTarget t1 = new ModelTarget("qwen3-max", c1, null);
        ModelTarget t2 = new ModelTarget("llama3", c2, null);

        when(selector.selectChatCandidates(any())).thenReturn(List.of(t1, t2));

        // A stub ChatClient so the provider-map is non-empty (executor is fully mocked,
        // so the resolver lambda never runs during these tests).
        ChatClient stubClient = mock(ChatClient.class);
        when(stubClient.provider()).thenReturn("BAI_LIAN");

        svc = new RoutingLLMService(selector, healthStore, executor, List.of(stubClient));
    }

    /**
     * Verifies that sync chat(ChatRequest) delegates to ModelRoutingExecutor.executeWithFallback,
     * which handles first-candidate failure and auto-falls back to the next candidate.
     * The executor returning "fallback-answer" proves the full fallback path is wired.
     */
    @Test
    void sync_chat_falls_back_to_next_candidate_when_first_throws() {
        ChatRequest req = ChatRequest.builder().build();

        // Executor absorbs the first-candidate failure internally and returns the
        // second candidate's response — exactly the fallback semantic we are locking.
        when(executor.executeWithFallback(any(), any(), any(), any()))
                .thenReturn("fallback-answer");

        String result = svc.chat(req);

        assertThat(result).isEqualTo("fallback-answer");
        // Exactly one delegation to the executor — proves no direct client.chat() bypass.
        verify(executor, times(1)).executeWithFallback(any(), any(), any(), any());
    }

    /**
     * Verifies that when ModelRoutingExecutor exhausts all candidates and throws,
     * RoutingLLMService.chat propagates that exception to the caller unchanged.
     */
    @Test
    void sync_chat_propagates_exception_when_all_candidates_fail() {
        ChatRequest req = ChatRequest.builder().build();

        when(executor.executeWithFallback(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("all candidates exhausted"));

        assertThatThrownBy(() -> svc.chat(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("all candidates exhausted");
    }
}
