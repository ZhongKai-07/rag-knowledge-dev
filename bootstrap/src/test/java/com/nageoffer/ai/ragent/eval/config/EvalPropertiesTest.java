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

package com.nageoffer.ai.ragent.eval.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalPropertiesTest {

    @Test
    void bindsFromYamlLikeProperties() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "rag.eval.python-service.url", "http://ragent-eval:9091",
                "rag.eval.python-service.timeout-ms", "120000",
                "rag.eval.python-service.max-retries", "2",
                "rag.eval.synthesis.default-count", "50",
                "rag.eval.synthesis.max-per-doc", "5",
                "rag.eval.synthesis.strong-model", "qwen-max",
                "rag.eval.run.batch-size", "5",
                "rag.eval.run.per-item-timeout-ms", "30000",
                "rag.eval.run.max-parallel-runs", "1"
        )));
        EvalProperties props = Binder.get(env).bind("rag.eval", EvalProperties.class).get();

        assertThat(props.getPythonService().getUrl()).isEqualTo("http://ragent-eval:9091");
        assertThat(props.getPythonService().getTimeoutMs()).isEqualTo(120_000);
        assertThat(props.getSynthesis().getDefaultCount()).isEqualTo(50);
        assertThat(props.getSynthesis().getMaxPerDoc()).isEqualTo(5);
        assertThat(props.getSynthesis().getStrongModel()).isEqualTo("qwen-max");
        assertThat(props.getRun().getBatchSize()).isEqualTo(5);
        assertThat(props.getRun().getMaxParallelRuns()).isEqualTo(1);
    }
}
