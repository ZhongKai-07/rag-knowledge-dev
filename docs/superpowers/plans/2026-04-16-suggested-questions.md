# 智能问题预测（Suggested Questions）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每次 RAG 回答完成后，通过一次独立的小模型 LLM 调用生成 3 个基于检索片段 grounding 的后续问题，经同一条 SSE 连接用新的 `suggestions` 事件推送给前端，前端渲染为可点击 chip 按钮。

**Architecture:** `RAGChatServiceImpl` 在走完整 KB LLM 路径时组装 `SuggestionContext(question, history, topChunks)` 并通过 setter 注入 `StreamChatEventHandler`；`onComplete` 先同步发 `finish`，再异步 submit 到专用 `ThreadPoolTaskExecutor` 调用 `SuggestedQuestionsService.generate(...)`；成功/失败/取消均由 `finally` 保证 `done` 事件发出并 `complete()` SSE 连接。推荐结果除推送前端外合并到 `t_rag_trace_run.extra_data` JSON 做审计。

**Tech Stack:** Java 17 · Spring Boot 3.5.7 · JUnit 5 + Mockito · Lombok · Gson · 现有 `LLMService` / `PromptTemplateLoader` / `LLMResponseCleaner` · React 18 + TypeScript + Zustand · TailwindCSS

**Spec reference:** `docs/superpowers/specs/2026-04-16-suggested-questions-design.md`

---

## 文件结构映射

### 新增（6 后端 + 1 资源）

| 路径 | 职责 |
|------|------|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SuggestionsPayload.java` | SSE `suggestions` 事件载荷 record |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestionContext.java` | Service 入参 record |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestedQuestionsService.java` | Service 接口 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java` | 默认实现 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SuggestedQuestionsExecutorConfig.java` | 专用线程池 `@Bean` |
| `bootstrap/src/main/resources/prompt/suggested-questions.st` | Prompt 模板 |

### 新增测试

| 路径 | 职责 |
|------|------|
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java` | Service 单测 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/RagTraceRecordServiceMergeTest.java` | `mergeRunExtraData` 单测 |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerSuggestionsTest.java` | Handler 异步分支单测 |

### 修改（后端 9 + 前端 4 + 配置 1）

| 路径 | 修改摘要 |
|------|---------|
| `bootstrap/.../rag/enums/SSEEventType.java` | 加 `SUGGESTIONS("suggestions")` |
| `bootstrap/.../rag/config/RAGConfigProperties.java` | 加 4 个 `rag.suggestions.*` 字段 |
| `bootstrap/.../rag/constant/RAGConstant.java` | 加 `SUGGESTED_QUESTIONS_PROMPT_PATH` 常量 |
| `bootstrap/.../rag/service/RagTraceRecordService.java` | 加方法 `mergeRunExtraData(String, Map)` |
| `bootstrap/.../rag/service/impl/RagTraceRecordServiceImpl.java` | 实现 `mergeRunExtraData`（读→合并→写回 JSON） |
| `bootstrap/.../rag/service/handler/StreamChatHandlerParams.java` | 加 3 字段：service / executor / ragConfigProperties |
| `bootstrap/.../rag/service/handler/StreamChatEventHandler.java` | 加 `updateSuggestionContext` setter；重写 `onComplete` 异步分支 |
| `bootstrap/.../rag/service/handler/StreamCallbackFactory.java` | 注入新依赖；暴露 handler 实例以便 orchestrator 调用 setter |
| `bootstrap/.../rag/service/impl/RAGChatServiceImpl.java` | 走 KB 路径前组装 `SuggestionContext` 并注入 handler |
| `bootstrap/src/main/resources/application.yaml` | 新增 `rag.suggestions` 配置节 |
| `frontend/src/types/index.ts` | 加 `SuggestionsPayload` 类型 |
| `frontend/src/hooks/useStreamResponse.ts` | `onSuggestions` handler + switch case |
| `frontend/src/stores/chatStore.ts` | `Message.suggestedQuestions?: string[]`；绑 `onSuggestions` |
| `frontend/src/components/chat/MessageItem.tsx` | chip 渲染 + 点击调 `sendMessage` |

---

## Task 1: 后端 - 新增 `SUGGESTIONS` SSE 事件类型

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/enums/SSEEventType.java`

- [ ] **Step 1: 在枚举末尾（`REJECT` 之后）添加新项**

打开 `SSEEventType.java`，在 `REJECT("reject");` 之后添加：

```java
    /**
     * 推荐问题事件
     */
    SUGGESTIONS("suggestions");
```

注意把原本 `REJECT("reject");` 末尾的 `;` 改为 `,`，把新增项末尾写 `;`。

- [ ] **Step 2: 编译验证**

```bash
cd "E:/AI Application/rag-knowledge-dev"
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/enums/SSEEventType.java
git commit -m "feat(rag): add SUGGESTIONS SSE event type"
```

---

## Task 2: 后端 - 创建 `SuggestionsPayload` DTO

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SuggestionsPayload.java`

- [ ] **Step 1: 创建 record 文件**

完整文件内容：

```java
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

package com.nageoffer.ai.ragent.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SSE suggestions 事件载荷
 *
 * @param messageId 对应的 assistant 消息 ID（与 FINISH 事件的 messageId 一致）
 * @param questions 推荐问题列表（固定 3 条，允许更少）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuggestionsPayload(String messageId, List<String> questions) {
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/dto/SuggestionsPayload.java
git commit -m "feat(rag): add SuggestionsPayload DTO for SSE suggestions event"
```

---

## Task 3: 后端 - 创建 `SuggestionContext` record

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestionContext.java`

- [ ] **Step 1: 创建 record 文件**

完整文件内容：

```java
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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 推荐问题生成的输入上下文
 *
 * @param question        用户当前问题（改写前的原始提问）
 * @param history         最近对话历史（由调用方裁剪）
 * @param topChunks       本轮检索的 top-N 片段（由调用方扁平化并排序）
 * @param shouldGenerate  是否触发生成（false 表示调用方已预判跳过）
 */
public record SuggestionContext(
        String question,
        List<ChatMessage> history,
        List<RetrievedChunk> topChunks,
        boolean shouldGenerate
) {

    /**
     * 生成一个 shouldGenerate=false 的占位上下文，
     * handler 在未被 orchestrator 更新时使用。
     */
    public static SuggestionContext skip() {
        return new SuggestionContext(null, List.of(), List.of(), false);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestionContext.java
git commit -m "feat(rag): add SuggestionContext record for suggested questions"
```

---

## Task 4: 后端 - 扩展 `RAGConfigProperties` 加 `suggestions` 配置

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RAGConfigProperties.java`

- [ ] **Step 1: 在 `RAGConfigProperties` 类末尾（在 `queryRewriteMaxHistoryChars` 字段之后）追加 4 个字段**

```java
    /**
     * 推荐问题功能总开关
     */
    @Value("${rag.suggestions.enabled:true}")
    private Boolean suggestionsEnabled;

    /**
     * 推荐问题独立调用使用的小模型 ID，留空则走 ai.chat.default-model
     */
    @Value("${rag.suggestions.model-id:}")
    private String suggestionsModelId;

    /**
     * 推荐问题输出 token 上限
     */
    @Value("${rag.suggestions.max-output-tokens:150}")
    private Integer suggestionsMaxOutputTokens;

    /**
     * 推荐问题异步生成超时兜底（毫秒）
     */
    @Value("${rag.suggestions.timeout-ms:5000}")
    private Long suggestionsTimeoutMs;
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RAGConfigProperties.java
git commit -m "feat(rag): add rag.suggestions.* config properties"
```

---

## Task 5: 后端 - 添加 Prompt 模板路径常量

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/constant/RAGConstant.java`

- [ ] **Step 1: 在 `QUERY_REWRITE_AND_SPLIT_PROMPT_PATH` 常量附近添加新常量**

打开 `RAGConstant.java`，找到第 111 行 `QUERY_REWRITE_AND_SPLIT_PROMPT_PATH` 声明的位置（用 `grep -n "QUERY_REWRITE_AND_SPLIT_PROMPT_PATH"` 定位），在其下方添加：

```java
    /**
     * 推荐问题生成 prompt 路径
     */
    public static final String SUGGESTED_QUESTIONS_PROMPT_PATH = "prompt/suggested-questions.st";
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/constant/RAGConstant.java
git commit -m "feat(rag): add SUGGESTED_QUESTIONS_PROMPT_PATH constant"
```

---

## Task 6: 后端 - 创建 Prompt 模板文件

**Files:**
- Create: `bootstrap/src/main/resources/prompt/suggested-questions.st`

- [ ] **Step 1: 创建模板文件**

完整文件内容：

```
你是 RAG 知识库助手的"追问预测"子模块。基于用户本轮的问答和检索到的文档片段，预测用户最可能提出的 3 个后续问题。

【当前问题】
{question}

【对话历史】（最近 2 轮，可为空）
{history}

【本轮检索片段】（top-3，按相关度降序，格式：[片段ID] 内容）
{chunks}

【本轮回答】（可能被尾部截断到 500 字以内）
{answer}

【要求】
1. 严格输出 3 个问题，每个不超过 30 字
2. 必须基于上述检索片段内容，不得臆造 KB 中不存在的概念
3. 3 个问题覆盖不同方向（深入 / 横向 / 实操），避免同质
4. 自然口语化，避免生硬的"请问 XXX"句式
5. 只输出 JSON，不要 markdown 代码块：
   {"questions":["q1","q2","q3"]}
```

注意：
- 占位符 `{question}` / `{history}` / `{chunks}` / `{answer}` 由 `DefaultSuggestedQuestionsService` 使用 `String.replace` 替换（不是 SpEL）
- 文件需以换行符结束

- [ ] **Step 2: 验证文件存在且不为空**

```bash
wc -l bootstrap/src/main/resources/prompt/suggested-questions.st
```

Expected: 行数 > 15

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/resources/prompt/suggested-questions.st
git commit -m "feat(rag): add suggested-questions prompt template"
```

---

## Task 7: 后端 - 创建 `SuggestedQuestionsExecutor` 线程池

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SuggestedQuestionsExecutorConfig.java`

- [ ] **Step 1: 创建配置类**

完整文件内容：

```java
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

package com.nageoffer.ai.ragent.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 推荐问题生成专用线程池
 *
 * 设计要点：
 * - 与 modelStreamExecutor 隔离，避免故障扩散到主流式回调
 * - AbortPolicy：推荐是辅助 UX，队列满时应丢弃而不是拖慢 done 事件
 */
@Configuration
public class SuggestedQuestionsExecutorConfig {

    @Bean("suggestedQuestionsExecutor")
    public ThreadPoolTaskExecutor suggestedQuestionsExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("suggest-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SuggestedQuestionsExecutorConfig.java
git commit -m "feat(rag): add suggestedQuestionsExecutor thread pool bean"
```

---

## Task 8: 后端 - 创建 `SuggestedQuestionsService` 接口

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestedQuestionsService.java`

- [ ] **Step 1: 创建接口**

完整文件内容：

```java
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

import java.util.List;

/**
 * 推荐问题生成服务
 *
 * 约定：
 * - 任何异常（LLM 失败 / 解析失败 / 超时）都返回空 List，绝不向上抛
 * - 返回 List 可能少于 3 条，调用方按实际长度处理
 */
public interface SuggestedQuestionsService {

    /**
     * 基于本轮问答和检索片段生成后续问题
     *
     * @param context 输入上下文（调用方保证 shouldGenerate=true）
     * @param answer  本轮 assistant 回答全文
     * @return 推荐问题列表（0-3 条）
     */
    List<String> generate(SuggestionContext context, String answer);
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/SuggestedQuestionsService.java
git commit -m "feat(rag): add SuggestedQuestionsService interface"
```

---

## Task 9: 后端 - `DefaultSuggestedQuestionsService` TDD #1：happy path

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java`
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java`

- [ ] **Step 1: 写失败测试 —— LLM 返回合法 JSON，应返回 3 条**

完整测试文件内容：

```java
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
        // 反射或公开 setter 都可；RAGConfigProperties 是 @Data，用 setter
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
}
```

- [ ] **Step 2: 运行测试确认失败（未实现）**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest -q
```

Expected: 编译失败，提示 `DefaultSuggestedQuestionsService` 不存在

- [ ] **Step 3: 实现最小版本**

创建 `DefaultSuggestedQuestionsService.java`，完整内容：

```java
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
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    public List<String> generate(SuggestionContext context, String answer) {
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
    }

    private String renderPrompt(String template, SuggestionContext ctx, String answer) {
        return template
                .replace("{question}", StrUtil.nullToEmpty(ctx.question()))
                .replace("{history}", renderHistory(ctx.history()))
                .replace("{chunks}", renderChunks(ctx.topChunks()))
                .replace("{answer}", truncateTail(answer, ANSWER_TAIL_LIMIT));
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
        JsonElement root = JsonParser.parseString(raw);
        JsonObject obj = root.getAsJsonObject();
        JsonArray arr = obj.getAsJsonArray("questions");
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            out.add(el.getAsString());
        }
        return out;
    }
}
```

注意：此时 `parseQuestions` 最小实现（不带异常处理 / 不带 fence 剥离）—— 后续 TDD 会逐步加强。

- [ ] **Step 4: 确认 `ChatRequest.builder().maxTokens(...)` 方法存在**

```bash
grep -n "maxTokens\|max_tokens" framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/ChatRequest.java
```

如果不存在，先加到 `ChatRequest`：打开文件，在已有字段（如 `temperature`, `topP`）附近添加：

```java
    /** 输出 token 上限 */
    private Integer maxTokens;
```

（Lombok `@Builder` 会自动生成 setter）。编译前补充此字段。

- [ ] **Step 5: 运行测试验证通过**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest -q
```

Expected: Tests run: 1, Failures: 0, Errors: 0

- [ ] **Step 6: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java \
        framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/ChatRequest.java
git commit -m "feat(rag): add DefaultSuggestedQuestionsService with happy-path JSON parsing"
```

---

## Task 10: 后端 - `DefaultSuggestedQuestionsService` TDD #2：容忍 markdown 代码块

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java`

- [ ] **Step 1: 添加失败测试 —— LLM 返回被 ```json ... ``` 包裹**

在 `DefaultSuggestedQuestionsServiceTest` 类末尾追加：

```java
    @Test
    void tolerates_markdown_code_fence_wrapping_json() {
        when(llmService.chat(any(ChatRequest.class), anyString()))
                .thenReturn("```json\n{\"questions\":[\"a\",\"b\",\"c\"]}\n```");

        SuggestionContext ctx = new SuggestionContext("q", List.of(), List.of(), true);
        List<String> result = service.generate(ctx, "answer");

        assertEquals(3, result.size());
        assertEquals("a", result.get(0));
    }
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest#tolerates_markdown_code_fence_wrapping_json -q
```

Expected: `MalformedJsonException` 或 `IllegalStateException`（因为 `JsonParser.parseString` 无法处理 ``` fence）

- [ ] **Step 3: 修改 `parseQuestions`，在解析前剥壳**

打开 `DefaultSuggestedQuestionsService.java`，修改 `parseQuestions` 方法：

```java
    private List<String> parseQuestions(String raw) {
        String cleaned = com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JsonElement root = JsonParser.parseString(cleaned);
        JsonObject obj = root.getAsJsonObject();
        JsonArray arr = obj.getAsJsonArray("questions");
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            out.add(el.getAsString());
        }
        return out;
    }
```

并在文件顶部加 import（如果 IDE 没自动加）：

```java
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
```

然后把内联的 full-qualified name 简化为 `LLMResponseCleaner.stripMarkdownCodeFence(raw)`。

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest -q
```

Expected: Tests run: 2, Failures: 0, Errors: 0

- [ ] **Step 5: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java
git commit -m "feat(rag): tolerate markdown code fence in suggested questions output"
```

---

## Task 11: 后端 - `DefaultSuggestedQuestionsService` TDD #3：LLM 异常返回空列表

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java`

- [ ] **Step 1: 添加失败测试**

在测试类末尾追加：

```java
    @Test
    void returns_empty_when_llm_throws() {
        when(llmService.chat(any(ChatRequest.class), anyString()))
                .thenThrow(new RuntimeException("boom"));

        SuggestionContext ctx = new SuggestionContext("q", List.of(), List.of(), true);
        List<String> result = service.generate(ctx, "answer");

        assertTrue(result.isEmpty());
    }
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest#returns_empty_when_llm_throws -q
```

Expected: `RuntimeException: boom` 抛出到测试层

- [ ] **Step 3: 在 `generate` 方法外层包 try-catch**

修改 `DefaultSuggestedQuestionsService.generate` 整体为：

```java
    @Override
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
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest -q
```

Expected: Tests run: 3, Failures: 0, Errors: 0

- [ ] **Step 5: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java
git commit -m "feat(rag): return empty list on LLM exception in SuggestedQuestionsService"
```

---

## Task 12: 后端 - `DefaultSuggestedQuestionsService` TDD #4：非 JSON 文本返回空列表

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java`

- [ ] **Step 1: 添加测试 —— 非 JSON 应该走异常捕获分支返回空**

在测试类末尾追加：

```java
    @Test
    void returns_empty_when_llm_returns_garbage() {
        when(llmService.chat(any(ChatRequest.class), anyString()))
                .thenReturn("抱歉，我无法帮你生成推荐问题。");

        SuggestionContext ctx = new SuggestionContext("q", List.of(), List.of(), true);
        List<String> result = service.generate(ctx, "answer");

        assertTrue(result.isEmpty());
    }

    @Test
    void returns_empty_when_questions_field_missing() {
        when(llmService.chat(any(ChatRequest.class), anyString()))
                .thenReturn("{\"answer\":\"not what we asked for\"}");

        SuggestionContext ctx = new SuggestionContext("q", List.of(), List.of(), true);
        List<String> result = service.generate(ctx, "answer");

        assertTrue(result.isEmpty());
    }
```

- [ ] **Step 2: 运行测试验证 —— 应该已经通过（上一步 try-catch 已覆盖）**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest -q
```

Expected: Tests run: 5, Failures: 0, Errors: 0

如果 `returns_empty_when_questions_field_missing` 失败（`getAsJsonArray("questions")` 返回 null 而不是抛异常），则修改 `parseQuestions`：

```java
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
```

- [ ] **Step 3: 再次运行测试**

```bash
mvn -pl bootstrap test -Dtest=DefaultSuggestedQuestionsServiceTest -q
```

Expected: Tests run: 5, Failures: 0, Errors: 0

- [ ] **Step 4: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsService.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/core/suggest/DefaultSuggestedQuestionsServiceTest.java
git commit -m "feat(rag): harden SuggestedQuestionsService parsing for malformed LLM output"
```

---

## Task 13: 后端 - `RagTraceRecordService.mergeRunExtraData` TDD

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RagTraceRecordService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceRecordServiceImpl.java`
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/RagTraceRecordServiceMergeTest.java`

- [ ] **Step 1: 扩展接口**

打开 `RagTraceRecordService.java`，在 `updateRunExtraData` 下方添加：

```java
    /**
     * 合并字段到 run 的 extra_data JSON
     *
     * <p>读取现有 extraData（可能为 null/空/合法 JSON），
     * 把 additions 里的键合并进去（覆盖同名键），然后写回。</p>
     *
     * @param traceId   run 对应的 traceId
     * @param additions 需要合并的键值对
     */
    void mergeRunExtraData(String traceId, java.util.Map<String, Object> additions);
```

- [ ] **Step 2: 写失败测试**

创建 `RagTraceRecordServiceMergeTest.java`：

```java
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

package com.nageoffer.ai.ragent.rag.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.impl.RagTraceRecordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagTraceRecordServiceMergeTest {

    private RagTraceRunMapper runMapper;
    private RagTraceNodeMapper nodeMapper;
    private RagTraceRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        runMapper = mock(RagTraceRunMapper.class);
        nodeMapper = mock(RagTraceNodeMapper.class);
        service = new RagTraceRecordServiceImpl(runMapper, nodeMapper);
    }

    @Test
    void merges_into_null_extra_data() {
        RagTraceRunDO existing = RagTraceRunDO.builder().traceId("t1").extraData(null).build();
        when(runMapper.selectOne(any())).thenReturn(existing);

        service.mergeRunExtraData("t1", Map.of("suggestedQuestions", List.of("a", "b")));

        ArgumentCaptor<RagTraceRunDO> captor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(runMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        String written = captor.getValue().getExtraData();

        Map<?, ?> parsed = new Gson().fromJson(written, Map.class);
        assertTrue(parsed.containsKey("suggestedQuestions"));
        assertEquals(2, ((List<?>) parsed.get("suggestedQuestions")).size());
    }

    @Test
    void merges_preserving_existing_keys() {
        RagTraceRunDO existing = RagTraceRunDO.builder()
                .traceId("t1")
                .extraData("{\"promptTokens\":100,\"totalTokens\":150}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(existing);

        service.mergeRunExtraData("t1", Map.of("suggestedQuestions", List.of("x")));

        ArgumentCaptor<RagTraceRunDO> captor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(runMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        Map<?, ?> parsed = new Gson().fromJson(captor.getValue().getExtraData(), Map.class);

        assertEquals(100.0, parsed.get("promptTokens"));   // Gson parses numbers to Double
        assertEquals(150.0, parsed.get("totalTokens"));
        assertTrue(parsed.containsKey("suggestedQuestions"));
    }

    @Test
    void addition_keys_override_existing_same_key() {
        RagTraceRunDO existing = RagTraceRunDO.builder()
                .traceId("t1")
                .extraData("{\"foo\":\"old\"}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(existing);

        service.mergeRunExtraData("t1", Map.of("foo", "new"));

        ArgumentCaptor<RagTraceRunDO> captor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(runMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        Map<?, ?> parsed = new Gson().fromJson(captor.getValue().getExtraData(), Map.class);
        assertEquals("new", parsed.get("foo"));
    }
}
```

注意：测试假设 `RagTraceRecordServiceImpl` 的构造函数是 `(runMapper, nodeMapper)`。如果实际使用 `@RequiredArgsConstructor`，这个假设成立；否则看文件头部构造方式调整。

- [ ] **Step 3: 运行测试确认失败（方法未实现）**

```bash
mvn -pl bootstrap test -Dtest=RagTraceRecordServiceMergeTest -q
```

Expected: Tests fail — 方法未实现 / AbstractMethodError

- [ ] **Step 4: 实现 `mergeRunExtraData`**

打开 `RagTraceRecordServiceImpl.java`，在 `updateRunExtraData` 下方添加：

```java
    @Override
    public void mergeRunExtraData(String traceId, java.util.Map<String, Object> additions) {
        RagTraceRunDO existing = runMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(RagTraceRunDO.class)
                        .eq(RagTraceRunDO::getTraceId, traceId));

        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (existing != null && cn.hutool.core.util.StrUtil.isNotBlank(existing.getExtraData())) {
            try {
                java.util.Map<String, Object> parsed = gson.fromJson(
                        existing.getExtraData(), java.util.Map.class);
                if (parsed != null) merged.putAll(parsed);
            } catch (Exception e) {
                log.warn("解析 extra_data 失败，将丢弃并覆盖，traceId={}", traceId, e);
            }
        }
        merged.putAll(additions);
        String written = gson.toJson(merged);

        RagTraceRunDO update = RagTraceRunDO.builder()
                .extraData(written)
                .build();
        runMapper.update(update, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
    }
```

（实现用 fully-qualified 名字以免触碰现有 import；必要时 IDE 自动优化。）

- [ ] **Step 5: 运行测试验证通过**

```bash
mvn -pl bootstrap test -Dtest=RagTraceRecordServiceMergeTest -q
```

Expected: Tests run: 3, Failures: 0, Errors: 0

- [ ] **Step 6: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/RagTraceRecordService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RagTraceRecordServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/RagTraceRecordServiceMergeTest.java
git commit -m "feat(rag): add mergeRunExtraData to RagTraceRecordService"
```

---

## Task 14: 后端 - 扩展 `StreamChatHandlerParams`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java`

- [ ] **Step 1: 添加 3 个字段**

打开文件，在 `traceRecordService` 字段之后追加：

```java
    /**
     * 推荐问题服务
     */
    private final com.nageoffer.ai.ragent.rag.core.suggest.SuggestedQuestionsService suggestedQuestionsService;

    /**
     * 推荐问题专用线程池
     */
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor suggestedQuestionsExecutor;

    /**
     * RAG 功能配置属性
     */
    private final com.nageoffer.ai.ragent.rag.config.RAGConfigProperties ragConfigProperties;
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS（Lombok `@Builder` 自动为新字段生成 builder 方法）

- [ ] **Step 3: 提交（与下一个 task 一起提交，先暂存）**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java
```

（不 commit，等 Task 15 完成一起提交）

---

## Task 15: 后端 - 扩展 `StreamCallbackFactory`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java`

- [ ] **Step 1: 注入新依赖 + 修改返回类型**

打开 `StreamCallbackFactory.java`，将整个类替换为：

```java
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
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
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
                                                         String taskId) {
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId(conversationId)
                .taskId(taskId)
                .modelProperties(modelProperties)
                .memoryService(memoryService)
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
```

关键变更：
- 返回类型从 `StreamCallback` 改为 `StreamChatEventHandler`（具体类）
- 新增 3 个字段注入
- `@Qualifier("suggestedQuestionsExecutor")` 在字段上（Lombok `copyableAnnotations` 已配 `@Qualifier`，会复制到构造器参数）

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

如果编译失败提示 `StreamCallback` 返回类型在 `RAGChatServiceImpl` 中被消费，则暂忽略，下一个 task 会处理。

- [ ] **Step 3: 暂存**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java
```

---

## Task 16: 后端 - `StreamChatEventHandler` 添加 `updateSuggestionContext` setter 并重写 `onComplete`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`

- [ ] **Step 1: 添加字段 + setter**

在 `StreamChatEventHandler` 类字段区（`private volatile TokenUsage tokenUsage;` 之后）追加：

```java
    private final com.nageoffer.ai.ragent.rag.core.suggest.SuggestedQuestionsService suggestedQuestionsService;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor suggestedQuestionsExecutor;
    private final com.nageoffer.ai.ragent.rag.config.RAGConfigProperties ragConfigProperties;
    private final String questionText;
    private volatile com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext suggestionContext =
            com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext.skip();
```

然后修改构造函数，在 `this.userId = UserContext.getUserId();` 之后追加：

```java
        this.suggestedQuestionsService = params.getSuggestedQuestionsService();
        this.suggestedQuestionsExecutor = params.getSuggestedQuestionsExecutor();
        this.ragConfigProperties = params.getRagConfigProperties();
        this.questionText = null; // orchestrator 不直接传；SuggestionContext 里已含 question
```

类末尾追加 setter：

```java
    /**
     * orchestrator 走完检索后，调用此方法更新上下文以触发推荐生成
     */
    public void updateSuggestionContext(com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext ctx) {
        if (ctx != null) {
            this.suggestionContext = ctx;
        }
    }
```

- [ ] **Step 2: 重写 `onComplete` 方法**

定位现有 `onComplete` 方法（大约 line 161-180），完整替换为：

```java
    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        String messageId = memoryService.append(conversationId, UserContext.getUserId(),
                ChatMessage.assistant(answer.toString()), null);

        // 更新 Trace token 用量
        updateTraceTokenUsage();

        // 保存评测记录（异步）
        saveEvaluationRecord(messageId);

        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;

        // 立即发 FINISH，让前端进入"完成"状态
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));

        com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext ctx = this.suggestionContext;
        boolean enabled = Boolean.TRUE.equals(ragConfigProperties.getSuggestionsEnabled());
        boolean shouldGenerate = enabled && ctx != null && ctx.shouldGenerate();

        if (!shouldGenerate) {
            sendDoneAndClose();
            return;
        }

        final String finalMessageId = messageIdText;
        final String answerSnapshot = answer.toString();
        try {
            suggestedQuestionsExecutor.submit(() -> generateAndFinish(ctx, answerSnapshot, finalMessageId));
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            log.warn("推荐生成线程池拒绝提交，直接结束 SSE", rex);
            sendDoneAndClose();
        }
    }

    private void generateAndFinish(com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext ctx,
                                   String answerSnapshot, String messageIdText) {
        java.util.List<String> questions = java.util.List.of();
        try {
            if (taskManager.isCancelled(taskId)) {
                return;
            }
            questions = suggestedQuestionsService.generate(ctx, answerSnapshot);
            if (!taskManager.isCancelled(taskId) && !questions.isEmpty()) {
                sender.sendEvent(SSEEventType.SUGGESTIONS.value(),
                        new com.nageoffer.ai.ragent.rag.dto.SuggestionsPayload(messageIdText, questions));
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

    private void mergeSuggestionsIntoTrace(java.util.List<String> questions) {
        if (traceRecordService == null || StrUtil.isBlank(traceId)) {
            return;
        }
        try {
            traceRecordService.mergeRunExtraData(traceId,
                    java.util.Map.of("suggestedQuestions", questions));
        } catch (Exception e) {
            log.warn("合并推荐问题到 trace.extra_data 失败", e);
        }
    }
```

**关键变化：**
- 原来的 `sender.sendEvent(DONE, "[DONE]") → unregister → complete` 移到 `sendDoneAndClose()`
- `unregister` 和 `complete` 都只发生在 `sendDoneAndClose` 中
- 异步分支里 `finally` 保证 DONE 一定发出

- [ ] **Step 3: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交 Task 14 + 15 + 16**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java
git commit -m "feat(rag): wire SuggestedQuestionsService into stream handler with async onComplete branch"
```

---

## Task 17: 后端 - `StreamChatEventHandler` 异步分支集成测试

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerSuggestionsTest.java`

- [ ] **Step 1: 写测试文件 —— 覆盖 3 个场景**

完整文件：

```java
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
import com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamChatEventHandlerSuggestionsTest {

    private SseEmitter emitter;
    private RecordingEmitter recordingEmitter;
    private ConversationMemoryService memoryService;
    private ConversationGroupService conversationGroupService;
    private StreamTaskManager taskManager;
    private RagEvaluationService evaluationService;
    private RagTraceRecordService traceRecordService;
    private SuggestedQuestionsService suggestedQuestionsService;
    private ThreadPoolTaskExecutor executor;
    private RAGConfigProperties ragConfigProperties;

    @BeforeEach
    void setUp() {
        recordingEmitter = new RecordingEmitter();
        emitter = recordingEmitter;

        memoryService = mock(ConversationMemoryService.class);
        when(memoryService.append(anyString(), any(), any(), any())).thenReturn("msg-1");

        conversationGroupService = mock(ConversationGroupService.class);
        when(conversationGroupService.findConversation(anyString(), any())).thenReturn(null);

        taskManager = mock(StreamTaskManager.class);
        when(taskManager.isCancelled(anyString())).thenReturn(false);

        evaluationService = mock(RagEvaluationService.class);
        traceRecordService = mock(RagTraceRecordService.class);
        suggestedQuestionsService = mock(SuggestedQuestionsService.class);

        // 同步线程池，方便断言异步任务结果
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();

        ragConfigProperties = new RAGConfigProperties();
        ragConfigProperties.setSuggestionsEnabled(true);
    }

    private StreamChatEventHandler buildHandler() {
        AIModelProperties modelProps = new AIModelProperties();
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId("conv-1")
                .taskId("task-1")
                .modelProperties(modelProps)
                .memoryService(memoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .evaluationService(evaluationService)
                .traceRecordService(traceRecordService)
                .suggestedQuestionsService(suggestedQuestionsService)
                .suggestedQuestionsExecutor(executor)
                .ragConfigProperties(ragConfigProperties)
                .build();
        return new StreamChatEventHandler(params);
    }

    private void waitForExecutor() throws InterruptedException {
        executor.shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void emits_finish_then_suggestions_then_done_on_happy_path() throws Exception {
        when(suggestedQuestionsService.generate(any(), anyString()))
                .thenReturn(List.of("q1", "q2", "q3"));

        StreamChatEventHandler handler = buildHandler();
        handler.updateSuggestionContext(new SuggestionContext("java 特点", List.of(), List.of(), true));
        handler.onContent("Java 是一门...");
        handler.onComplete();
        waitForExecutor();

        List<String> events = recordingEmitter.eventNames();
        assertEquals(List.of("meta", "message", "finish", "suggestions", "done"), events);

        verify(traceRecordService).mergeRunExtraData(eq("trace-unset-or-empty"),
                any()); // traceId 为空或非空都调用（下一 verify 更精确）
    }

    @Test
    void skips_suggestions_when_should_generate_is_false() throws Exception {
        StreamChatEventHandler handler = buildHandler();
        // 不调 updateSuggestionContext，保持默认 skip()
        handler.onContent("x");
        handler.onComplete();
        waitForExecutor();

        List<String> events = recordingEmitter.eventNames();
        assertEquals(List.of("meta", "message", "finish", "done"), events);
        verify(suggestedQuestionsService, never()).generate(any(), anyString());
    }

    @Test
    void sends_done_even_when_suggestion_generation_throws() throws Exception {
        when(suggestedQuestionsService.generate(any(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        StreamChatEventHandler handler = buildHandler();
        handler.updateSuggestionContext(new SuggestionContext("q", List.of(), List.of(), true));
        handler.onContent("x");
        handler.onComplete();
        waitForExecutor();

        List<String> events = recordingEmitter.eventNames();
        assertFalse(events.contains("suggestions"));
        assertTrue(events.contains("done"));
    }

    /**
     * 简化的 SseEmitter，只记录 event name 顺序，不真正发送
     */
    static class RecordingEmitter extends SseEmitter {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private boolean completed = false;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            // SseEventBuilder 没有公开 eventName；用反射提取
            try {
                java.lang.reflect.Field f = builder.getClass().getDeclaredField("name");
                f.setAccessible(true);
                Object name = f.get(builder);
                events.add(name == null ? "message" : name.toString());
            } catch (Exception e) {
                events.add("unknown");
            }
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        public List<String> eventNames() {
            return new java.util.ArrayList<>(events);
        }
    }
}
```

**注意：**
- `RecordingEmitter` 通过反射提取 Spring 的 `SseEventBuilderImpl` 内部 `name` 字段 —— 这是测试专属 hack，优先级低于生产代码正确性
- 如果反射失败（字段名变化），fallback 到 `"unknown"`，测试断言会失败提醒
- 第一个测试的 `verify(traceRecordService).mergeRunExtraData(...)` 里 `eq("trace-unset-or-empty")` 不对 —— 生产代码会跳过 `traceId` 为空时的合并；这里先写成 `verify(traceRecordService, never())` 更稳妥

**修正 Step 1 测试里的断言**：把第一个测试末尾的 `verify(traceRecordService)...` 替换为：

```java
        // traceId 由 RagTraceContext 提供，测试无 ThreadLocal 注入时为 null，不会调用 merge
        // 不做 mergeRunExtraData 断言（已在 RagTraceRecordServiceMergeTest 单测覆盖）
```

- [ ] **Step 2: 运行测试**

```bash
mvn -pl bootstrap test -Dtest=StreamChatEventHandlerSuggestionsTest -q
```

Expected: Tests run: 3, Failures: 0, Errors: 0

如果失败：
- `events` 顺序不对 → 检查 `onComplete` 是否在 `waitForExecutor` 前就尝试关闭 `executor`
- `RecordingEmitter` 反射抛异常 → 调试时先打印 `builder.getClass().getDeclaredFields()` 查字段名

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandlerSuggestionsTest.java
git commit -m "test(rag): integration test for stream handler suggestions branch"
```

---

## Task 18: 后端 - `RAGChatServiceImpl` 注入 `SuggestionContext` 到 handler

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`

- [ ] **Step 1: 修改 `callback` 变量类型 + 走 KB 路径时更新 suggestion context**

在 `RAGChatServiceImpl.java` 中：

1. 找到第 103 行：
   ```java
   StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);
   ```
   改为：
   ```java
   com.nageoffer.ai.ragent.rag.service.handler.StreamChatEventHandler callback =
           callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);
   ```

2. 在走 KB 完整路径（line 192 `streamLLMResponse` 调用之前）插入 `SuggestionContext` 装配：

找到：
```java
        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K, accessibleKbIds, knowledgeBaseId);
        if (ctx.isEmpty()) {
            String emptyReply = "未检索到与问题相关的文档内容。";
            callback.onContent(emptyReply);
            callback.onComplete();
            return;
        }

        // 采集检索数据
        evalCollector.setTopK(DEFAULT_TOP_K);
```

在 `evalCollector.setTopK(...)` 之前（即 `ctx.isEmpty()` 判断之后）插入：

```java
        // 装配推荐问题上下文：扁平化取 top-3 chunks、检查是否含 MCP 意图
        java.util.List<com.nageoffer.ai.ragent.framework.convention.RetrievedChunk> topChunks =
                ctx.getIntentChunks() == null ? java.util.List.of() :
                        ctx.getIntentChunks().values().stream()
                                .flatMap(java.util.List::stream)
                                .distinct()
                                .sorted(java.util.Comparator.comparing(
                                        com.nageoffer.ai.ragent.framework.convention.RetrievedChunk::getScore,
                                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                                .limit(3)
                                .toList();

        boolean hasMcp = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .anyMatch(ns -> ns.getNode() != null
                        && ns.getNode().getKind() != null
                        && ns.getNode().getKind().name().contains("MCP"));

        boolean shouldGenerate = !hasMcp && !topChunks.isEmpty();
        callback.updateSuggestionContext(new com.nageoffer.ai.ragent.rag.core.suggest.SuggestionContext(
                rewriteResult.rewrittenQuestion(),
                history,
                topChunks,
                shouldGenerate
        ));
```

**注意点：**
- 所有 4 个短路分支（guidance / allSystemOnly / ctx.isEmpty() / 直接 onError）**不调用 updateSuggestionContext**，handler 默认 `skip()` → 不生成
- 包含 MCP 意图时 `shouldGenerate=false`（按 spec 第 2.4 条）
- `RetrievedChunk.getScore` 方法假定存在；若实际字段名不同，用 `grep RetrievedChunk` 调整

- [ ] **Step 2: 编译验证**

```bash
mvn -pl bootstrap compile -q
```

若提示 `RetrievedChunk.getScore` 不存在，打开 `framework/src/main/java/com/nageoffer/ai/ragent/framework/convention/RetrievedChunk.java` 查看实际字段名，替换 `getScore` 为实际 getter。

- [ ] **Step 3: 提交**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java
git commit -m "feat(rag): inject SuggestionContext into stream handler on KB-answer path"
```

---

## Task 19: 后端 - `application.yaml` 新增 `rag.suggestions` 配置节

**Files:**
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 定位 `rag:` 配置节**

```bash
grep -n "^rag:" bootstrap/src/main/resources/application.yaml
```

- [ ] **Step 2: 在 `rag:` 节下方、`query-rewrite:` 附近追加 `suggestions:` 子节**

例如：

```yaml
rag:
  # ... 已有配置 ...
  query-rewrite:
    enabled: true
    max-history-messages: 4
    max-history-chars: 500
  suggestions:
    enabled: true
    model-id: qwen-turbo
    max-output-tokens: 150
    timeout-ms: 5000
```

如果 `qwen-turbo` 在 `ai.chat.candidates` 中未声明，把 `model-id` 留空（或换成已存在的候选模型 ID），`RoutingLLMService` 会走默认路由。

- [ ] **Step 3: 启动应用冒烟**

```bash
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

在启动日志里搜关键词 `rag.suggestions` 或 `suggestedQuestionsExecutor`，确认 bean 创建成功、无配置错误。Ctrl+C 退出。

- [ ] **Step 4: 提交**

```bash
git add bootstrap/src/main/resources/application.yaml
git commit -m "chore(rag): add rag.suggestions config to application.yaml"
```

---

## Task 20: 前端 - 新增 `SuggestionsPayload` 类型 + `useStreamResponse` dispatch

**Files:**
- Modify: `frontend/src/types/index.ts`（如不存在，搜 `CompletionPayload` 所在文件）
- Modify: `frontend/src/hooks/useStreamResponse.ts`

- [ ] **Step 1: 找到 `CompletionPayload` 类型定义位置**

```bash
grep -rn "CompletionPayload\|MessageDeltaPayload" frontend/src/types/
```

- [ ] **Step 2: 在同一文件添加 `SuggestionsPayload` 类型**

在 `CompletionPayload` 之后追加：

```typescript
export interface SuggestionsPayload {
  messageId: string;
  questions: string[];
}
```

- [ ] **Step 3: 修改 `useStreamResponse.ts`，在 `StreamHandlers` 接口加 `onSuggestions`**

打开 `frontend/src/hooks/useStreamResponse.ts`，修改 import 和 interface：

```typescript
import type {
  CompletionPayload,
  MessageDeltaPayload,
  StreamMetaPayload,
  SuggestionsPayload
} from "@/types";

export interface StreamHandlers {
  onMeta?: (payload: StreamMetaPayload) => void;
  onMessage?: (payload: MessageDeltaPayload) => void;
  onThinking?: (payload: MessageDeltaPayload) => void;
  onFinish?: (payload: CompletionPayload) => void;
  onSuggestions?: (payload: SuggestionsPayload) => void;   // 新增
  onDone?: () => void;
  onCancel?: (payload: CompletionPayload) => void;
  onReject?: (payload: MessageDeltaPayload) => void;
  onTitle?: (payload: { title: string }) => void;
  onError?: (error: Error) => void;
  onEvent?: (event: string, payload: unknown) => void;
}
```

然后在 `switch (eventName)` 块（约 line 53）中 `case "finish":` 之后添加：

```typescript
      case "suggestions":
        handlers.onSuggestions?.(payload as SuggestionsPayload);
        break;
```

- [ ] **Step 4: 构建验证**

```bash
cd frontend && npm run build
```

Expected: 无 TypeScript 错误

- [ ] **Step 5: 提交**

```bash
cd "E:/AI Application/rag-knowledge-dev"
git add frontend/src/types/index.ts frontend/src/hooks/useStreamResponse.ts
git commit -m "feat(frontend): add SuggestionsPayload type and useStreamResponse onSuggestions handler"
```

---

## Task 21: 前端 - `chatStore` 绑定 `onSuggestions`

**Files:**
- Modify: `frontend/src/stores/chatStore.ts`

- [ ] **Step 1: 找到 `Message` 类型定义**

```bash
grep -n "interface Message\|type Message" frontend/src/stores/chatStore.ts frontend/src/types/
```

- [ ] **Step 2: 在 `Message` 类型添加 `suggestedQuestions?: string[]` 字段**

例如：

```typescript
export interface Message {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  // ...已有字段
  suggestedQuestions?: string[];   // 新增
}
```

- [ ] **Step 3: 在 `sendMessage` 中绑定 `onSuggestions`**

在 `chatStore.ts` 的 `sendMessage` 函数里找到 `createStreamResponse(...)` 或 handlers 对象，添加：

```typescript
onSuggestions: (payload) => {
  set((state) => ({
    messages: state.messages.map((m) =>
      m.id === payload.messageId
        ? { ...m, suggestedQuestions: payload.questions }
        : m
    )
  }));
},
```

（具体 set/get 风格按 chatStore 现有 Zustand 用法调整；`messageId` 字段类型是 string，用 `m.id === payload.messageId` 匹配。）

**注意：** 如果 `messageId` 在 assistant 消息上是在 `onFinish` 回调里才设置的，需确保 `onFinish` 先执行（SSE 事件顺序保证）。生产代码 `finish` 先于 `suggestions` 发出，frontend 事件串行处理 → 保证 `onFinish` 已写入 `id` 后 `onSuggestions` 才查找，顺序天然正确。

- [ ] **Step 4: 构建验证**

```bash
cd frontend && npm run build
```

Expected: 无 TypeScript 错误

- [ ] **Step 5: 提交**

```bash
cd "E:/AI Application/rag-knowledge-dev"
git add frontend/src/stores/chatStore.ts
git commit -m "feat(frontend): store suggestedQuestions on assistant message via onSuggestions"
```

---

## Task 22: 前端 - `MessageItem` 渲染推荐 chip 按钮

**Files:**
- Modify: `frontend/src/components/chat/MessageItem.tsx`

- [ ] **Step 1: 打开 `MessageItem.tsx` 查找当前渲染位置**

```bash
grep -n "role.*assistant\|MessageItem" frontend/src/components/chat/MessageItem.tsx
```

- [ ] **Step 2: 在 assistant 消息的回答正文下方渲染 chip**

定位 assistant 消息渲染区域（通常在反馈按钮组附近）。在反馈按钮行**之后**添加：

```tsx
{message.role === "assistant" && message.suggestedQuestions && message.suggestedQuestions.length > 0 && (
  <div className="mt-3 flex flex-wrap gap-2 items-center">
    <span className="text-xs text-muted-foreground">可能想问：</span>
    {message.suggestedQuestions.map((q, idx) => (
      <button
        key={idx}
        onClick={() => sendMessage(q)}
        className="text-xs px-3 py-1 rounded-full border border-border bg-background hover:bg-accent hover:text-accent-foreground transition-colors"
      >
        {q}
      </button>
    ))}
  </div>
)}
```

`sendMessage` 从 `chatStore` 拿到（可能需要 `useChatStore` hook 里解构 `const { sendMessage } = useChatStore()`）。

- [ ] **Step 3: 构建前端**

```bash
cd frontend && npm run build
```

Expected: 无 TypeScript 错误

- [ ] **Step 4: 手动检查 CSS 类是否可用**

`rounded-full / border-border / bg-background / hover:bg-accent / transition-colors` 是 Tailwind + shadcn/ui 标准类，项目中应该已可用。若编译过但运行时样式异常，对比同文件其他按钮的类名复用。

- [ ] **Step 5: 提交**

```bash
cd "E:/AI Application/rag-knowledge-dev"
git add frontend/src/components/chat/MessageItem.tsx
git commit -m "feat(frontend): render suggested questions as clickable chips under assistant message"
```

---

## Task 23: 手动端到端验收

**Files:**
- 无代码改动，执行验收步骤

- [ ] **Step 1: 启动后端**

```bash
cd "E:/AI Application/rag-knowledge-dev"
$env:NO_PROXY='localhost,127.0.0.1'; $env:no_proxy='localhost,127.0.0.1'; mvn -pl bootstrap clean spring-boot:run
```

等待 `Started Application in X seconds` 打印。

- [ ] **Step 2: 启动前端**

新终端：

```bash
cd "E:/AI Application/rag-knowledge-dev/frontend"
npm run dev
```

浏览器打开 `http://localhost:5173`（或 Vite 默认端口），登录。

- [ ] **Step 3: 验收场景 1 —— KB 正常问答**

- 进入一个 KB 空间，发一个相关问题（例如"Java 有哪些特点"）
- 等待答案流式完成
- 验收点：**答案结束后 0.5-1 秒内**，答案下方出现 "可能想问：" + 3 个 chip 按钮

- [ ] **Step 4: 验收场景 2 —— 点击 chip**

- 点任一 chip
- 验收点：chip 文本作为新的用户消息自动发送，进入下一轮对话，正常流式回答

- [ ] **Step 5: 验收场景 3 —— 跳过分支**

- 发一个明显歧义的问题（例如仅输入"这是什么"），触发 `IntentGuidanceService`
- 验收点：前端显示引导提示，**不出现 chip 按钮**

- [ ] **Step 6: 验收场景 4 —— 首轮对话**

- 新开会话，发首轮问题（无历史）
- 验收点：正常出 chip（空 history 不阻止生成）

- [ ] **Step 7: 验收场景 5 —— 功能开关**

- 停后端，改 `application.yaml`：`rag.suggestions.enabled: false`
- 重启后端，发问题
- 验收点：**无 chip 出现**，但答案、finish、done 正常

- [ ] **Step 8: 验收场景 6 —— trace 持久化**

- 恢复 `enabled: true`，发一个 KB 问题
- 查询数据库：

```bash
docker exec postgres psql -U postgres -d ragent -c "SELECT trace_id, extra_data FROM t_rag_trace_run ORDER BY start_time DESC LIMIT 1;"
```

- 验收点：`extra_data` JSON 包含 `"suggestedQuestions":[...]` 字段

- [ ] **Step 9: 验收场景 7 —— 生成异常兜底**

- 配一个不存在的模型：`rag.suggestions.model-id: nonexistent-model`
- 重启后端，发问题
- 验收点：答案 + finish 正常；chip 不出现（因 LLM 失败）；`done` 事件到达，前端 loading 不挂起

- [ ] **Step 10: 验收场景 8 —— 浏览器 Network 事件流检查**

- 打开 DevTools → Network → 选中 `/rag/v3/chat` SSE 请求
- 在 "EventStream" 或 "Preview" 观察事件序列
- 验收点：事件依次为 `meta` → `message` × N → `finish` → `suggestions` → `done`

- [ ] **Step 11: 全部验收通过后提交最后的记录（无代码改动）**

如有验收中发现的小问题修复，单独 commit。如全部通过，在 `log/dev_log/` 下新增一个 `2026-04-16-suggested-questions.md` 记录（可选）。

---

## 自检 Checklist（作者用）

**Spec 覆盖对照：**

| Spec 章节 | 对应 Task |
|-----------|----------|
| §2.1 新 SSE 事件 | Task 1, 17 |
| §2.2 小模型独立调用 + grounding | Task 4 (config), 9-12 (service) |
| §2.3 5 条跳过条件 | Task 18（guidance/sysOnly/empty retrieval 由分支短路）, Task 16（default skip()）|
| §2.4 MCP 跳过 | Task 18 (`hasMcp` 判断) |
| §2.5 配置项 | Task 4, 19 |
| §2.6 固定 3 条 | Prompt 模板 (Task 6) |
| §2.7 不持久化 + trace extra_data | Task 13, 16 (`mergeSuggestionsIntoTrace`) |
| §3.1 SSE 契约 | Task 1, 16 (`onComplete` 重写) |
| §3.2 职责分层 | Task 16（handler 不判业务）, Task 18（orchestrator 预判） |
| §6 错误处理 | Task 11, 16 (try/finally) |
| §7 线程池 | Task 7 |
| §8 测试策略 | Task 9-12（单测）, Task 17（集成）, Task 23（手动） |

**Placeholder 扫描：**
- ✅ 无 TBD / TODO / "implement later"
- ✅ 每个有测试的 Task 都有完整测试代码
- ✅ 每个修改步骤都给出具体代码 diff 或完整内容
- ✅ 每个命令都有 Expected 输出

**类型一致性：**
- `SuggestedQuestionsService.generate(SuggestionContext, String)` —— Task 8, 9, 16 均一致
- `SuggestionContext(question, history, topChunks, shouldGenerate)` —— Task 3, 16, 18 一致
- `SuggestionsPayload(messageId, questions)` —— Task 2, 16, 17, 20 一致
- `updateSuggestionContext(SuggestionContext)` —— Task 16（setter）, 18（调用）, 17（测试调用）一致
- `mergeRunExtraData(String, Map<String,Object>)` —— Task 13（定义）, 16（调用）一致

**Scope：**
- 只增不改 SSE 协议（向后兼容）
- 不触碰数据库 schema（`extra_data` JSON 写入走现有列）
- 不改 RAG 核心（retrieve/rewrite/intent）
- 后续可扩展（MCP 支持、embedding 过滤、前端埋点）在 spec §10 里记录

---

## 执行提示

- 按 Task 顺序执行，Task 之间常有依赖（特别是 Task 14-16 和 17-18）
- 每个 Task 结束 commit 一次，保持 commit 粒度细
- 测试失败别硬改测试 —— 先改生产代码让测试通过
- 如果 `ChatRequest.maxTokens` 或 `RetrievedChunk.getScore` 等底层 API 与假设不符，遵循"**探一下现有源码、对齐现有签名**"原则调整，不编造新 API

结束。
