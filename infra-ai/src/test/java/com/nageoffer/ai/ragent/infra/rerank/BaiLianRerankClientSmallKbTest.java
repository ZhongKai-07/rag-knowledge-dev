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

package com.nageoffer.ai.ragent.infra.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link BaiLianRerankClient} 对"候选数 &lt; 请求 topN"小库场景的防御性 clamp。
 *
 * <p>百炼 API 在 {@code top_n > documents.length} 时的行为未在文档中明确，
 * 实测也不一致。Client 侧必须在发请求前 clamp 到 candidates.size()，保证请求合法。
 */
class BaiLianRerankClientSmallKbTest {

    private MockWebServer server;

    private BaiLianRerankClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new BaiLianRerankClient(new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void clampsTopNToCandidatesSizeWhenCandidatesSmaller() throws Exception {
        // Serve a minimal valid 百炼 rerank response with 3 results
        String body = "{\"output\":{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.9,\"document\":{\"text\":\"a\"}},"
                + "{\"index\":1,\"relevance_score\":0.7,\"document\":{\"text\":\"b\"}},"
                + "{\"index\":2,\"relevance_score\":0.3,\"document\":{\"text\":\"c\"}}"
                + "]}}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(body)
                .addHeader("Content-Type", "application/json"));

        List<RetrievedChunk> candidates = List.of(
                RetrievedChunk.builder().id("a").text("a").score(0.1f).build(),
                RetrievedChunk.builder().id("b").text("b").score(0.2f).build(),
                RetrievedChunk.builder().id("c").text("c").score(0.3f).build());

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setProvider("bailian");
        candidate.setModel("qwen3-rerank");
        // 直接把 candidate.url 指向 MockWebServer；ModelUrlResolver 优先使用 candidate.url，
        // 这样无需配置 provider.endpoints。
        candidate.setUrl(server.url("/").toString());

        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString());
        provider.setApiKey("k");

        ModelTarget target = new ModelTarget("qwen3-rerank", candidate, provider);

        // Ask for topN=10 but we only have 3 candidates → API should be called with top_n=3.
        List<RetrievedChunk> out = client.rerank("q", candidates, 10, target);

        RecordedRequest req = server.takeRequest();
        String sent = req.getBody().readUtf8();
        JsonObject json = JsonParser.parseString(sent).getAsJsonObject();
        int topNSent =
                json.getAsJsonObject("parameters").get("top_n").getAsInt();
        assertThat(topNSent).isEqualTo(3);

        assertThat(out).hasSize(3);
    }
}
