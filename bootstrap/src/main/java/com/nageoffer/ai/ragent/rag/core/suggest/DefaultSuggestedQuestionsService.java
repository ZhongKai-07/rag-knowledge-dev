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

package com.nageoffer.ai.ragent.rag.core.suggest;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.SUGGESTED_QUESTIONS_PROMPT_PATH;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSuggestedQuestionsService implements SuggestedQuestionsService {

    private static final int ANSWER_TAIL_LIMIT = 500;
    private static final int CHUNK_SNIPPET_LIMIT = 150;

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    @RagTraceNode(name = "suggested-chat", type = "SUGGESTION")
    public List<String> generate(SuggestionContext context, String answer) {
        try {
            String template = promptTemplateLoader.load(SUGGESTED_QUESTIONS_PROMPT_PATH);
            String rendered = renderPrompt(template, context, answer);

            ChatRequest req = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(rendered)))
                    .temperature(0.1D)
                    .topP(0.3D)
                    .thinking(false)
                    .maxTokens(ragConfigProperties.getSuggestionsMaxOutputTokens())
                    .build();

            String modelId = ragConfigProperties.getSuggestionsModelId();
            String raw = llmService.chat(req, StrUtil.isBlank(modelId) ? null : modelId);

            return parseQuestions(raw);
        } catch (Exception e) {
            log.warn("生成推荐问题失败", e);
            return List.of();
        }
    }

    private String renderPrompt(String template, SuggestionContext ctx, String answer) {
        return PromptTemplateUtils.fillSlots(template, Map.of(
                "question", StrUtil.nullToEmpty(ctx.question()),
                "history", renderHistory(ctx.history()),
                "chunks", renderChunks(ctx.topChunks()),
                "answer", truncateTail(answer, ANSWER_TAIL_LIMIT)
        ));
    }

    private String renderHistory(List<ChatMessage> history) {
        if (CollUtil.isEmpty(history)) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            if (m.getRole() == ChatMessage.Role.SYSTEM) continue;
            sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private String renderChunks(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (RetrievedChunk c : chunks) {
            String id = StrUtil.nullToEmpty(c.getId());
            String text = StrUtil.nullToEmpty(c.getText());
            if (text.length() > CHUNK_SNIPPET_LIMIT) {
                text = text.substring(0, CHUNK_SNIPPET_LIMIT) + "…";
            }
            sb.append("[").append(id).append("] ").append(text).append("\n");
        }
        return sb.toString().trim();
    }

    private String truncateTail(String s, int limit) {
        if (s == null) return "";
        if (s.length() <= limit) return s;
        return s.substring(s.length() - limit);
    }

    private List<String> parseQuestions(String raw) {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JsonElement root = JsonParser.parseString(cleaned);
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("questions") || !obj.get("questions").isJsonArray()) {
            return List.of();
        }
        JsonArray arr = obj.getAsJsonArray("questions");
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }
}
