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

package com.knowledgebase.ai.ragent.eval.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalPropertiesTest {

    @Test
    void bindsFromYamlLikeProperties() {
        var env = new StandardEnvironment();
        Map<String, Object> source = new HashMap<>();
        source.put("rag.eval.python-service.url", "http://ragent-eval:9091");
        source.put("rag.eval.python-service.timeout-ms", "120000");
        source.put("rag.eval.python-service.max-retries", "2");
        source.put("rag.eval.synthesis.default-count", "50");
        source.put("rag.eval.synthesis.max-per-doc", "5");
        source.put("rag.eval.synthesis.strong-model", "qwen-max");
        source.put("rag.eval.synthesis.batch-size", "5");
        source.put("rag.eval.synthesis.synthesis-timeout-ms", "600000");
        source.put("rag.eval.run.batch-size", "5");
        source.put("rag.eval.run.per-item-timeout-ms", "30000");
        source.put("rag.eval.run.max-parallel-runs", "1");
        env.getPropertySources().addFirst(new MapPropertySource("test", source));
        EvalProperties props = Binder.get(env).bind("rag.eval", EvalProperties.class).get();

        assertThat(props.getPythonService().getUrl()).isEqualTo("http://ragent-eval:9091");
        assertThat(props.getPythonService().getTimeoutMs()).isEqualTo(120_000);
        assertThat(props.getSynthesis().getDefaultCount()).isEqualTo(50);
        assertThat(props.getSynthesis().getMaxPerDoc()).isEqualTo(5);
        assertThat(props.getSynthesis().getStrongModel()).isEqualTo("qwen-max");
        assertThat(props.getSynthesis().getBatchSize()).isEqualTo(5);
        assertThat(props.getSynthesis().getSynthesisTimeoutMs()).isEqualTo(600_000);
        assertThat(props.getRun().getBatchSize()).isEqualTo(5);
        assertThat(props.getRun().getMaxParallelRuns()).isEqualTo(1);
    }

    @Test
    void run_evaluate_fields_bind_from_yaml() {
        var env = new StandardEnvironment();
        Map<String, Object> source = new HashMap<>();
        source.put("rag.eval.run.evaluate-batch-size", "7");
        source.put("rag.eval.run.evaluate-timeout-ms", "900000");
        env.getPropertySources().addFirst(new MapPropertySource("test", source));
        EvalProperties props = Binder.get(env).bind("rag.eval", EvalProperties.class).get();

        assertThat(props.getRun().getEvaluateBatchSize()).isEqualTo(7);
        assertThat(props.getRun().getEvaluateTimeoutMs()).isEqualTo(900_000);
    }

    @Test
    void run_evaluate_fields_default_values() {
        EvalProperties props = new EvalProperties();
        assertThat(props.getRun().getEvaluateBatchSize()).isEqualTo(5);
        assertThat(props.getRun().getEvaluateTimeoutMs()).isEqualTo(600_000);
    }
}
