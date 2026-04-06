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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchProperties {

    private String uris = "http://localhost:9200";
    private String username;
    private String password;
    private String authType = "basic"; // 当前仅实现 basic；aws-sigv4 预留，生产部署时再实现

    private AnalyzerConfig analyzer = new AnalyzerConfig();
    private HybridConfig hybrid = new HybridConfig();

    @Data
    public static class AnalyzerConfig {
        private String defaultAnalyzer = "ik_max_word";
        private String searchAnalyzer = "ik_smart";
    }

    @Data
    public static class HybridConfig {
        private double vectorWeight = 0.5;
        private double textWeight = 0.5;
    }
}
