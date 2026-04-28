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

package com.knowledgebase.ai.ragent.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 向量库类型配置校验器。
 * <p>
 * 当前生产前提是 OpenSearch：Milvus / pg 的权限过滤与 sources 元数据仍未补齐。
 * 非 OpenSearch 后端必须显式打开 dev override，否则启动期 fail-fast。
 */
@Slf4j
@Component
public class RagVectorTypeValidator {

    @Value("${rag.vector.type:opensearch}")
    private String vectorType;

    @Value("${rag.vector.allow-incomplete-backend:false}")
    private boolean allowIncomplete;

    @PostConstruct
    public void validate() {
        String normalized = vectorType == null ? "" : vectorType.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("rag.vector.type must not be blank. Use opensearch, milvus, or pg.");
        }
        if ("opensearch".equals(normalized)) {
            return;
        }
        if (!isKnownIncompleteBackend(normalized)) {
            throw new IllegalStateException("RAG vector backend '" + normalized + "' is not recognized. " +
                    "Supported rag.vector.type values are opensearch, milvus, and pg.");
        }
        if (allowIncomplete) {
            log.warn("RAG vector backend '{}' is incomplete. rag.vector.allow-incomplete-backend=true " +
                            "allows local/dev startup only; do not use this in production until backlog SL-1 " +
                            "adds equivalent authz filters and source metadata support.",
                    normalized);
            return;
        }
        throw new IllegalStateException("RAG vector backend '" + normalized + "' is not ready for production. " +
                "Only opensearch currently supports the required authz filters and source metadata. " +
                "Set rag.vector.allow-incomplete-backend=true only for local/dev verification, or complete " +
                "backlog SL-1 before enabling milvus/pg.");
    }

    private boolean isKnownIncompleteBackend(String normalized) {
        return "milvus".equals(normalized) || "pg".equals(normalized);
    }
}
