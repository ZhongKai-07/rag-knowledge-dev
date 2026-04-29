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

@Slf4j
public abstract class AbstractOpenAIStyleEmbeddingClient implements EmbeddingClient {

    protected final OkHttpClient httpClient;

    protected AbstractOpenAIStyleEmbeddingClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    protected boolean requiresApiKey() {
        return true;
    }

    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        body.addProperty("encoding_format", "float");
    }

    protected int maxBatchSize() {
        return 0;
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        return doEmbed(List.of(text), target).get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        int maxBatch = maxBatchSize();
        if (maxBatch <= 0 || texts.size() <= maxBatch) {
            return doEmbed(texts, target);
        }

        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += maxBatch) {
            int end = Math.min(start + maxBatch, texts.size());
            vectors.addAll(doEmbed(texts.subList(start, end), target));
        }
        return vectors;
    }

    protected List<List<Float>> doEmbed(List<String> texts, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProvider(target);
        if (requiresApiKey()) {
            requireApiKey(provider);
        }
        String model = requireModel(target);
        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        for (String text : texts) {
            input.add(text);
        }
        body.add("input", input);
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

        JsonObject root;
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String errBody = readBody(response.body());
                log.warn("{} embedding request failed: status={}, body={}", provider(), response.code(), errBody);
                throw new ModelClientException(
                        provider() + " embedding request failed: HTTP " + response.code(),
                        classifyStatus(response.code()),
                        response.code()
                );
            }
            root = parseJsonBody(response.body());
        } catch (IOException e) {
            throw new ModelClientException(provider() + " embedding request failed: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        if (root.has("error")) {
            JsonObject err = root.get("error").isJsonObject() ? root.getAsJsonObject("error") : new JsonObject();
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : root.get("error").toString();
            throw new ModelClientException(provider() + " embedding error: " + code + " - " + msg, ModelClientErrorType.PROVIDER_ERROR, null);
        }

        JsonArray data = root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelClientException(provider() + " embedding response missing data", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        if (data.size() != texts.size()) {
            throw new ModelClientException(provider() + " embedding response size mismatch", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<List<Float>> vectors = new ArrayList<>(data.size());
        for (JsonElement element : data) {
            JsonObject item = element.getAsJsonObject();
            JsonArray embedding = item.getAsJsonArray("embedding");
            if (embedding == null || embedding.isEmpty()) {
                throw new ModelClientException(provider() + " embedding response missing embedding", ModelClientErrorType.INVALID_RESPONSE, null);
            }

            List<Float> vector = new ArrayList<>(embedding.size());
            for (JsonElement num : embedding) {
                vector.add(num.getAsFloat());
            }
            vectors.add(vector);
        }
        return vectors;
    }

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

    protected String requireApiKey(AIModelProperties.ProviderConfig provider) {
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new IllegalStateException(provider() + " apiKey is missing");
        }
        return provider.getApiKey();
    }

    protected JsonObject parseJsonBody(ResponseBody body) throws IOException {
        if (body == null) {
            throw new ModelClientException(provider() + " embedding response is empty", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        try {
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ModelClientException(provider() + " embedding response is invalid JSON", ModelClientErrorType.INVALID_RESPONSE, null, e);
        }
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
