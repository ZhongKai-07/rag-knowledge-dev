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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * StreamCallback 工厂
 * 负责创建各种类型的 StreamCallback 实例
 *
 * 注意：返回具体类型 StreamChatEventHandler（而非 StreamCallback 接口），
 * 以便调用方（RAGChatServiceImpl）调用 updateSuggestionContext。
 */
@Component
@RequiredArgsConstructor
public class StreamCallbackFactory {

    private final AIModelProperties modelProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationMessageService conversationMessageService;
    private final ConversationGroupService conversationGroupService;
    private final StreamTaskManager taskManager;
    private final RagEvaluationService evaluationService;
    private final RagTraceRecordService traceRecordService;
    private final SuggestedQuestionsService suggestedQuestionsService;
    private final RAGConfigProperties ragConfigProperties;

    @Qualifier("suggestedQuestionsExecutor")
    private final ThreadPoolTaskExecutor suggestedQuestionsExecutor;

    /**
     * 创建聊天事件处理器
     */
    public StreamChatEventHandler createChatEventHandler(SseEmitter emitter,
                                                         String conversationId,
                                                         String taskId,
                                                         String userId) {
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(userId)
                .modelProperties(modelProperties)
                .memoryService(memoryService)
                .conversationMessageService(conversationMessageService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .evaluationService(evaluationService)
                .traceRecordService(traceRecordService)
                .suggestedQuestionsService(suggestedQuestionsService)
                .suggestedQuestionsExecutor(suggestedQuestionsExecutor)
                .ragConfigProperties(ragConfigProperties)
                .build();

        return new StreamChatEventHandler(params);
    }
}
