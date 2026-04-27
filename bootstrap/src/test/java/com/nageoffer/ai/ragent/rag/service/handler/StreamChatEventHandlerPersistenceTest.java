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

package com.nageoffer.ai.ragent.rag.service.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamChatEventHandlerPersistenceTest {

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

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();
    private static final TypeReference<List<SourceCard>> SOURCES_TYPE = new TypeReference<>() {};

    private SourceCardsHolder holder;

    @BeforeEach
    void setUp() {
        RagTraceContext.setTraceId("test-trace-id");
        holder = new SourceCardsHolder();
        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("msg-1");
        when(ragConfigProperties.getSuggestionsEnabled()).thenReturn(false);
        when(taskManager.isCancelled(anyString())).thenReturn(false);
        when(conversationGroupService.findConversation(anyString(), any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        RagTraceContext.clear();
    }

    private StreamChatEventHandler newHandler() {
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("c-1")
                .taskId("t-1")
                .userId("user-1")
                .modelProperties(new AIModelProperties())
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
        return new StreamChatEventHandler(params);
    }

    private SourceCard card(int n) {
        return SourceCard.builder()
                .index(n).docId("d" + n).docName("D" + n).kbId("kb").topScore(0.9f)
                .chunks(List.of(SourceChunk.builder()
                        .chunkId("c").chunkIndex(0).preview("p").score(0.8f).build()))
                .build();
    }

    @Test
    void onComplete_whenCardsHolderEmpty_thenUpdateSourcesJsonNeverCalled() {
        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer");

        handler.onComplete();

        verify(conversationMessageService, never()).updateSourcesJson(anyString(), anyString());
    }

    @Test
    void onComplete_whenCardsHolderSet_thenUpdateSourcesJsonCalledOnceWithEquivalentCards() throws Exception {
        holder.trySet(List.of(card(1), card(2)));

        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer [^1]");

        handler.onComplete();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationMessageService, times(1))
                .updateSourcesJson(eq("msg-1"), jsonCaptor.capture());

        List<SourceCard> roundtrip = TEST_MAPPER.readValue(jsonCaptor.getValue(), SOURCES_TYPE);
        assertEquals(2, roundtrip.size());
        assertEquals(1, roundtrip.get(0).getIndex());
        assertEquals("d1", roundtrip.get(0).getDocId());
        assertEquals(2, roundtrip.get(1).getIndex());
        assertEquals("D1", roundtrip.get(0).getDocName());
        assertEquals("kb", roundtrip.get(0).getKbId());
        assertEquals(0.9f, roundtrip.get(0).getTopScore());
        assertEquals(1, roundtrip.get(0).getChunks().size());
        assertEquals("c", roundtrip.get(0).getChunks().get(0).getChunkId());
    }

    @Test
    void onComplete_whenMessageIdBlank_thenUpdateSourcesJsonNeverCalled() {
        holder.trySet(List.of(card(1)));
        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("");

        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer");

        handler.onComplete();

        verify(conversationMessageService, never()).updateSourcesJson(anyString(), anyString());
    }

    @Test
    void onComplete_whenUpdateSourcesJsonThrows_thenSubsequentSegmentsStillRun() throws Exception {
        holder.trySet(List.of(card(1)));
        doThrow(new RuntimeException("db down"))
                .when(conversationMessageService).updateSourcesJson(anyString(), anyString());

        StreamChatEventHandler handler = newHandler();
        handler.onContent("answer [^1]");

        handler.onComplete();

        // persist 被调一次（且抛了），但 onComplete 后续段仍执行：
        verify(conversationMessageService, times(1)).updateSourcesJson(anyString(), anyString());
        // mergeCitationStatsIntoTrace 仍跑（cardsHolder 非空 + traceRecordService 非空）
        verify(traceRecordService, times(1)).mergeRunExtraData(eq("test-trace-id"), anyMap());
        // FINISH + META + MESSAGE 等事件仍被 emit（at-least-once — emit 次数取决于 messageChunkSize 分片）
        verify(emitter, atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        // 锁 persist → merge 顺序（spec §2.7 invariant）
        InOrder inOrder = inOrder(conversationMessageService, traceRecordService);
        inOrder.verify(conversationMessageService).updateSourcesJson(anyString(), anyString());
        inOrder.verify(traceRecordService).mergeRunExtraData(anyString(), anyMap());
    }
}
