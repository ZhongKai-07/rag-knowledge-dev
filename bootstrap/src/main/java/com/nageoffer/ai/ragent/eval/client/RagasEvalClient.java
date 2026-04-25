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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.domain.SynthesizeRequest;
import com.nageoffer.ai.ragent.eval.domain.SynthesizeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Python ragent-eval 服务 HTTP 客户端。
 *
 * <p>无状态、无 ThreadLocal；失败快速抛异常，由调用方决定兜底策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagasEvalClient {

    private final EvalProperties evalProperties;

    private RestClient buildClient(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutMs, 10_000));
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(evalProperties.getPythonService().getUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    log.debug("[ragas-eval] {} {}", request.getMethod(), request.getURI());
                    return execution.execute(request, body);
                })
                .build();
    }

    public SynthesizeResponse synthesize(SynthesizeRequest request) {
        return buildClient(evalProperties.getSynthesis().getSynthesisTimeoutMs())
                .post()
                .uri("/synthesize")
                .body(request)
                .retrieve()
                .body(SynthesizeResponse.class);
    }

    public HealthStatus health() {
        return buildClient(evalProperties.getPythonService().getTimeoutMs())
                .get()
                .uri("/health")
                .retrieve()
                .body(HealthStatus.class);
    }

    public record HealthStatus(
            String status,
            @JsonProperty("ragas_version") String ragasVersion,
            @JsonProperty("evaluator_llm") String evaluatorLlm
    ) {
    }
}
