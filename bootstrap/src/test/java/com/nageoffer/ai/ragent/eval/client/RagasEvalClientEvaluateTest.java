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

package com.nageoffer.ai.ragent.eval.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.domain.EvaluateRequest;
import com.nageoffer.ai.ragent.eval.domain.EvaluateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class RagasEvalClientEvaluateTest {

    private WireMockServer wm;
    private RagasEvalClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        EvalProperties props = new EvalProperties();
        props.getPythonService().setUrl("http://localhost:" + wm.port());
        props.getRun().setEvaluateTimeoutMs(5_000);
        client = new RagasEvalClient(props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void evaluate_serializes_request_and_parses_response() {
        String responseJson = """
                {"results": [
                    {"gold_item_id":"g1",
                     "faithfulness":0.92,"answer_relevancy":0.88,
                     "context_precision":0.81,"context_recall":0.85,
                     "error":null}
                ]}
                """;
        wm.stubFor(post(urlEqualTo("/evaluate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)));

        EvaluateRequest req = new EvaluateRequest(List.of(
                new EvaluateRequest.Item("g1", "what is X?",
                        List.of("ctx1", "ctx2"), "X is Y", "X is Y")));

        EvaluateResponse resp = client.evaluate("run-123", req);

        assertThat(resp.results()).hasSize(1);
        EvaluateResponse.MetricResult mr = resp.results().get(0);
        assertThat(mr.goldItemId()).isEqualTo("g1");
        assertThat(mr.faithfulness()).isEqualByComparingTo(new BigDecimal("0.92"));
        assertThat(mr.contextRecall()).isEqualByComparingTo(new BigDecimal("0.85"));
        assertThat(mr.error()).isNull();

        wm.verify(postRequestedFor(urlEqualTo("/evaluate"))
                .withHeader("X-Eval-Run-Id", equalTo("run-123")));

        String captured = wm.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(captured).contains("\"gold_item_id\":\"g1\"");
        assertThat(captured).contains("\"ground_truth\":\"X is Y\"");
    }

    @Test
    void evaluate_propagates_per_item_error_field() {
        String responseJson = """
                {"results": [
                    {"gold_item_id":"g1","faithfulness":null,
                     "answer_relevancy":null,"context_precision":null,
                     "context_recall":null,"error":"openai 429"}
                ]}
                """;
        wm.stubFor(post(urlEqualTo("/evaluate"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(responseJson)));

        EvaluateRequest req = new EvaluateRequest(List.of(
                new EvaluateRequest.Item("g1", "q", List.of("c"), "a", "gt")));
        EvaluateResponse resp = client.evaluate("run-1", req);

        assertThat(resp.results().get(0).error()).isEqualTo("openai 429");
        assertThat(resp.results().get(0).faithfulness()).isNull();
    }
}
