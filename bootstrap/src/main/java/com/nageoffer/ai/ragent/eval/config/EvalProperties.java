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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * eval 域配置属性（rag.eval.*）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.eval")
public class EvalProperties {

    private PythonService pythonService = new PythonService();
    private Synthesis synthesis = new Synthesis();
    private Run run = new Run();

    @Data
    public static class PythonService {
        private String url = "http://ragent-eval:9091";
        private int timeoutMs = 120_000;
        private int maxRetries = 2;
    }

    @Data
    public static class Synthesis {
        private int defaultCount = 50;
        private int maxPerDoc = 5;
        private String strongModel = "qwen-max";
        /** Java 端把 N 个 chunk 拆多少批送 Python，单批 timeout 受控于 synthesisTimeoutMs */
        private int batchSize = 5;
        /** 单次 /synthesize HTTP 调用的超时，远大于 pythonService.timeoutMs（120s）——LLM 批调用可能 6-10 分钟 */
        private int synthesisTimeoutMs = 600_000;
    }

    @Data
    public static class Run {
        private int batchSize = 5;
        private int perItemTimeoutMs = 30_000;
        private int maxParallelRuns = 1;
    }
}
