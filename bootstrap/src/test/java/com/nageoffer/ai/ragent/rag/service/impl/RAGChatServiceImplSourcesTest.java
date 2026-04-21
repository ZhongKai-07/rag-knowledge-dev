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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.config.RagSourcesProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.SourceCardBuilder;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourcesPayload;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamChatEventHandler;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驱动真实的 {@link RAGChatServiceImpl#streamChat} 方法，使用 14 个 mocked 协作者 +
 * 真实 {@link RagSourcesProperties} POJO，通过 {@link InOrder} 锁住 retrieve → build → emit
 * → streamChat 的相对顺序，验证 3 层闸门对回答路径的不侵入性。
 */
@ExtendWith(MockitoExtension.class)
class RAGChatServiceImplSourcesTest {

    @Mock LLMService llmService;
    @Mock RAGPromptService promptBuilder;
    @Mock PromptTemplateLoader promptTemplateLoader;
    @Mock ConversationMemoryService memoryService;
    @Mock StreamTaskManager taskManager;
    @Mock IntentGuidanceService guidanceService;
    @Mock StreamCallbackFactory callbackFactory;
    @Mock QueryRewriteService queryRewriteService;
    @Mock IntentResolver intentResolver;
    @Mock RetrievalEngine retrievalEngine;
    @Mock KbAccessService kbAccessService;
    @Mock KbReadAccessPort kbReadAccess;
    @Mock ConversationMapper conversationMapper;
    @Mock SourceCardBuilder sourceCardBuilder;

    @Mock StreamChatEventHandler callback;
    @Mock SseEmitter emitter;

    RagSourcesProperties props;

    @InjectMocks
    RAGChatServiceImpl service;

    @BeforeEach
    void setUp() {
        // 未登录场景：UserContext.hasUser()=false → AccessScope.empty() 分支
        UserContext.clear();
        // 清理 TTL 评测采集器，防止 streamChat 写入的 EVAL_COLLECTOR 在测试间泄漏
        RagTraceContext.clear();

        // RagSourcesProperties 是真实 POJO（非 @Mock），通过反射注入到 @InjectMocks 构造的 service 中
        props = new RagSourcesProperties();
        props.setEnabled(true);
        props.setMaxCards(8);
        props.setPreviewMaxChars(200);
        ReflectionTestUtils.setField(service, "ragSourcesProperties", props);

        // callback 工厂
        lenient().when(callbackFactory.createChatEventHandler(any(), any(), any())).thenReturn(callback);

        // 记忆、改写、意图
        lenient().when(memoryService.loadAndAppend(any(), any(), any(), any())).thenReturn(List.<ChatMessage>of());
        lenient().when(queryRewriteService.rewriteWithSplit(any(), any()))
                .thenReturn(new RewriteResult("q", List.of("q")));

        // 1 个非空 SubQuestionIntent（空列表会触发 allMatch=true 的 SystemOnly 分支）
        SubQuestionIntent si = new SubQuestionIntent("q", List.of());
        lenient().when(intentResolver.resolve(any())).thenReturn(List.of(si));
        lenient().when(intentResolver.isSystemOnly(any())).thenReturn(false);
        lenient().when(intentResolver.mergeIntentGroup(any()))
                .thenReturn(new IntentGroup(List.of(), List.of()));

        // guidance：不做 prompt 引导
        lenient().when(guidanceService.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.none());

        // prompt build：返回空 messages
        lenient().when(promptBuilder.buildStructuredMessages(any(), any(), any(), any()))
                .thenReturn(List.of());

        // LLM streamChat：返回 mock 取消句柄
        lenient().when(llmService.streamChat(any(ChatRequest.class), any(StreamCallback.class)))
                .thenReturn(mock(StreamCancellationHandle.class));

        // trySetCards 默认为 true（happyPath 使用）
        lenient().when(callback.trySetCards(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        RagTraceContext.clear();
    }

    // ---------- helpers ----------

    private static RetrievedChunk chunk(String id, String docId) {
        return RetrievedChunk.builder().id(id).docId(docId).score(0.9f).text("preview").build();
    }

    private RetrievalContext ctxWithKbChunks(List<RetrievedChunk> chunks) {
        RetrievalContext ctx = mock(RetrievalContext.class);
        lenient().when(ctx.isEmpty()).thenReturn(chunks.isEmpty());
        lenient().when(ctx.getIntentChunks()).thenReturn(Map.of("i1", chunks));
        lenient().when(ctx.getMcpContext()).thenReturn("");
        lenient().when(ctx.getKbContext()).thenReturn("kb-ctx");
        lenient().when(ctx.hasMcp()).thenReturn(false);
        return ctx;
    }

    private RetrievalContext ctxMcpOnly() {
        RetrievalContext ctx = mock(RetrievalContext.class);
        lenient().when(ctx.isEmpty()).thenReturn(false);
        lenient().when(ctx.getIntentChunks()).thenReturn(Map.of());
        lenient().when(ctx.getMcpContext()).thenReturn("mcp-ctx");
        lenient().when(ctx.getKbContext()).thenReturn("");
        lenient().when(ctx.hasMcp()).thenReturn(true);
        return ctx;
    }

    private RetrievalContext ctxEmpty() {
        RetrievalContext ctx = mock(RetrievalContext.class);
        lenient().when(ctx.isEmpty()).thenReturn(true);
        return ctx;
    }

    // ---------- tests ----------

    /** 1. happyPath：有 chunks + 有 cards → retrieve → build → emit → streamChat 严格有序。 */
    @Test
    void happyPath_emitSourcesBetweenRetrieveAndStreamChat() {
        List<RetrievedChunk> chunks = List.of(chunk("c1", "d1"));
        RetrievalContext ctx = ctxWithKbChunks(chunks);
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        SourceCard card = SourceCard.builder().index(1).docId("d1").docName("D1").topScore(0.9f).chunks(List.of()).build();
        when(sourceCardBuilder.build(any(), anyInt(), anyInt())).thenReturn(List.of(card));

        service.streamChat("q", "cid-1", null, false, emitter);

        // retrieve → build → trySetCards → emitSources → llmService.streamChat
        InOrder io = inOrder(retrievalEngine, sourceCardBuilder, callback, llmService);
        io.verify(retrievalEngine).retrieve(any(), anyInt(), any(), any());
        io.verify(sourceCardBuilder).build(any(), eq(8), eq(200));
        io.verify(callback).trySetCards(any());
        io.verify(callback).emitSources(any(SourcesPayload.class));
        io.verify(llmService).streamChat(any(ChatRequest.class), any(StreamCallback.class));

        // 用 ArgumentCaptor 精确断言 SourcesPayload 内容
        ArgumentCaptor<SourcesPayload> captor = ArgumentCaptor.forClass(SourcesPayload.class);
        verify(callback).emitSources(captor.capture());
        SourcesPayload p = captor.getValue();
        assertThat(p.getConversationId()).isEqualTo("cid-1");
        assertThat(p.getMessageId()).isNull();
        assertThat(p.getCards()).containsExactly(card);
    }

    /** 2. feature flag off → 跳过 builder + emit，但 LLM 流必须照常启动。 */
    @Test
    void flagOff_shouldNotCallBuilder_butShouldStillStartLlmStream() {
        props.setEnabled(false);

        List<RetrievedChunk> chunks = List.of(chunk("c1", "d1"));
        RetrievalContext ctx = ctxWithKbChunks(chunks);
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        service.streamChat("q", "cid-2", null, false, emitter);

        verify(sourceCardBuilder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
        verify(callback, never()).trySetCards(any());
        verify(llmService, times(1)).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    /** 3. distinctChunks 为空（MCP-Only 场景）→ 跳过 builder，但 LLM 流必须照常启动。 */
    @Test
    void emptyDistinctChunks_mcpOnlyScenario_shouldNotCallBuilder_butLlmStillStarts() {
        RetrievalContext ctx = ctxMcpOnly();
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        service.streamChat("q", "cid-3", null, false, emitter);

        verify(sourceCardBuilder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
        verify(callback, never()).trySetCards(any());
        verify(llmService, times(1)).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    /** 4. builder 返回空 cards 列表 → builder 被调用一次，但不 trySet/不 emit，LLM 流照常启动。 */
    @Test
    void emptyCards_shouldNotEmit_butLlmStillStarts() {
        List<RetrievedChunk> chunks = List.of(chunk("c1", "d1"));
        RetrievalContext ctx = ctxWithKbChunks(chunks);
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        when(sourceCardBuilder.build(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.streamChat("q", "cid-4", null, false, emitter);

        verify(sourceCardBuilder, times(1)).build(any(), anyInt(), anyInt());
        verify(callback, never()).trySetCards(any());
        verify(callback, never()).emitSources(any());
        verify(llmService, times(1)).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    /** 5. ctx.isEmpty() 早返分支（"未检索到..."）→ 既不走 builder 也不走 LLM 流。 */
    @Test
    void ctxIsEmpty_shouldNotCallBuilder_andShouldNotStartLlm() {
        RetrievalContext ctx = ctxEmpty();
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        service.streamChat("q", "cid-5", null, false, emitter);

        verify(sourceCardBuilder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
        verify(llmService, never()).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    /**
     * 6. trySetCards 返回 false（CAS 失败）→ builder 被调用一次，trySetCards 被调用一次，
     * 但不触发 emitSources；LLM 流路径不受影响。
     */
    @Test
    void whenTrySetCardsReturnsFalse_shouldNotCallEmitSources() {
        // 防御：若 holder 已被设置（理论上不应发生，但锁住行为）
        List<RetrievedChunk> chunks = List.of(chunk("c1", "d1"));
        RetrievalContext ctx = ctxWithKbChunks(chunks);
        when(retrievalEngine.retrieve(any(), anyInt(), any(), any())).thenReturn(ctx);

        SourceCard card = SourceCard.builder().index(1).docId("d1").docName("D1").topScore(0.9f).chunks(List.of()).build();
        when(sourceCardBuilder.build(any(), anyInt(), anyInt())).thenReturn(List.of(card));
        // 模拟 CAS 失败：trySetCards 返回 false
        when(callback.trySetCards(any())).thenReturn(false);

        service.streamChat("q", "cid-6", null, false, emitter);

        verify(sourceCardBuilder, times(1)).build(any(), anyInt(), anyInt());
        verify(callback, times(1)).trySetCards(any());
        verify(callback, never()).emitSources(any()); // 关键断言：CAS 失败不触发推送
        // 回答路径不受影响：LLM 流仍然启动
        verify(llmService, times(1)).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }

    /**
     * 7. allSystemOnly=true 早返分支（L174-186）→ 走 streamSystemResponse，不调用 SourceCardBuilder，
     * 不调用 emitSources。注意：streamSystemResponse 内部仍会调用 llmService.streamChat，
     * 这是 SystemOnly 分支的正常 LLM 路径（不经过多通道检索，使用简化 System Prompt）。
     */
    @Test
    void systemOnly_shouldNotCallBuilder_andShouldNotEmitSources() {
        // SystemOnly 早返回：isSystemOnly 对所有 intent 返回 true
        when(intentResolver.isSystemOnly(any())).thenReturn(true);
        // promptTemplateLoader.load 在 streamSystemResponse 中被调用（customPrompt=null 时走默认 prompt）
        lenient().when(promptTemplateLoader.load(anyString())).thenReturn("system-prompt");

        service.streamChat("q", "cid-7", null, false, emitter);

        // sources 路径完全不触发
        verify(sourceCardBuilder, never()).build(any(), anyInt(), anyInt());
        verify(callback, never()).emitSources(any());
        // streamSystemResponse 内部调用 llmService.streamChat（SystemOnly 依然需要 LLM 生成回答）
        verify(llmService, times(1)).streamChat(any(ChatRequest.class), any(StreamCallback.class));
    }
}
