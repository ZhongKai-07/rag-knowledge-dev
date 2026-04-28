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

import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.rag.config.RAGConfigProperties;
import com.knowledgebase.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.knowledgebase.ai.ragent.rag.service.ConversationGroupService;
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
import com.knowledgebase.ai.ragent.rag.service.RagEvaluationService;
import com.knowledgebase.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamCallbackFactoryUserIdTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createdHandlerUsesInjectedUserIdAfterUserContextIsCleared() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        when(memoryService.append(any(), any(), any(), any())).thenReturn("msg-1");
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        when(taskManager.isCancelled(any())).thenReturn(false);

        RAGConfigProperties ragConfigProperties = new RAGConfigProperties();
        ragConfigProperties.setSuggestionsEnabled(false);

        StreamCallbackFactory factory = new StreamCallbackFactory(
                new AIModelProperties(),
                memoryService,
                mock(ConversationMessageService.class),
                conversationGroupService,
                taskManager,
                mock(RagEvaluationService.class),
                mock(RagTraceRecordService.class),
                mock(SuggestedQuestionsService.class),
                ragConfigProperties,
                mock(ThreadPoolTaskExecutor.class)
        );

        StreamChatEventHandler handler = factory.createChatEventHandler(
                mock(SseEmitter.class), "conv-1", "task-1", "user-from-service");

        UserContext.clear();
        handler.onContent("answer");
        handler.onComplete();

        verify(memoryService).append(eq("conv-1"), eq("user-from-service"), any(), isNull());
    }
}
