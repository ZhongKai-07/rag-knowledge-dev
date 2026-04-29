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
import com.google.gson.JsonParser;
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
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractOpenAIStyleEmbeddingClientTest {

    private OkHttpClient httpClient;
    private Call call;
    private TestEmbeddingClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        call = mock(Call.class);
        client = new TestEmbeddingClient(httpClient);
    }

    @Test
    void singleEmbedReturnsFirstVector() throws Exception {
        mockResponses(okResponse("""
                {"data":[{"embedding":[0.1,0.2]}]}
                """));

        List<Float> vector = client.embed("hello", target("sk-test", 128));

        assertThat(vector).containsExactly(0.1f, 0.2f);
    }

    @Test
    void batchEmbedSendsArrayInputAndReturnsVectors() throws Exception {
        mockResponses(okResponse("""
                {"data":[{"embedding":[1.0]},{"embedding":[2.0]}]}
                """));

        List<List<Float>> vectors = client.embedBatch(List.of("a", "b"), target("sk-test", 128));

        JsonObject body = capturedBody();
        assertThat(body.getAsJsonArray("input").get(0).getAsString()).isEqualTo("a");
        assertThat(body.getAsJsonArray("input").get(1).getAsString()).isEqualTo("b");
        assertThat(vectors).containsExactly(List.of(1.0f), List.of(2.0f));
    }

    @Test
    void batchSlicingHonorsMaxBatchSizeAndPreservesOrder() throws Exception {
        client.maxBatchSize = 2;
        mockResponses(
                okResponse("{\"data\":[{\"embedding\":[1.0]},{\"embedding\":[2.0]}]}"),
                okResponse("{\"data\":[{\"embedding\":[3.0]}]}")
        );

        List<List<Float>> vectors = client.embedBatch(List.of("a", "b", "c"), target("sk-test", 128));

        assertThat(vectors).containsExactly(List.of(1.0f), List.of(2.0f), List.of(3.0f));
        verify(httpClient, times(2)).newCall(any(Request.class));
    }

    @Test
    void requiresApiKeyFalseSkipsAuthorizationHeaderAndDoesNotRequireApiKey() throws Exception {
        client.requiresApiKey = false;
        mockResponses(okResponse("{\"data\":[{\"embedding\":[1.0]}]}"));

        client.embed("hello", target(null, 128));

        Request request = capturedRequest();
        assertThat(request.header("Authorization")).isNull();
    }

    @Test
    void defaultRequiresApiKeySendsAuthorizationHeader() throws Exception {
        mockResponses(okResponse("{\"data\":[{\"embedding\":[1.0]}]}"));

        client.embed("hello", target("sk-test", 128));

        Request request = capturedRequest();
        assertThat(request.header("Authorization")).isEqualTo("Bearer sk-test");
    }

    @Test
    void dimensionIsOmittedWhenCandidateDimensionIsNull() throws Exception {
        mockResponses(okResponse("{\"data\":[{\"embedding\":[1.0]}]}"));

        client.embed("hello", target("sk-test", null));

        assertThat(capturedBody().has("dimensions")).isFalse();
    }

    @Test
    void http429MapsToRateLimitedWithStatusCode() throws Exception {
        mockResponses(response(429, "{\"error\":\"too many\"}"));

        assertThatThrownBy(() -> client.embed("hello", target("sk-test", 128)))
                .isInstanceOfSatisfying(ModelClientException.class, ex -> {
                    assertThat(ex.getErrorType()).isEqualTo(ModelClientErrorType.RATE_LIMITED);
                    assertThat(ex.getStatusCode()).isEqualTo(429);
                });
    }

    @Test
    void responseErrorFieldAndInvalidPayloadsMapToProviderAndInvalidResponseErrors() throws Exception {
        mockResponses(
                okResponse("{\"error\":{\"code\":\"bad_request\",\"message\":\"bad input\"}}"),
                okResponse("{\"data\":[{}]}"),
                okResponse("{\"data\":[{\"embedding\":[1.0]}]}")
        );

        assertThatThrownBy(() -> client.embed("hello", target("sk-test", 128)))
                .isInstanceOfSatisfying(ModelClientException.class, ex ->
                        assertThat(ex.getErrorType()).isEqualTo(ModelClientErrorType.PROVIDER_ERROR));
        assertThatThrownBy(() -> client.embed("hello", target("sk-test", 128)))
                .isInstanceOfSatisfying(ModelClientException.class, ex ->
                        assertThat(ex.getErrorType()).isEqualTo(ModelClientErrorType.INVALID_RESPONSE));
        assertThatThrownBy(() -> client.embedBatch(List.of("a", "b"), target("sk-test", 128)))
                .isInstanceOfSatisfying(ModelClientException.class, ex ->
                        assertThat(ex.getErrorType()).isEqualTo(ModelClientErrorType.INVALID_RESPONSE));
    }

    private void mockResponses(Response... responses) throws IOException {
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
    }

    private Request capturedRequest() {
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(httpClient, times(1)).newCall(captor.capture());
        return captor.getValue();
    }

    private JsonObject capturedBody() throws IOException {
        Request request = capturedRequest();
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
    }

    private Response okResponse(String body) {
        return response(200, body);
    }

    private Response response(int code, String body) {
        Request request = new Request.Builder().url("http://localhost/v1/embeddings").build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }

    private ModelTarget target(String apiKey, Integer dimension) {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://localhost");
        provider.setApiKey(apiKey);
        provider.setEndpoints(new HashMap<>(java.util.Map.of("embedding", "/v1/embeddings")));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("test-embedding");
        candidate.setProvider("test");
        candidate.setModel("text-embedding-test");
        candidate.setDimension(dimension);
        return new ModelTarget("test-embedding", candidate, provider);
    }

    private static class TestEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

        private boolean requiresApiKey = true;
        private int maxBatchSize;

        TestEmbeddingClient(OkHttpClient httpClient) {
            super(httpClient);
        }

        @Override
        public String provider() {
            return "test";
        }

        @Override
        protected boolean requiresApiKey() {
            return requiresApiKey;
        }

        @Override
        protected int maxBatchSize() {
            return maxBatchSize;
        }
    }
}
