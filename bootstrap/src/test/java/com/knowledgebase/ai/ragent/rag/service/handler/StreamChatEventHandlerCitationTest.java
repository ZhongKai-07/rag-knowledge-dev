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

package com.knowledgebase.ai.ragent.rag.service.handler;

import com.knowledgebase.ai.ragent.framework.trace.RagTraceContext;
import com.knowledgebase.ai.ragent.infra.chat.TokenUsage;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.rag.config.RAGConfigProperties;
import com.knowledgebase.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import com.knowledgebase.ai.ragent.rag.dto.SourceChunk;
import com.knowledgebase.ai.ragent.rag.service.ConversationGroupService;
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
import com.knowledgebase.ai.ragent.rag.service.RagEvaluationService;
import com.knowledgebase.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamChatEventHandlerCitationTest {

    @Mock SseEmitter emitter;
    @Mock ConversationMemoryService memoryService;
    @Mock ConversationGroupService conversationGroupService;
    @Mock StreamTaskManager taskManager;
    @Mock RagEvaluationService evaluationService;
    @Mock RagTraceRecordService traceRecordService;
    @Mock SuggestedQuestionsService suggestedQuestionsService;
    @Mock ThreadPoolTaskExecutor suggestedQuestionsExecutor;
    @Mock RAGConfigProperties ragConfigProperties;
    @Mock ConversationMessageService conversationMessageService;

    private SourceCardsHolder holder;
    private StreamChatEventHandler handler;

    @BeforeEach
    void setUp() {
        // MUST set traceId BEFORE constructing handler — handler reads it in ctor
        RagTraceContext.setTraceId("test-trace-id");

        holder = new SourceCardsHolder();

        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("msg-1");
        when(ragConfigProperties.getSuggestionsEnabled()).thenReturn(false);
        when(taskManager.isCancelled(anyString())).thenReturn(false);
        when(conversationGroupService.findConversation(anyString(), any())).thenReturn(null);

        AIModelProperties modelProps = new AIModelProperties();

        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("c-1")
                .taskId("t-1")
                .userId("user-1")
                .modelProperties(modelProps)
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .evaluationService(evaluationService)
                .traceRecordService(traceRecordService)
                .suggestedQuestionsService(suggestedQuestionsService)
                .suggestedQuestionsExecutor(suggestedQuestionsExecutor)
                .ragConfigProperties(ragConfigProperties)
                .cardsHolder(holder)
                .conversationMessageService(conversationMessageService)
                .build();

        handler = new StreamChatEventHandler(params);
    }

    @AfterEach
    void tearDown() {
        RagTraceContext.clear();
    }

    private SourceCard card(int n) {
        return SourceCard.builder()
                .index(n).docId("d" + n).docName("D" + n).kbId("kb").topScore(0.9f)
                .chunks(List.of(SourceChunk.builder()
                        .chunkId("c").chunkIndex(0).preview("p").score(0.8f).build()))
                .build();
    }

    /**
     * Option A — handler has public onTokenUsage(TokenUsage).
     */
    private void applyTokenUsage(int prompt, int completion, int total) {
        handler.onTokenUsage(new TokenUsage(prompt, completion, total));
    }

    @Test
    void onComplete_whenCardsHolderUnset_thenMergeRunExtraDataNotCalled() {
        handler.onContent("回答正文[^1]。");
        applyTokenUsage(10, 20, 30);
        handler.onComplete();

        verify(traceRecordService, never()).mergeRunExtraData(anyString(), anyMap());
        verify(traceRecordService).updateRunExtraData(eq("test-trace-id"), anyString());
    }

    @Test
    void onComplete_whenCardsHolderSetAndAnswerHasCitations_thenMergeCalledOnceWithFourKeys() {
        holder.trySet(List.of(card(1), card(2)));
        handler.onContent("句[^1]。句[^2]。越界[^99]。");
        applyTokenUsage(5, 6, 11);
        handler.onComplete();

        InOrder io = inOrder(traceRecordService);
        io.verify(traceRecordService).updateRunExtraData(eq("test-trace-id"), anyString());
        io.verify(traceRecordService).mergeRunExtraData(eq("test-trace-id"), anyMap());

        verify(traceRecordService).mergeRunExtraData(eq("test-trace-id"),
                org.mockito.ArgumentMatchers.argThat(map -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) map;
                    return m.containsKey("citationTotal")
                            && m.containsKey("citationValid")
                            && m.containsKey("citationInvalid")
                            && m.containsKey("citationCoverage")
                            && ((Integer) m.get("citationTotal")) == 3
                            && ((Integer) m.get("citationValid")) == 2
                            && ((Integer) m.get("citationInvalid")) == 1;
                }));
    }

    @Test
    void onComplete_whenCardsSetButAnswerEmpty_thenStillMergesAllZeros() {
        holder.trySet(List.of(card(1)));
        applyTokenUsage(0, 0, 0);
        handler.onComplete();

        verify(traceRecordService).mergeRunExtraData(eq("test-trace-id"),
                org.mockito.ArgumentMatchers.argThat(map -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) map;
                    return ((Integer) m.get("citationTotal")) == 0
                            && ((Integer) m.get("citationValid")) == 0
                            && ((Integer) m.get("citationInvalid")) == 0
                            && ((Double) m.get("citationCoverage")) == 0.0;
                }));
    }

    @Test
    void onComplete_whenAnswerNonBlankButHasNoCitations_thenMergesAllZerosViaLoopPath() {
        // 区别于 whenCardsSetButAnswerEmpty：那条用例走 scan 首行 StrUtil.isBlank 早返回；
        // 本用例 answer 非空，实际走完 CITATION 匹配循环 + SENTENCE 循环，结果才应全 0。
        holder.trySet(List.of(card(1), card(2)));
        handler.onContent("一句话无引用。另一句仍无引用。");
        applyTokenUsage(10, 20, 30);
        handler.onComplete();

        verify(traceRecordService).mergeRunExtraData(eq("test-trace-id"),
                org.mockito.ArgumentMatchers.argThat(map -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) map;
                    return ((Integer) m.get("citationTotal")) == 0
                            && ((Integer) m.get("citationValid")) == 0
                            && ((Integer) m.get("citationInvalid")) == 0
                            && ((Double) m.get("citationCoverage")) == 0.0;
                }));
    }
}
