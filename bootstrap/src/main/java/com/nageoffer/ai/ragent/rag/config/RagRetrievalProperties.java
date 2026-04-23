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

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 两阶段检索配置：召回数（bi-encoder 检索候选数）与 rerank 后最终保留数（喂给 LLM 的数量）。
 * 绑定阶段 + 运行时双重 fail-fast：配置错直接阻止启动或构建 SearchContext。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.retrieval")
public class RagRetrievalProperties {

    /** 召回阶段候选数（向量检索返回），默认 30。必须 >= rerankTopK。 */
    private int recallTopK = 30;

    /** Rerank 后保留并喂给 LLM 的数量，默认 10。 */
    private int rerankTopK = 10;

    @PostConstruct
    public void validate() {
        if (recallTopK <= 0) {
            throw new IllegalStateException(
                    "rag.retrieval.recall-top-k must be > 0, got: " + recallTopK);
        }
        if (rerankTopK <= 0) {
            throw new IllegalStateException(
                    "rag.retrieval.rerank-top-k must be > 0, got: " + rerankTopK);
        }
        if (recallTopK < rerankTopK) {
            throw new IllegalStateException(
                    "rag.retrieval.recall-top-k (" + recallTopK + ") must be >= rerank-top-k ("
                            + rerankTopK + ")");
        }
    }
}
