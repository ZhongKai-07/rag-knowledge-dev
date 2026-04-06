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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * OpenAI 协议风格 SSE 解析器
 * 支持从 delta/message 中提取 content，以及可选的 reasoning_content
 */
final class OpenAIStyleSseParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARKER = "[DONE]";

    private OpenAIStyleSseParser() {
    }

    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        String payload = line.trim();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        JsonObject obj = gson.fromJson(payload, JsonObject.class);

        // 提取 usage（部分 API 在最后一帧或独立帧中返回）
        TokenUsage usage = extractUsage(obj);

        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            // 可能是只包含 usage 的帧（无 choices）
            if (usage != null) {
                return new ParsedEvent(null, null, false, false, usage);
            }
            return ParsedEvent.empty();
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        String content = extractText(choice0, "content");
        String reasoning = reasoningEnabled ? extractText(choice0, "reasoning_content") : null;
        boolean finished = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, finished, false, usage);
    }

    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    private static String extractText(JsonObject choice, String fieldName) {
        if (choice == null) {
            return null;
        }
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    private static TokenUsage extractUsage(JsonObject obj) {
        if (obj == null || !obj.has("usage") || obj.get("usage").isJsonNull()) {
            return null;
        }
        JsonObject usage = obj.getAsJsonObject("usage");
        if (usage == null) {
            return null;
        }
        int prompt = usage.has("prompt_tokens") && !usage.get("prompt_tokens").isJsonNull()
                ? usage.get("prompt_tokens").getAsInt() : 0;
        int completion = usage.has("completion_tokens") && !usage.get("completion_tokens").isJsonNull()
                ? usage.get("completion_tokens").getAsInt() : 0;
        return TokenUsage.of(prompt, completion);
    }

    /**
     * @param content      增量文本
     * @param reasoning    思考内容
     * @param finished     finish_reason 已出现（但流可能还有 usage 帧未读完）
     * @param streamEnded  [DONE] 标记，流真正结束
     * @param usage        token 用量
     */
    record ParsedEvent(String content, String reasoning, boolean finished, boolean streamEnded, TokenUsage usage) {

        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false, false, null);
        }

        static ParsedEvent done() {
            return new ParsedEvent(null, null, false, true, null);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }

        boolean hasUsage() {
            return usage != null;
        }

        /**
         * 流是否完全结束（[DONE] 或 finish_reason）
         */
        boolean completed() {
            return finished || streamEnded;
        }
    }
}
