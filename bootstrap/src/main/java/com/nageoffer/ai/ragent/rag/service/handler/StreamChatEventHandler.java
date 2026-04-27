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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.TokenUsage;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext;
import com.nageoffer.ai.ragent.rag.dto.EvaluationCollector;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourcesPayload;
import com.nageoffer.ai.ragent.rag.dto.SuggestionsPayload;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.core.source.CitationStatsCollector;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";
    private static final ObjectMapper SOURCES_MAPPER = new ObjectMapper();

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationMessageService conversationMessageService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final RagEvaluationService evaluationService;
    private final RagTraceRecordService traceRecordService;
    private final String traceId;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();
    private volatile TokenUsage tokenUsage;
    private final SuggestedQuestionsService suggestedQuestionsService;
    private final ThreadPoolTaskExecutor suggestedQuestionsExecutor;
    private final RAGConfigProperties ragConfigProperties;
    private final SourceCardsHolder cardsHolder;
    private volatile SuggestionContext suggestionContext = SuggestionContext.skip();

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationMessageService = params.getConversationMessageService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.evaluationService = params.getEvaluationService();
        this.traceRecordService = params.getTraceRecordService();
        this.traceId = RagTraceContext.getTraceId();
        this.userId = params.getUserId();
        this.suggestedQuestionsService = params.getSuggestedQuestionsService();
        this.suggestedQuestionsExecutor = params.getSuggestedQuestionsExecutor();
        this.ragConfigProperties = params.getRagConfigProperties();
        this.cardsHolder = params.getCardsHolder();

        // 计算配置
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    /**
     * 初始化：发送元数据事件并注册任务
     */
    private void initialize() {
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析消息块大小
     */
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     */
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        if (StrUtil.isNotBlank(content)) {
            messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(content), null);
        }
        String title = resolveTitleForEvent();
        return new CompletionPayload(String.valueOf(messageId), title);
    }

    @Override
    public void onTokenUsage(TokenUsage usage) {
        if (usage != null) {
            this.tokenUsage = usage;
        }
    }

    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        sendChunked(TYPE_THINK, chunk);
    }

    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        String messageId = memoryService.append(conversationId, userId,
                ChatMessage.assistant(answer.toString()), null);

        // 持久化 sources_json（必须在 updateTraceTokenUsage 之前，见 spec §2.7）
        persistSourcesIfPresent(messageId);

        // 更新 Trace token 用量（overwrite 写，必须在 merge 之前）
        updateTraceTokenUsage();

        // 合并 citation 埋点到 Trace extra_data（merge 写，必须在 overwrite 之后）
        mergeCitationStatsIntoTrace();

        // 保存评测记录（异步）
        saveEvaluationRecord(messageId);

        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;

        // 立即发 FINISH，让前端进入"完成"状态
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));

        SuggestionContext ctx = this.suggestionContext;
        boolean enabled = Boolean.TRUE.equals(ragConfigProperties.getSuggestionsEnabled());
        boolean shouldGenerate = enabled && ctx.shouldGenerate();

        if (!shouldGenerate) {
            sendDoneAndClose();
            return;
        }

        final String finalMessageId = messageIdText;
        final String answerSnapshot = answer.toString();
        try {
            suggestedQuestionsExecutor.submit(() -> generateAndFinish(ctx, answerSnapshot, finalMessageId));
        } catch (RejectedExecutionException rex) {
            log.warn("推荐生成线程池拒绝提交，直接结束 SSE", rex);
            sendDoneAndClose();
        }
    }

    private void generateAndFinish(SuggestionContext ctx, String answerSnapshot, String messageIdText) {
        List<String> questions = List.of();
        try {
            if (taskManager.isCancelled(taskId)) {
                return;
            }
            questions = suggestedQuestionsService.generate(ctx, answerSnapshot);
            if (!taskManager.isCancelled(taskId) && !questions.isEmpty()) {
                sender.sendEvent(SSEEventType.SUGGESTIONS.value(),
                        new SuggestionsPayload(messageIdText, questions));
            }
        } catch (Exception e) {
            log.warn("推荐问题生成失败", e);
        } finally {
            mergeSuggestionsIntoTrace(questions);
            sendDoneAndClose();
        }
    }

    private void sendDoneAndClose() {
        try {
            sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        } catch (Exception e) {
            log.warn("发送 DONE 失败", e);
        }
        taskManager.unregister(taskId);
        sender.complete();
    }

    private void mergeSuggestionsIntoTrace(List<String> questions) {
        if (traceRecordService == null || StrUtil.isBlank(traceId)) {
            return;
        }
        try {
            traceRecordService.mergeRunExtraData(traceId,
                    Map.of("suggestedQuestions", questions));
        } catch (Exception e) {
            log.warn("合并推荐问题到 trace.extra_data 失败", e);
        }
    }

    private void updateTraceTokenUsage() {
        if (traceRecordService == null || StrUtil.isBlank(traceId) || tokenUsage == null) {
            return;
        }
        try {
            String extraData = StrUtil.format(
                    "{\"promptTokens\":{},\"completionTokens\":{},\"totalTokens\":{}}",
                    tokenUsage.promptTokens(), tokenUsage.completionTokens(), tokenUsage.totalTokens()
            );
            traceRecordService.updateRunExtraData(traceId, extraData);
        } catch (Exception e) {
            log.warn("更新 Trace token 用量失败", e);
        }
    }

    /**
     * 合并 citation 埋点到 trace.extra_data（PR3）。
     *
     * <p>顺序要求：必须在 {@link #updateTraceTokenUsage()}（overwrite 写）之后调用，
     * 否则 merge 结果会被后续 overwrite 清掉。
     *
     * <p>traceId 来自构造期缓存的 final 字段，不读 ThreadLocal。
     */
    private void mergeCitationStatsIntoTrace() {
        if (traceRecordService == null || StrUtil.isBlank(traceId)) {
            return;
        }
        Optional<List<SourceCard>> cardsOpt = cardsHolder.get();
        if (cardsOpt.isEmpty()) {
            return;
        }
        try {
            CitationStatsCollector.CitationStats stats =
                    CitationStatsCollector.scan(answer.toString(), cardsOpt.get());
            traceRecordService.mergeRunExtraData(traceId, Map.of(
                    "citationTotal", stats.total(),
                    "citationValid", stats.valid(),
                    "citationInvalid", stats.invalid(),
                    "citationCoverage", stats.coverage()
            ));
        } catch (Exception e) {
            log.warn("合并引用统计到 trace.extra_data 失败", e);
        }
    }

    /**
     * 在 onComplete 中持久化 sources_json。holder 空或 messageId blank 时早返回。
     * 任何异常仅 log.warn，不 rethrow，避免阻塞 onComplete 后续段（token 埋点 / citation merge
     * / evaluation / FINISH / SUGGESTIONS / DONE）。
     */
    private void persistSourcesIfPresent(String messageId) {
        if (StrUtil.isBlank(messageId)) {
            return;
        }
        Optional<List<SourceCard>> cardsOpt = cardsHolder.get();
        if (cardsOpt.isEmpty()) {
            return;
        }
        try {
            String json = SOURCES_MAPPER.writeValueAsString(cardsOpt.get());
            conversationMessageService.updateSourcesJson(messageId, json);
        } catch (Exception e) {
            log.warn("持久化 sources_json 失败，messageId={}", messageId, e);
        }
    }

    private void saveEvaluationRecord(String messageId) {
        if (evaluationService == null) {
            return;
        }
        try {
            EvaluationCollector collector = RagTraceContext.getEvalCollector();
            if (collector != null) {
                collector.setAnswer(answer.toString());
                evaluationService.saveRecord(
                        collector, conversationId, messageId,
                        traceId, userId
                );
            }
        } catch (Exception e) {
            // 评测记录保存失败不影响主流程
        }
    }

    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }

    /**
     * 一次性存入 cards。委托给 {@link SourceCardsHolder#trySet(List)}。
     * <p>
     * 返回值用于调用方防御（理论上 orchestrator 主路径仅调一次，始终返回 true）。
     */
    public boolean trySetCards(List<SourceCard> cards) {
        return cardsHolder.trySet(cards);
    }

    /**
     * 机械发射 SSE {@code sources} 事件。异常语义沿用 {@link SseEmitterSender#sendEvent}，
     * 不做额外吞错。
     */
    public void emitSources(SourcesPayload payload) {
        sender.sendEvent(SSEEventType.SOURCES.value(), payload);
    }

    /**
     * orchestrator 走完检索后，调用此方法更新上下文以触发推荐生成
     */
    public void updateSuggestionContext(SuggestionContext ctx) {
        if (ctx != null) {
            this.suggestionContext = ctx;
        }
    }
}
