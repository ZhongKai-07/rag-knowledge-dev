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

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 系统功能配置
 *
 * <p>
 * 用于管理 RAG 系统的各项功能开关，例如查询重写等
 * </p>
 *
 * <pre>
 * 示例配置：
 *
 * rag:
 *   query-rewrite:
 *     enabled: true
 * </pre>
 */
@Data
@Configuration
public class RAGConfigProperties {

    /**
     * 查询重写功能开关
     * <p>
     * 控制是否启用查询重写功能，查询重写可以将用户的查询语句优化为更适合检索的形式
     * 默认值：{@code true}
     */
    @Value("${rag.query-rewrite.enabled:true}")
    private Boolean queryRewriteEnabled;

    /**
     * 改写时用于承接上下文的最大历史消息数
     */
    @Value("${rag.query-rewrite.max-history-messages:4}")
    private Integer queryRewriteMaxHistoryMessages;

    /**
     * 改写时用于承接上下文的最大字符数
     */
    @Value("${rag.query-rewrite.max-history-chars:500}")
    private Integer queryRewriteMaxHistoryChars;

    /**
     * 推荐问题功能总开关
     */
    @Value("${rag.suggestions.enabled:true}")
    private Boolean suggestionsEnabled;

    /**
     * 推荐问题独立调用使用的小模型 ID，留空则走 ai.chat.default-model
     */
    @Value("${rag.suggestions.model-id:}")
    private String suggestionsModelId;

    /**
     * 推荐问题输出 token 上限
     */
    @Value("${rag.suggestions.max-output-tokens:150}")
    private Integer suggestionsMaxOutputTokens;

    /**
     * 推荐问题异步生成超时兜底（毫秒）
     */
    @Value("${rag.suggestions.timeout-ms:5000}")
    private Long suggestionsTimeoutMs;
}
