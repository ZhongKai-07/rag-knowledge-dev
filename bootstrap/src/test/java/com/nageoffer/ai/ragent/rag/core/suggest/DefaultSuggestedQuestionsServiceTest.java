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

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultSuggestedQuestionsServiceTest {

    private LLMService llmService;
    private PromptTemplateLoader promptTemplateLoader;
    private RAGConfigProperties ragConfigProperties;
    private DefaultSuggestedQuestionsService service;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        promptTemplateLoader = mock(PromptTemplateLoader.class);
        ragConfigProperties = new RAGConfigProperties();
        ragConfigProperties.setSuggestionsEnabled(true);
        ragConfigProperties.setSuggestionsModelId("qwen-turbo");
        ragConfigProperties.setSuggestionsMaxOutputTokens(150);
        ragConfigProperties.setSuggestionsTimeoutMs(5000L);

        when(promptTemplateLoader.load(anyString()))
                .thenReturn("template {question} {history} {chunks} {answer}");

        service = new DefaultSuggestedQuestionsService(llmService, promptTemplateLoader, ragConfigProperties);
    }

    @Test
    void happy_path_returns_three_questions_from_valid_json() {
        when(llmService.chat(any(ChatRequest.class), anyString()))
                .thenReturn("{\"questions\":[\"什么是 JIT？\",\"Java 和 Python 比较\",\"如何优化 GC？\"]}");

        SuggestionContext ctx = new SuggestionContext("Java 有哪些特点", List.of(), List.of(), true);
        List<String> result = service.generate(ctx, "Java 是一门面向对象的语言，支持 JIT 编译...");

        assertEquals(3, result.size());
        assertEquals("什么是 JIT？", result.get(0));
        assertEquals("Java 和 Python 比较", result.get(1));
        assertEquals("如何优化 GC？", result.get(2));
    }

    @Test
    void tolerates_markdown_code_fence_wrapping_json() {
        when(llmService.chat(any(ChatRequest.class), anyString()))
                .thenReturn("```json\n{\"questions\":[\"a\",\"b\",\"c\"]}\n```");

        SuggestionContext ctx = new SuggestionContext("q", List.of(), List.of(), true);
        List<String> result = service.generate(ctx, "answer");

        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
    }
}
