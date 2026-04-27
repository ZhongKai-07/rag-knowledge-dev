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

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamChatEventHandlerSuggestionsTest {

    private RecordingEmitter recordingEmitter;
    private ConversationMemoryService memoryService;
    private ConversationGroupService conversationGroupService;
    private StreamTaskManager taskManager;
    private RagEvaluationService evaluationService;
    private RagTraceRecordService traceRecordService;
    private SuggestedQuestionsService suggestedQuestionsService;
    private ConversationMessageService conversationMessageService;
    private ThreadPoolTaskExecutor executor;
    private RAGConfigProperties ragConfigProperties;

    @BeforeEach
    void setUp() {
        recordingEmitter = new RecordingEmitter();

        memoryService = mock(ConversationMemoryService.class);
        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("msg-1");

        conversationGroupService = mock(ConversationGroupService.class);
        when(conversationGroupService.findConversation(anyString(), any())).thenReturn(null);

        taskManager = mock(StreamTaskManager.class);
        when(taskManager.isCancelled(anyString())).thenReturn(false);

        evaluationService = mock(RagEvaluationService.class);
        traceRecordService = mock(RagTraceRecordService.class);
        suggestedQuestionsService = mock(SuggestedQuestionsService.class);
        conversationMessageService = mock(ConversationMessageService.class);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();

        ragConfigProperties = new RAGConfigProperties();
        ragConfigProperties.setSuggestionsEnabled(true);
    }

    private StreamChatEventHandler buildHandler() {
        AIModelProperties modelProps = new AIModelProperties();
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(recordingEmitter)
                .conversationId("conv-1")
                .taskId("task-1")
                .userId("user-1")
                .modelProperties(modelProps)
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .evaluationService(evaluationService)
                .traceRecordService(traceRecordService)
                .suggestedQuestionsService(suggestedQuestionsService)
                .suggestedQuestionsExecutor(executor)
                .ragConfigProperties(ragConfigProperties)
                .conversationMessageService(conversationMessageService)
                .build();
        return new StreamChatEventHandler(params);
    }

    private void waitForExecutor() throws InterruptedException {
        executor.shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void emits_finish_then_suggestions_then_done_on_happy_path() throws Exception {
        when(suggestedQuestionsService.generate(any(), anyString()))
                .thenReturn(List.of("q1", "q2", "q3"));

        StreamChatEventHandler handler = buildHandler();
        handler.updateSuggestionContext(new SuggestionContext("java 特点", List.of(), List.of(), true));
        handler.onContent("Java 是一门...");
        handler.onComplete();
        waitForExecutor();

        List<String> events = recordingEmitter.eventNames();
        // initialize() sends META; onContent sends MESSAGE; onComplete sends FINISH → async sends SUGGESTIONS → finally sends DONE
        assertTrue(events.contains("meta"), "events should include meta, got " + events);
        assertTrue(events.contains("message"), "events should include message, got " + events);
        int finishIdx = events.indexOf("finish");
        int suggestionsIdx = events.indexOf("suggestions");
        int doneIdx = events.indexOf("done");
        assertTrue(finishIdx >= 0, "finish missing, got " + events);
        assertTrue(suggestionsIdx > finishIdx, "suggestions should appear after finish, got " + events);
        assertTrue(doneIdx > suggestionsIdx, "done should appear after suggestions, got " + events);
    }

    @Test
    void skips_suggestions_when_should_generate_is_false() throws Exception {
        StreamChatEventHandler handler = buildHandler();
        // do NOT call updateSuggestionContext — defaults to skip()
        handler.onContent("x");
        handler.onComplete();
        waitForExecutor();

        List<String> events = recordingEmitter.eventNames();
        assertTrue(events.contains("finish"));
        assertTrue(events.contains("done"));
        assertFalse(events.contains("suggestions"), "should not emit suggestions when shouldGenerate=false");
        verify(suggestedQuestionsService, never()).generate(any(), anyString());
    }

    @Test
    void sends_done_even_when_suggestion_generation_throws() throws Exception {
        when(suggestedQuestionsService.generate(any(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        StreamChatEventHandler handler = buildHandler();
        handler.updateSuggestionContext(new SuggestionContext("q", List.of(), List.of(), true));
        handler.onContent("x");
        handler.onComplete();
        waitForExecutor();

        List<String> events = recordingEmitter.eventNames();
        assertFalse(events.contains("suggestions"), "no suggestions on exception");
        assertTrue(events.contains("done"), "done must fire via finally");
    }

    /**
     * 测试专用 SseEmitter：拦截 send(SseEventBuilder)，
     * 通过 SseEmitter.DataWithMediaType.getData() 公开 API
     * 解析 "event:<name>\n" 文本标记，提取事件名。
     */
    static class RecordingEmitter extends SseEmitter {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public synchronized void send(Object object) throws IOException {
            // unnamed event → "message"
            events.add("message");
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            // Spring 合并 "event:<name>\ndata:" 到单个 DataWithMediaType.getData() 里，
            // 因此遍历找到以 "event:" 开头的片段，按换行取首行作为事件名。
            for (SseEmitter.DataWithMediaType part : builder.build()) {
                Object data = part.getData();
                if (data instanceof String s && s.startsWith("event:")) {
                    int nl = s.indexOf('\n');
                    String name = (nl >= 0 ? s.substring("event:".length(), nl) : s.substring("event:".length()))
                            .trim();
                    events.add(name);
                    return;
                }
            }
            events.add("message");
        }

        @Override
        public synchronized void complete() {
            // noop
        }

        public List<String> eventNames() {
            return new java.util.ArrayList<>(events);
        }
    }
}
