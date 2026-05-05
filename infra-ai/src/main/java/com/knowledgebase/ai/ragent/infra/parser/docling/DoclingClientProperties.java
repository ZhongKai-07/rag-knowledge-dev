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

package com.knowledgebase.ai.ragent.infra.parser.docling;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Docling 服务客户端配置。
 *
 * <p>{@code enabled=false} 时 {@link com.knowledgebase.ai.ragent.infra.parser.docling.DoclingClient}
 * bean 不会被注册，下游 {@code DoclingDocumentParser} 也不会注册（{@code @ConditionalOnProperty}），
 * ENHANCED 路径自动 degrade 到 Tika。
 */
@Data
@ConfigurationProperties(prefix = "docling.service")
public class DoclingClientProperties {

    /** Whether the Docling backend is wired into the parser chain. */
    private boolean enabled = false;

    /** Docling base URL, e.g. {@code http://localhost:5001}. */
    private String host = "http://localhost:5001";

    /** Per-call read / call timeout for both health and convert endpoints. */
    private int timeoutMs = 60_000;

    private String healthEndpoint = "/health";

    private String convertEndpoint = "/v1alpha/convert/file";
}
