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
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * StreamChatEventHandler 构建参数
 * 使用参数对象模式，将多个参数封装成一个对象
 */
@Getter
@Builder
public class StreamChatHandlerParams {

    /**
     * SSE 发射器
     */
    private final SseEmitter emitter;

    /**
     * 会话ID
     */
    private final String conversationId;

    /**
     * 任务ID
     */
    private final String taskId;

    private final String userId;

    /**
     * 模型配置
     */
    private final AIModelProperties modelProperties;

    /**
     * 记忆服务
     */
    private final ConversationMemoryService memoryService;

    /**
     * 会话消息服务，用于持久化 sources_json
     */
    private final ConversationMessageService conversationMessageService;

    /**
     * 会话组服务
     */
    private final ConversationGroupService conversationGroupService;

    /**
     * 任务管理器
     */
    private final StreamTaskManager taskManager;

    /**
     * RAG 评测服务
     */
    private final RagEvaluationService evaluationService;

    /**
     * Trace 记录服务（用于在流完成后更新 token 用量）
     */
    private final RagTraceRecordService traceRecordService;

    /**
     * 推荐问题服务
     */
    private final SuggestedQuestionsService suggestedQuestionsService;

    /**
     * 推荐问题专用线程池
     */
    private final ThreadPoolTaskExecutor suggestedQuestionsExecutor;

    /**
     * RAG 功能配置属性
     */
    private final RAGConfigProperties ragConfigProperties;

    /**
     * 回答来源 cards 的 set-once 容器。
     * <p>
     * 使用 {@code @Builder.Default} 保证任何 {@code builder().build()} 调用
     * 都拿到非 null 的空壳实例；{@code @NonNull} 让显式传 null 在构建期 NPE，
     * 防止 {@code .cardsHolder(null)} 这种调用习惯混进来。
     */
    @lombok.Builder.Default
    @NonNull
    private final SourceCardsHolder cardsHolder = new SourceCardsHolder();
}
