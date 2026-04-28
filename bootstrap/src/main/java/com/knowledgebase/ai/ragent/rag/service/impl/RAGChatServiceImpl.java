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

package com.knowledgebase.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.convention.ChatMessage;
import com.knowledgebase.ai.ragent.framework.convention.ChatRequest;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import com.knowledgebase.ai.ragent.framework.trace.RagTraceContext;
import com.knowledgebase.ai.ragent.infra.chat.LLMService;
import com.knowledgebase.ai.ragent.rag.dao.entity.ConversationDO;
import com.knowledgebase.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.knowledgebase.ai.ragent.infra.chat.StreamCallback;
import com.knowledgebase.ai.ragent.infra.chat.StreamCancellationHandle;
import com.knowledgebase.ai.ragent.rag.aop.ChatRateLimit;
import com.knowledgebase.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.knowledgebase.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentResolver;
import com.knowledgebase.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.prompt.PromptContext;
import com.knowledgebase.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.knowledgebase.ai.ragent.rag.core.prompt.RAGPromptService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.knowledgebase.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.knowledgebase.ai.ragent.rag.core.rewrite.RewriteResult;
import com.knowledgebase.ai.ragent.rag.core.source.SourceCardBuilder;
import com.knowledgebase.ai.ragent.rag.core.suggest.SuggestionContext;
import com.knowledgebase.ai.ragent.rag.config.RagSourcesProperties;
import com.knowledgebase.ai.ragent.rag.dto.EvaluationCollector;
import com.knowledgebase.ai.ragent.rag.dto.IntentGroup;
import com.knowledgebase.ai.ragent.rag.dto.RetrievalContext;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import com.knowledgebase.ai.ragent.rag.dto.SourcesPayload;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import com.knowledgebase.ai.ragent.rag.service.RAGChatService;
import com.knowledgebase.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.knowledgebase.ai.ragent.rag.service.handler.StreamChatEventHandler;
import com.knowledgebase.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.knowledgebase.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

/**
 * RAG 对话服务默认实现
 * <p>
 * 核心流程：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 检索(MCP+KB) -> Prompt 组装 -> 流式输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final IntentGuidanceService guidanceService;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final KbReadAccessPort kbReadAccess;
    private final ConversationMapper conversationMapper;
    private final SourceCardBuilder sourceCardBuilder;
    private final RagSourcesProperties ragSourcesProperties;

    @Override
    @ChatRateLimit
    public void streamChat(String question, String conversationId, String knowledgeBaseId,
                           Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);
        String userId = UserContext.getUserId();
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        StreamChatEventHandler callback = callbackFactory.createChatEventHandler(
                emitter, actualConversationId, taskId, userId);

        // 初始化评测数据采集器
        EvaluationCollector evalCollector = new EvaluationCollector();
        evalCollector.setOriginalQuery(question);
        RagTraceContext.setEvalCollector(evalCollector);

        // RBAC: resolve access scope (single source of truth for retrieval).
        // 未登录 → AccessScope.empty() (fail-closed), 不再复用 null 语义。
        AccessScope accessScope;
        if (UserContext.hasUser() && userId != null) {
            accessScope = kbReadAccess.getAccessScope(Permission.READ);
        } else {
            accessScope = AccessScope.empty();
        }

        // If knowledgeBaseId specified, verify access
        if (knowledgeBaseId != null) {
            kbReadAccess.checkReadAccess(knowledgeBaseId);
        }

        // Validate conversation-KB ownership for existing conversations
        if (knowledgeBaseId != null && StrUtil.isNotBlank(conversationId)) {
            ConversationDO existing = conversationMapper.selectOne(
                    Wrappers.lambdaQuery(ConversationDO.class)
                            .eq(ConversationDO::getConversationId, actualConversationId)
                            .eq(ConversationDO::getUserId, userId)
                            .eq(ConversationDO::getDeleted, 0)
            );
            if (existing != null && !Objects.equals(existing.getKbId(), knowledgeBaseId)) {
                throw new ClientException("会话不属于当前知识库");
            }
        }

        List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question), knowledgeBaseId);

        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

        // 采集改写和意图数据
        evalCollector.setRewrittenQuery(rewriteResult.rewrittenQuestion());
        evalCollector.setSubQuestions(rewriteResult.subQuestions());
        evalCollector.setIntents(subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .filter(ns -> ns.getNode() != null)
                .map(ns -> new EvaluationCollector.IntentSnapshot(
                        ns.getNode().getId(), ns.getNode().getName(),
                        ns.getScore(), ns.getNode().getKind() != null ? ns.getNode().getKind().name() : null))
                .toList());

        GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
        if (guidanceDecision.isPrompt()) {
            callback.onContent(guidanceDecision.getPrompt());
            callback.onComplete();
            return;
        }

        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (allSystemOnly) {
            String customPrompt = subIntents.stream()
                    .flatMap(si -> si.nodeScores().stream())
                    .map(ns -> ns.getNode().getPromptTemplate())
                    .filter(StrUtil::isNotBlank)
                    .findFirst()
                    .orElse(null);
            StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), history, customPrompt, callback);
            taskManager.bindHandle(taskId, handle);
            return;
        }

        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, accessScope, knowledgeBaseId);
        if (ctx.isEmpty()) {
            String emptyReply = "未检索到与问题相关的文档内容。";
            callback.onContent(emptyReply);
            callback.onComplete();
            return;
        }

        List<RetrievedChunk> distinctChunks = ctx.getIntentChunks() == null
                ? List.of()
                : ctx.getIntentChunks().values().stream()
                        .flatMap(List::stream)
                        .distinct()
                        .toList();

        List<RetrievedChunk> topChunks = distinctChunks.stream()
                .sorted(Comparator.comparing(
                        RetrievedChunk::getScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(3)
                .toList();
        double maxScore = distinctChunks.stream()
                .map(RetrievedChunk::getScore)
                .filter(Objects::nonNull)
                .mapToDouble(Float::doubleValue)
                .max()
                .orElse(-1D);
        log.info("[sources-gate] distinctChunks={}, maxScore={}, minTopScore={}",
                distinctChunks.size(), maxScore, ragSourcesProperties.getMinTopScore());
        boolean hasRelevantKbEvidence = hasRelevantKbEvidence(distinctChunks);

        boolean hasMcp = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .anyMatch(ns -> ns.getNode() != null && ns.getNode().isMCP());

        callback.updateSuggestionContext(new SuggestionContext(
                rewriteResult.rewrittenQuestion(),
                history,
                topChunks,
                !hasMcp && hasRelevantKbEvidence
        ));

        // ---- 回答来源事件推送（3 层闸门）----
        // 闸门 1：feature flag off → 跳过 sources（不影响回答路径）
        // 闸门 2：distinctChunks.isEmpty() → 跳过 builder 调用（MCP-Only / Mixed-but-no-KB 等）
        // 闸门 3：cards.isEmpty() → 不 trySet / 不 emit（findMetaByIds 过滤后无卡片）
        // Current gates are:
        // 1) feature flag on
        // 2) hasRelevantKbEvidence=true (distinctChunks non-empty and maxScore >= minTopScore)
        // 3) builder returns non-empty cards
        // 4) trySetCards succeeds before emit
        List<SourceCard> cards = List.of(); // outer scope; stays empty unless gate-3 sets it
        if (Boolean.TRUE.equals(ragSourcesProperties.getEnabled()) && hasRelevantKbEvidence) {
            cards = sourceCardBuilder.build(
                    distinctChunks,
                    ragSourcesProperties.getMaxCards(),
                    ragSourcesProperties.getPreviewMaxChars());
            if (!cards.isEmpty() && callback.trySetCards(cards)) {
                callback.emitSources(SourcesPayload.builder()
                        .conversationId(actualConversationId)
                        .messageId(null)
                        .cards(cards)
                        .build());
            }
        }

        // 记录实际喂给 LLM 的 chunk 数（不是 config 默认值）。
        // 这样对 IntentNode.topK override 场景、多 sub-question 聚合 + dedup 都是真值，
        // 避免"配置写 10、实际 8 或 13"在评测里记错。
        evalCollector.setTopK(distinctChunks.size());
        evalCollector.setChunks(distinctChunks.stream()
                .map(EvaluationCollector.RetrievedChunkSnapshot::from)
                .toList());

        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

        StreamCancellationHandle handle = streamLLMResponse(
                rewriteResult,
                ctx,
                mergedGroup,
                history,
                thinkingEnabled,
                callback,
                cards
        );
        taskManager.bindHandle(taskId, handle);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history.subList(0, history.size() - 1));
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback,
                                                       List<SourceCard> cards) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .cards(cards)
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }

    private boolean hasRelevantKbEvidence(List<RetrievedChunk> distinctChunks) {
        if (CollUtil.isEmpty(distinctChunks)) {
            return false;
        }
        double minTopScore = ragSourcesProperties.getMinTopScore() == null
                ? 0.55D
                : ragSourcesProperties.getMinTopScore();
        double maxScore = distinctChunks.stream()
                .map(RetrievedChunk::getScore)
                .filter(Objects::nonNull)
                .mapToDouble(Float::doubleValue)
                .max()
                .orElse(0.0D);
        return maxScore >= minTopScore;
    }
}
