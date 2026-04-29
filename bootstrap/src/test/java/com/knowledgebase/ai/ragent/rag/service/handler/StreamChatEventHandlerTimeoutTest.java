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

import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.rag.config.RAGConfigProperties;
import com.knowledgebase.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.knowledgebase.ai.ragent.rag.service.ConversationGroupService;
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
import com.knowledgebase.ai.ragent.rag.service.RagEvaluationService;
import com.knowledgebase.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class StreamChatEventHandlerTimeoutTest {

    @Test
    void emitterTimeoutCancelsRegisteredStreamTask() {
        RecordingEmitter emitter = new RecordingEmitter();
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        when(taskManager.isCancelled(any())).thenReturn(false);

        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("c-1")
                .taskId("t-1")
                .userId("user-1")
                .modelProperties(new AIModelProperties())
                .memoryService(mock(ConversationMemoryService.class))
                .conversationMessageService(mock(ConversationMessageService.class))
                .conversationGroupService(mock(ConversationGroupService.class))
                .taskManager(taskManager)
                .evaluationService(mock(RagEvaluationService.class))
                .traceRecordService(mock(RagTraceRecordService.class))
                .suggestedQuestionsService(mock(SuggestedQuestionsService.class))
                .suggestedQuestionsExecutor(mock(ThreadPoolTaskExecutor.class))
                .ragConfigProperties(new RAGConfigProperties())
                .build();

        new StreamChatEventHandler(params);

        assertNotNull(emitter.timeoutCallback);
        emitter.timeoutCallback.run();

        verify(taskManager).cancel("t-1");
    }

    private static final class RecordingEmitter extends SseEmitter {
        private Runnable timeoutCallback;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            // noop
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            this.timeoutCallback = callback;
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            // noop
        }

        @Override
        public synchronized void onError(Consumer<Throwable> callback) {
            // noop
        }
    }
}
