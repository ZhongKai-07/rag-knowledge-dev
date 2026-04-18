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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量库类型配置校验器。
 * <p>
 * 非 opensearch 配置时打印 WARN: Milvus/Pg 实现不回填 {@code RetrievedChunk.kbId},
 * {@code AuthzPostProcessor} 会对 kbId==null 的 chunk 在认证会话里 fail-closed。
 * 开发/测试环境可用，不建议生产。
 */
@Slf4j
@Component
public class RagVectorTypeValidator {

    @Value("${rag.vector.type:opensearch}")
    private String vectorType;

    @PostConstruct
    public void validate() {
        if (!"opensearch".equalsIgnoreCase(vectorType)) {
            log.warn("RAG vector backend is '{}' (not opensearch). " +
                            "AuthzPostProcessor will fail-close all chunks where kbId is null " +
                            "in authenticated sessions. This is a dev-only configuration — " +
                            "do NOT use in production without equivalent authz metadata support.",
                    vectorType);
        }
    }
}
