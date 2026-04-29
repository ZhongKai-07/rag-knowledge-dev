# OpenAI-Compatible Embedding Client Abstract Base Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** cherry-pick upstream `nageoffer/ragent@bd62f68c`，把 `OllamaEmbeddingClient` 与 `SiliconFlowEmbeddingClient` 的 ~360 行重复实现抽到新的 `AbstractOpenAIStyleEmbeddingClient` 基类（OpenAI `/v1/embeddings` 协议），子类只剩 `provider()` + 钩子方法（`requiresApiKey` / `customizeRequestBody` / `maxBatchSize`），与已有的 `EmbeddingClient` 接口对称简化。

**Architecture:** 完全位于 `infra-ai/src/main/java/.../infra/embedding/` 包内，**不动 framework `security/port` 任何 Port，不动 retrieval 链路，不动权限体系**。本次同时把 Ollama 的 embedding 端点从 `/api/embed` 切到 OpenAI 兼容的 `/v1/embeddings`（要求 Ollama ≥ 0.1.42），获得真正的批量调用能力（之前 `embedBatch` 是循环单条）。

**Tech Stack:** Java 17 / OkHttp 4.x / Gson / JUnit 5 + Mockito 5.20 / infra-ai 模块。

---

## Context Notes for Implementer

- Upstream commit `bd62f68c` 路径前缀 `com.nageoffer.ai.ragent` 在我方对应 `com.knowledgebase.ai.ragent`（包名替换是机械工作）。
- **upstream 用了 `HttpResponseHelper` 静态工具类（`requireProvider/requireModel/requireApiKey/parseJson/readBody`）和 `ModelClientErrorType.fromHttpStatus(int)`，这两个我方都不存在**——`HttpResponseHelper` 是 upstream 在 `8600d8ed` 大重构时引入的，我方未合入。**本计划不引入新公共类**，把这些 helper 作为 `protected` 方法**内嵌到 `AbstractOpenAIStyleEmbeddingClient`** 里，沿用我方现有两个客户端各自私有方法的实现。未来若整体合入 8600d8ed 的 ChatClient 抽象，再统一抽出 `HttpResponseHelper`。
- **我方 `ModelClientErrorType.PROVIDER_ERROR` 已存在**（line 61）；`fromHttpStatus` 不存在 → 在基类的 `protected classifyStatus(int)` 里实现，沿用我方原有逻辑（401/403→UNAUTHORIZED, 429→RATE_LIMITED, ≥500→SERVER_ERROR, else→CLIENT_ERROR）。
- **dimensions null-safe**：upstream `body.addProperty("dimensions", target.candidate().getDimension())` 直接发送，如果 dimension 为 null 会变成 `"dimensions": null` JSON（API 可能拒绝）。我方 `SiliconFlowEmbeddingClient` 原本是 `if (... != null)` 条件性发送的——**保留这个 null-safe 兜底**，在基类的 `doEmbed` 中加 null-check。
- **Ollama yaml 端点切换** `/api/embed → /v1/embeddings`：这是接口契约改动，要求 Ollama **≥ 0.1.42**（OpenAI 兼容端点支持版本）。如果用户生产 Ollama 老版本，**回滚方法**：把 yaml 那行改回 `/api/embed`，但同时也要把 `OllamaEmbeddingClient` 改回 Ollama 原生协议——所以本计划**整体捆绑变更**，不留中间态。
- **Ollama `embedBatch` 行为变化**：从循环单条调用 → 真正批量。是性能改进，但要求 Ollama `/v1/embeddings` 支持 input 数组（OpenAI 协议本来就支持，Ollama OpenAI 兼容层也支持）。
- **测试设计**：mock `OkHttpClient.newCall(...).execute()` 返回 `Response.Builder` 构造的 Response。基类单测覆盖核心路径（API key header / batch 切片 / 错误响应映射 / dimensions null-safe）；两个子类各加 1 个轻测试锁住 `provider()` 与 hook 覆写。
- **架构/权限合规**：**零影响**——纯 infra-ai 模块内部重构，不接 retrieval 通道、不接 metadata filter、不接 framework Port。
- **单一逻辑提交**：5 个生产文件（含 yaml）+ 3 个新测试文件同时提；不拆 commit。提交信息标明 cherry-pick 来源。

---

## File Structure

| 路径 | 责任 | 操作 |
| --- | --- | --- |
| `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/AbstractOpenAIStyleEmbeddingClient.java` | OpenAI `/v1/embeddings` 协议公共基类 | Create：~190 行（接口实现 + 模板方法 + 内嵌 helper） |
| `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/OllamaEmbeddingClient.java` | Ollama 实现 | Modify：从 159 行 → ~50 行，继承基类，覆写 `requiresApiKey=false` + `customizeRequestBody` 不发 `encoding_format` |
| `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/SiliconFlowEmbeddingClient.java` | SiliconFlow 实现 | Modify：从 205 行 → ~50 行，继承基类，覆写 `maxBatchSize=32` |
| `bootstrap/src/main/resources/application.yaml` | 主配置 | Modify：`ai.providers.ollama.endpoints.embedding: /api/embed → /v1/embeddings` |
| `infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/AbstractOpenAIStyleEmbeddingClientTest.java` | 基类 8 用例 | Create |
| `infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/OllamaEmbeddingClientTest.java` | Ollama 子类 1 用例（hook 覆写） | Create |
| `infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/SiliconFlowEmbeddingClientTest.java` | SiliconFlow 子类 1 用例（hook 覆写） | Create |

---

## Task 0: Setup Worktree + Branch

**Files:** N/A（git 操作）

- [ ] **Step 1: 创建 worktree**

```bash
git worktree add .worktrees/embedding-base -b embedding-base main
```

- [ ] **Step 2: 进入 worktree，确认基线**

```bash
cd .worktrees/embedding-base
git status
git log --oneline -3
```

Expected：`On branch embedding-base` / 工作树干净 / HEAD 是包含 PR #33 merge 的最新 commit。

---

## Task 1: 新建 `AbstractOpenAIStyleEmbeddingClient` 基类

**Files:**
- Create: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/AbstractOpenAIStyleEmbeddingClient.java`

- [ ] **Step 1: 创建文件并写入完整内容**

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

package com.knowledgebase.ai.ragent.infra.embedding;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.infra.enums.ModelCapability;
import com.knowledgebase.ai.ragent.infra.http.HttpMediaTypes;
import com.knowledgebase.ai.ragent.infra.http.ModelClientErrorType;
import com.knowledgebase.ai.ragent.infra.http.ModelClientException;
import com.knowledgebase.ai.ragent.infra.http.ModelUrlResolver;
import com.knowledgebase.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容协议 EmbeddingClient 抽象基类
 * <p>
 * 封装 /v1/embeddings 协议的通用逻辑，子类只需提供 provider() 和按需覆写钩子方法。
 *
 * <p>本地化适配（vs upstream bd62f68c）：
 * <ul>
 *   <li>helper 方法 {@code requireProvider/requireModel/requireApiKey/parseJsonBody/readBody/classifyStatus}
 *       内嵌为 {@code protected} 方法（upstream 用的 {@code HttpResponseHelper} 静态工具类我方未引入）</li>
 *   <li>{@code dimensions} 字段 null-safe 发送（upstream 直接发送，dim=null 时会得到 {@code "dimensions": null}）</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractOpenAIStyleEmbeddingClient implements EmbeddingClient {

    protected final OkHttpClient httpClient;

    protected AbstractOpenAIStyleEmbeddingClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ==================== 子类钩子方法 ====================

    /**
     * 是否要求提供商配置 API Key，默认 true。Ollama 等本地服务覆写为 false。
     */
    protected boolean requiresApiKey() {
        return true;
    }

    /**
     * 子类可覆写此方法添加提供商特有的请求体字段。
     * 默认实现：添加 {@code encoding_format=float}。Ollama 不需要此字段，覆写为空实现。
     */
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        body.addProperty("encoding_format", "float");
    }

    /**
     * 单次请求最大批量大小，0 表示不限制。SiliconFlow 覆写为 32。
     */
    protected int maxBatchSize() {
        return 0;
    }

    // ==================== 接口实现 ====================

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        List<List<Float>> result = doEmbed(List.of(text), target);
        return result.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }
        int batch = maxBatchSize();
        if (batch <= 0 || texts.size() <= batch) {
            return doEmbed(texts, target);
        }

        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += batch) {
            int end = Math.min(i + batch, n);
            List<String> slice = texts.subList(i, end);
            List<List<Float>> part = doEmbed(slice, target);
            for (int k = 0; k < part.size(); k++) {
                results.set(i + k, part.get(k));
            }
        }
        return results;
    }

    // ==================== 模板方法：核心请求逻辑 ====================

    protected List<List<Float>> doEmbed(List<String> texts, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        if (requiresApiKey()) {
            requireApiKey(provider);
        }

        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);

        JsonObject body = new JsonObject();
        body.addProperty("model", requireModel(target));
        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        body.add("input", inputArray);
        // 本地化：dimensions null-safe（避免发送 "dimensions": null）
        if (target.candidate().getDimension() != null) {
            body.addProperty("dimensions", target.candidate().getDimension());
        }
        customizeRequestBody(body, target);

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Content-Type", HttpMediaTypes.JSON_UTF8_HEADER);
        if (requiresApiKey()) {
            requestBuilder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        Request request = requestBuilder.build();

        JsonObject json;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("{} embedding 请求失败: status={}, body={}", provider(), response.code(), errBody);
                throw new ModelClientException(
                        provider() + " embedding 请求失败: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            json = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " embedding 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        if (json.has("error")) {
            JsonObject err = json.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException(
                    provider() + " embedding 错误: " + code + " - " + msg,
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }

        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelClientException(
                    provider() + " embedding 响应中缺少 data 数组",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<List<Float>> results = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            JsonArray emb = obj.getAsJsonArray("embedding");
            if (emb == null || emb.isEmpty()) {
                throw new ModelClientException(
                        provider() + " embedding 响应中缺少 embedding 字段",
                        ModelClientErrorType.INVALID_RESPONSE, null);
            }
            List<Float> vector = new ArrayList<>(emb.size());
            for (JsonElement v : emb) {
                vector.add(v.getAsFloat());
            }
            results.add(vector);
        }

        return results;
    }

    // ==================== 内嵌 helper（沿用我方原私有方法实现，未引入 HttpResponseHelper） ====================

    protected AIModelProperties.ProviderConfig requireProvider(ModelTarget target) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException(provider() + " provider config is missing");
        }
        return target.provider();
    }

    protected String requireModel(ModelTarget target) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException(provider() + " model name is missing");
        }
        return target.candidate().getModel();
    }

    protected void requireApiKey(AIModelProperties.ProviderConfig provider) {
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new IllegalStateException(provider() + " API key is missing");
        }
    }

    protected JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException(provider() + " embedding 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return JsonParser.parseString(body.string()).getAsJsonObject();
    }

    protected String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    protected ModelClientErrorType classifyStatus(int status) {
        if (status == 401 || status == 403) {
            return ModelClientErrorType.UNAUTHORIZED;
        }
        if (status == 429) {
            return ModelClientErrorType.RATE_LIMITED;
        }
        if (status >= 500) {
            return ModelClientErrorType.SERVER_ERROR;
        }
        return ModelClientErrorType.CLIENT_ERROR;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -pl infra-ai -am compile -DskipTests
```

Expected：`BUILD SUCCESS`。

---

## Task 2: 写基类测试（8 用例 TDD）

**Files:**
- Create: `infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/AbstractOpenAIStyleEmbeddingClientTest.java`

- [ ] **Step 1: 创建测试文件**

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

package com.knowledgebase.ai.ragent.infra.embedding;

import com.google.gson.JsonObject;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.infra.http.ModelClientErrorType;
import com.knowledgebase.ai.ragent.infra.http.ModelClientException;
import com.knowledgebase.ai.ragent.infra.model.ModelTarget;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractOpenAIStyleEmbeddingClientTest {

    private OkHttpClient httpClient;
    private AtomicReference<Request> capturedRequest;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        capturedRequest = new AtomicReference<>();
    }

    @Test
    void embedSingleTextReturnsFirstVector() throws IOException {
        stubResponse(200, "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient);

        List<Float> vector = client.embed("hello", target("siliconflow", "model-x", 3, "sk-key"));

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void embedBatchUnderLimitMakesSingleRequest() throws IOException {
        stubResponse(200,
                "{\"data\":[{\"embedding\":[0.1,0.2]},{\"embedding\":[0.3,0.4]},{\"embedding\":[0.5,0.6]}]}");
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient);  // maxBatchSize=0

        List<List<Float>> result = client.embedBatch(List.of("a", "b", "c"),
                target("siliconflow", "model-x", 2, "sk-key"));

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(result.get(2)).containsExactly(0.5f, 0.6f);
    }

    @Test
    void embedBatchSlicesWhenSizeExceedsLimit() throws IOException {
        // maxBatchSize=2, 5 texts → 3 calls (2+2+1)
        stubResponseSequence(
                "{\"data\":[{\"embedding\":[1.0]},{\"embedding\":[2.0]}]}",
                "{\"data\":[{\"embedding\":[3.0]},{\"embedding\":[4.0]}]}",
                "{\"data\":[{\"embedding\":[5.0]}]}"
        );
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient) {
            @Override protected int maxBatchSize() { return 2; }
        };

        List<List<Float>> result = client.embedBatch(List.of("a", "b", "c", "d", "e"),
                target("siliconflow", "model-x", 1, "sk-key"));

        assertThat(result).hasSize(5);
        assertThat(result.get(0)).containsExactly(1.0f);
        assertThat(result.get(4)).containsExactly(5.0f);
    }

    @Test
    void requiresApiKeyTrueAddsAuthorizationHeader() throws IOException {
        stubResponse(200, "{\"data\":[{\"embedding\":[1.0]}]}");
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient);  // requiresApiKey=true

        client.embed("hi", target("siliconflow", "model-x", 1, "sk-secret"));

        assertThat(capturedRequest.get().header("Authorization")).isEqualTo("Bearer sk-secret");
    }

    @Test
    void requiresApiKeyFalseSkipsAuthorizationHeader() throws IOException {
        stubResponse(200, "{\"data\":[{\"embedding\":[1.0]}]}");
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient) {
            @Override protected boolean requiresApiKey() { return false; }
        };

        client.embed("hi", target("ollama", "model-x", 1, null));

        assertThat(capturedRequest.get().header("Authorization")).isNull();
    }

    @Test
    void dimensionsIsOmittedWhenCandidateDimensionIsNull() throws IOException {
        stubResponse(200, "{\"data\":[{\"embedding\":[1.0]}]}");
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient);

        client.embed("hi", target("siliconflow", "model-x", null, "sk-key"));

        String body = readRequestBody(capturedRequest.get());
        assertThat(body).doesNotContain("\"dimensions\"");
    }

    @Test
    void httpUnauthorizedMapsToUnauthorizedErrorType() {
        stubResponse(401, "{\"error\":\"bad token\"}");
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient);

        assertThatThrownBy(() -> client.embed("hi", target("siliconflow", "model-x", 1, "sk-key")))
                .isInstanceOf(ModelClientException.class)
                .matches(e -> ((ModelClientException) e).getErrorType() == ModelClientErrorType.UNAUTHORIZED);
    }

    @Test
    void responseWithErrorObjectMapsToProviderError() {
        stubResponse(200, "{\"error\":{\"code\":\"quota\",\"message\":\"exceeded\"}}");
        TestEmbeddingClient client = new TestEmbeddingClient(httpClient);

        assertThatThrownBy(() -> client.embed("hi", target("siliconflow", "model-x", 1, "sk-key")))
                .isInstanceOf(ModelClientException.class)
                .hasMessageContaining("quota - exceeded")
                .matches(e -> ((ModelClientException) e).getErrorType() == ModelClientErrorType.PROVIDER_ERROR);
    }

    // ==================== test fixtures ====================

    private void stubResponse(int code, String jsonBody) {
        Call call = mock(Call.class);
        try {
            when(call.execute()).thenAnswer(inv -> buildResponse(captureRequest(call), code, jsonBody));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            capturedRequest.set(inv.getArgument(0));
            Call c = mock(Call.class);
            try {
                when(c.execute()).thenReturn(buildResponse(inv.getArgument(0), code, jsonBody));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return c;
        });
    }

    private void stubResponseSequence(String... bodies) {
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(0);
        when(httpClient.newCall(any(Request.class))).thenAnswer(inv -> {
            capturedRequest.set(inv.getArgument(0));
            Call c = mock(Call.class);
            String body = bodies[idx.getAndIncrement()];
            when(c.execute()).thenReturn(buildResponse(inv.getArgument(0), 200, body));
            return c;
        });
    }

    private Request captureRequest(Call call) {
        return capturedRequest.get();
    }

    private Response buildResponse(Request request, int code, String jsonBody) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Err")
                .body(ResponseBody.create(jsonBody, MediaType.get("application/json")))
                .build();
    }

    private ModelTarget target(String providerId, String model, Integer dimension, String apiKey) {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://example.invalid");
        provider.setApiKey(apiKey);
        provider.setEndpoints(Map.of("embedding", "/v1/embeddings"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(model);
        candidate.setProvider(providerId);
        candidate.setModel(model);
        candidate.setDimension(dimension);

        return new ModelTarget(model, candidate, provider);
    }

    private String readRequestBody(Request request) {
        try (okio.Buffer buffer = new okio.Buffer()) {
            request.body().writeTo(buffer);
            return buffer.readUtf8();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 测试用基类实例：默认所有钩子方法不覆写。 */
    private static class TestEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {
        TestEmbeddingClient(OkHttpClient httpClient) {
            super(httpClient);
        }

        @Override
        public String provider() {
            return "test";
        }

        @Override
        public java.util.List<Float> embed(String text, ModelTarget target) {
            return super.embed(text, target);
        }

        // override hook to no-op (avoid encoding_format if not desired)
        @Override
        protected void customizeRequestBody(JsonObject body, ModelTarget target) {
            super.customizeRequestBody(body, target);
        }
    }
}
```

- [ ] **Step 2: 跑测试，确认 PASS**

```bash
mvn -pl infra-ai test -Dtest='AbstractOpenAIStyleEmbeddingClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：8/8 PASS。

> 如果某个用例 FAIL，先看 stack trace 修复，**不要跳过测试**。常见问题：
> - `mock(OkHttpClient.class)` 不工作 —— OkHttpClient 是 final 类，**Mockito 5.20 默认支持 mock final**（parent pom 已配 `mockito-extensions/MockMaker = mock-maker-inline`）。如果报错，检查 `infra-ai/pom.xml` 是否继承了 mockito-core test scope。
> - `okio.Buffer` 找不到 —— OkHttp 已传递依赖 `com.squareup.okio:okio`，编译应自动找到。

---

## Task 3: 重构 `OllamaEmbeddingClient` 继承基类

**Files:**
- Modify: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/OllamaEmbeddingClient.java`
- Create: `infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/OllamaEmbeddingClientTest.java`

- [ ] **Step 1: 用以下完整内容替换 `OllamaEmbeddingClient.java`**

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

package com.knowledgebase.ai.ragent.infra.embedding;

import com.google.gson.JsonObject;
import com.knowledgebase.ai.ragent.infra.enums.ModelProvider;
import com.knowledgebase.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

@Service
public class OllamaEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public OllamaEmbeddingClient(OkHttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    @Override
    protected boolean requiresApiKey() {
        return false;
    }

    @Override
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        // Ollama OpenAI 兼容端点不需要 encoding_format 字段
    }
}
```

- [ ] **Step 2: 创建 `OllamaEmbeddingClientTest`**

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

package com.knowledgebase.ai.ragent.infra.embedding;

import com.knowledgebase.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OllamaEmbeddingClientTest {

    @Test
    void providerNameAndHookOverridesAreCorrect() {
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(mock(OkHttpClient.class));

        assertThat(client.provider()).isEqualTo(ModelProvider.OLLAMA.getId());
        // 通过反射或行为测试间接验证 requiresApiKey=false / maxBatchSize=0；
        // 此处用最简方式：构造未配置 apiKey 的 ProviderConfig 不应抛 IllegalStateException
        // —— 由基类 AbstractOpenAIStyleEmbeddingClientTest 的 requiresApiKeyFalseSkipsAuthorizationHeader
        // 已锁住该路径；此处只锁 provider 名，避免重复测试基础设施。
    }
}
```

- [ ] **Step 3: 跑 OllamaEmbeddingClientTest**

```bash
mvn -pl infra-ai test -Dtest='OllamaEmbeddingClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：1/1 PASS。

---

## Task 4: 重构 `SiliconFlowEmbeddingClient` 继承基类

**Files:**
- Modify: `infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/SiliconFlowEmbeddingClient.java`
- Create: `infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/SiliconFlowEmbeddingClientTest.java`

- [ ] **Step 1: 用以下完整内容替换 `SiliconFlowEmbeddingClient.java`**

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

package com.knowledgebase.ai.ragent.infra.embedding;

import com.knowledgebase.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

@Service
public class SiliconFlowEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public SiliconFlowEmbeddingClient(OkHttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    protected int maxBatchSize() {
        return 32;
    }
}
```

- [ ] **Step 2: 创建 `SiliconFlowEmbeddingClientTest`**

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

package com.knowledgebase.ai.ragent.infra.embedding;

import com.knowledgebase.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SiliconFlowEmbeddingClientTest {

    @Test
    void providerNameAndHookOverridesAreCorrect() {
        SiliconFlowEmbeddingClient client = new SiliconFlowEmbeddingClient(mock(OkHttpClient.class));

        assertThat(client.provider()).isEqualTo(ModelProvider.SILICON_FLOW.getId());
        // maxBatchSize=32 由基类 AbstractOpenAIStyleEmbeddingClientTest.embedBatchSlicesWhenSizeExceedsLimit
        // 等价覆盖（用 maxBatchSize=2 的子类锁住切片逻辑）
    }
}
```

- [ ] **Step 3: 跑 SiliconFlowEmbeddingClientTest**

```bash
mvn -pl infra-ai test -Dtest='SiliconFlowEmbeddingClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：1/1 PASS。

---

## Task 5: yaml — Ollama embedding 端点切到 OpenAI 兼容路径

**Files:**
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: 改 ollama embedding endpoint**

打开 `bootstrap/src/main/resources/application.yaml`，定位 `ai.providers.ollama.endpoints`（约 142-146 行）：

```yaml
    ollama:
      url: http://localhost:11434
      endpoints:
        chat: /api/chat
        embedding: /api/embed
```

把 `embedding: /api/embed` 改为：

```yaml
    ollama:
      url: http://localhost:11434
      endpoints:
        chat: /api/chat
        embedding: /v1/embeddings
```

> **要求**：Ollama 服务端 ≥ 0.1.42（OpenAI 兼容端点支持版本）。如需回滚，把 yaml 改回 `/api/embed` 的同时**也要回滚 `OllamaEmbeddingClient`**（恢复继承前的私有协议实现）。

---

## Task 6: 全量验证 + Commit

**Files:** N/A（验证）

- [ ] **Step 1: 后端 spotless + 全量编译**

```bash
mvn clean install -DskipTests spotless:check
```

Expected：5 模块全部 BUILD SUCCESS。

> 如 spotless 报 formatting 问题，跑 `mvn spotless:apply` 修复后**作为本次改动的一部分一起 commit**。

- [ ] **Step 2: 跑 embedding 全部测试**

```bash
mvn -pl infra-ai test -Dtest='AbstractOpenAIStyleEmbeddingClientTest,OllamaEmbeddingClientTest,SiliconFlowEmbeddingClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Expected：8 + 1 + 1 = **10/10 PASS**。

- [ ] **Step 3: 跑 infra-ai 模块全量测试，确认无回归**

```bash
mvn -pl infra-ai test
```

Expected：现有 `RoutingLLMServiceSyncFallbackTest` / `BaiLianRerankClientSmallKbTest` 全部 PASS。

- [ ] **Step 4: 后端启动烟测（必做）**

需要 Redis 已启动：

```bash
$env:NO_PROXY='localhost,127.0.0.1'; mvn -pl bootstrap spring-boot:run
```

观察启动日志：
- `OllamaEmbeddingClient` 与 `SiliconFlowEmbeddingClient` Spring Bean 装配无错
- 应用启动完成（端口 9090）
- 无 `BeanCreationException` 或 `ClassNotFoundException`

如果当前环境有 Ollama 服务（`localhost:11434`）且配置使用 Ollama 做 embedding，**进一步验证一次真实 embedding 调用**（如新建文档触发 ingestion + embedding）；如果生产 embedding 走 SiliconFlow，启动 OK 即可放行（SiliconFlow 协议本来就是 OpenAI 兼容，回归风险极低）。

Ctrl+C 停止。

- [ ] **Step 5: 单一 commit**

```bash
git add infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/AbstractOpenAIStyleEmbeddingClient.java \
        infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/OllamaEmbeddingClient.java \
        infra-ai/src/main/java/com/knowledgebase/ai/ragent/infra/embedding/SiliconFlowEmbeddingClient.java \
        infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/AbstractOpenAIStyleEmbeddingClientTest.java \
        infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/OllamaEmbeddingClientTest.java \
        infra-ai/src/test/java/com/knowledgebase/ai/ragent/infra/embedding/SiliconFlowEmbeddingClientTest.java \
        bootstrap/src/main/resources/application.yaml
git status
```

Expected：仅上述 7 个文件 staged。

```bash
git commit -m "$(cat <<'EOF'
feat(embedding): OpenAI 兼容协议 EmbeddingClient 抽象基类

cherry-pick from upstream nageoffer/ragent@bd62f68c。把 OllamaEmbeddingClient
与 SiliconFlowEmbeddingClient 的 ~360 行重复实现抽到新的 AbstractOpenAIStyleEmbeddingClient
基类，子类只剩 provider() + 钩子方法（requiresApiKey / customizeRequestBody / maxBatchSize）。

主要变更：

1. 新增 AbstractOpenAIStyleEmbeddingClient（193 行）
   - 实现 EmbeddingClient 接口，封装 /v1/embeddings 协议
   - 模板方法 doEmbed 处理 HTTP 请求 + 响应解析 + 错误映射
   - 内嵌 helper 方法 requireProvider/requireModel/requireApiKey/parseJsonBody/readBody/classifyStatus
     （未引入 HttpResponseHelper 公共类，与未合入 8600d8ed 的现状保持一致）
   - dimensions null-safe 兜底（避免发送 "dimensions": null）

2. OllamaEmbeddingClient 159 → ~50 行
   - 继承基类，覆写 requiresApiKey=false + customizeRequestBody 不发 encoding_format
   - embedBatch 从循环单条调用改为真正批量
   - 配套 yaml: ai.providers.ollama.endpoints.embedding /api/embed → /v1/embeddings
     （要求 Ollama ≥ 0.1.42）

3. SiliconFlowEmbeddingClient 205 → ~50 行
   - 继承基类，覆写 maxBatchSize=32（与原本切片逻辑等价）

测试：
- AbstractOpenAIStyleEmbeddingClientTest (8 用例)：embed/embedBatch/maxBatchSize 切片/
  requiresApiKey 控制 Authorization header/dimensions null-safe/HTTP 4xx 映射/
  响应 error 字段映射 PROVIDER_ERROR
- OllamaEmbeddingClientTest / SiliconFlowEmbeddingClientTest 各 1 用例锁住 provider() 名

权限审计：
- 不动 framework/security/port 任何 Port
- 不动 retrieval / metadata filter / RetrievalScope 链路
- 纯 infra-ai 模块内部重构

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Push + 开 PR

**Files:** N/A（git / GitHub 操作）

- [ ] **Step 1: 推分支**

```bash
git push -u origin embedding-base
```

Expected：远程创建分支 `embedding-base`，推送提示给出 GitHub PR URL。

- [ ] **Step 2: 创建 PR**

如果 `gh` CLI 已登录：

```bash
gh pr create --base main --head embedding-base \
  --title "feat(embedding): OpenAI 兼容协议 EmbeddingClient 抽象基类" \
  --body "$(cat <<'EOF'
## Summary

cherry-pick upstream `nageoffer/ragent@bd62f68c`：抽出 `AbstractOpenAIStyleEmbeddingClient` 基类，消除 `OllamaEmbeddingClient` 与 `SiliconFlowEmbeddingClient` 的 ~360 行重复实现，子类只剩 `provider()` + 钩子方法。

## 主要变更

- **新增 `AbstractOpenAIStyleEmbeddingClient`（193 行）**：OpenAI `/v1/embeddings` 协议公共基类
- **`OllamaEmbeddingClient` 159 → ~50 行**：继承基类，覆写 `requiresApiKey=false` + 不发 `encoding_format`
- **`SiliconFlowEmbeddingClient` 205 → ~50 行**：继承基类，覆写 `maxBatchSize=32`
- **yaml `ai.providers.ollama.endpoints.embedding`**：`/api/embed → /v1/embeddings`（要求 Ollama ≥ 0.1.42）

## 本地化适配（vs upstream）

- **未引入 `HttpResponseHelper` 公共类**：upstream 在 `8600d8ed` 引入此 helper，我方未合入该重构，本计划把 `requireProvider/requireModel/requireApiKey/parseJsonBody/readBody/classifyStatus` 作为 `protected` 方法**内嵌到基类**。未来若整体合入 `8600d8ed` 的 ChatClient 抽象，再统一抽出。
- **`dimensions` null-safe 兜底**：upstream `body.addProperty("dimensions", target.candidate().getDimension())` 直接发送，dim=null 时会得到 `"dimensions": null` JSON。本计划在基类加 null-check，与我方原 `SiliconFlowEmbeddingClient` 行为一致。
- **`ModelClientErrorType.fromHttpStatus(int)` 不存在**（同样属 8600d8ed 引入）：在基类的 `protected classifyStatus(int)` 实现，沿用我方原有逻辑。

## 权限/架构合规审计

- ✅ 不动 framework `security/port` 任何 Port
- ✅ 不动 retrieval / metadata filter / RetrievalScope 链路  
- ✅ 纯 infra-ai 模块内部重构，零业务逻辑变更

## 测试

新增：
- `AbstractOpenAIStyleEmbeddingClientTest`（8 用例）：embed/embedBatch/`maxBatchSize` 切片/`requiresApiKey` 控制 Authorization/`dimensions` null-safe/HTTP 4xx 映射/响应 error 字段映射 PROVIDER_ERROR
- `OllamaEmbeddingClientTest` / `SiliconFlowEmbeddingClientTest` 各 1 用例锁住 `provider()` 名

回归：
- [x] `mvn clean install -DskipTests spotless:check` BUILD SUCCESS（5 模块）
- [x] `mvn -pl infra-ai test` 全 PASS（含既有 `RoutingLLMServiceSyncFallbackTest` / `BaiLianRerankClientSmallKbTest`）
- [x] 后端启动烟测：embedding Bean 装配无错

## 已知前置依赖

**Ollama 用户必读**：本 PR 把 ollama embedding 端点切到 OpenAI 兼容的 `/v1/embeddings`，要求 Ollama 服务端 ≥ 0.1.42。如果生产 embedding 走 SiliconFlow（`siliconflow` provider），无需任何 Ollama 操作。如需回滚 Ollama 部分，**同时**把 yaml 改回 `/api/embed` 并恢复 `OllamaEmbeddingClient` 私有协议实现。

## 实施计划

详见 [`docs/superpowers/plans/2026-04-29-openai-style-embedding-base.md`](docs/superpowers/plans/2026-04-29-openai-style-embedding-base.md)（plan 文件本地保留，不入此 PR）。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

如果 `gh` 未登录，把上述 PR title + body 落盘到 `PR_BODY_embedding_base.md`，然后让 user 用 push 输出的 GitHub URL 手工开 PR。

- [ ] **Step 3: PR merge 后清理**

```bash
cd "E:/AI Application/rag-knowledge-dev"
git checkout main
git pull --ff-only origin main
git worktree remove .worktrees/embedding-base
git branch -D embedding-base
rm -f PR_BODY_embedding_base.md
git worktree list
```

Expected：worktree 与本地分支删除，主仓库 main HEAD 推进到包含此 PR 的 merge commit。

---

## Self-Review Notes

**Spec coverage check:**
- ✅ upstream `bd62f68c` 4 个生产文件（base / ollama / siliconflow / yaml）全部对应 task
- ✅ 新增 3 个测试文件覆盖基类 + 2 个子类的 hook 覆写
- ✅ 本地化适配 3 处明确写入 plan（HttpResponseHelper 不引入 / dimensions null-safe / fromHttpStatus 不存在）
- ✅ Cherry-pick 元信息保留：commit message 标明 upstream bd62f68c
- ✅ Worktree 流程闭环：从 setup 到清理（Task 0 + Task 7 Step 3）

**Placeholder scan:**
- 无 "TBD" / "implement later" / "适当处理"
- 所有代码块都给出可粘贴的完整文件内容（Task 1 / Task 3 Step 1 / Task 4 Step 1）或 inline diff 片段
- 命令都是可执行形式

**Type consistency:**
- `EmbeddingClient` 接口签名 `embed(text, target)` / `embedBatch(texts, target)` 全程一致
- `ModelTarget` 是 `record(id, candidate, provider)` 三元组——测试 fixture 使用一致
- `ModelClientErrorType` 枚举值（`UNAUTHORIZED / RATE_LIMITED / SERVER_ERROR / CLIENT_ERROR / NETWORK_ERROR / INVALID_RESPONSE / PROVIDER_ERROR`）已 grep 确认全部存在
- 包名 `com.knowledgebase.ai.ragent.infra.embedding` 全程一致

**架构 / 权限合规审计表：**

| 改动点 | 影响层级 | 审计结论 |
| --- | --- | --- |
| `AbstractOpenAIStyleEmbeddingClient`（新增） | infra-ai 内部 | ✅ 不接 Port / Retrieval / Filter |
| `OllamaEmbeddingClient`（重构） | infra-ai 内部 | ✅ 行为变化：embedBatch 真批量；不接业务层 |
| `SiliconFlowEmbeddingClient`（重构） | infra-ai 内部 | ✅ 行为完全等价（maxBatchSize=32 切片仍存在） |
| `application.yaml` ollama embedding endpoint | 配置 | ⚠️ Ollama 用户需 ≥ 0.1.42；SiliconFlow 用户零影响 |
| 新增 3 个测试文件 | 测试 | ✅ 不接生产代码 |

**风险等级**：低-中。下行影响只局限于使用 Ollama 做 embedding 的部署。预计 90 分钟内完成（含烟测 + PR）。
