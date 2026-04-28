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

package com.knowledgebase.ai.ragent.rag.core.retrieve.channel;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 检索上下文
 * <p>
 * 携带检索所需的所有信息，在多个通道之间传递
 */
@Data
public class SearchContext {

    /**
     * 原始问题
     */
    private String originalQuestion;

    /**
     * 重写后的问题
     */
    private String rewrittenQuestion;

    /**
     * 子问题列表
     */
    private List<String> subQuestions;

    /**
     * 意图识别结果
     */
    private List<SubQuestionIntent> intents;

    /**
     * 召回阶段的候选数（向量检索每个目标返回这么多）。
     * 由 {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties#getRecallTopK()} 或
     * 意图节点 override 推导。必须 >= rerankTopK。
     */
    private int recallTopK;

    /**
     * Rerank 后保留并喂给 LLM 的数量（最终 TopK）。
     * 由 {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties#getRerankTopK()} 或
     * {@link com.knowledgebase.ai.ragent.rag.core.intent.IntentNode#getTopK()} override 推导。
     */
    private int rerankTopK;

    /**
     * 当前用户的检索访问范围（RBAC 单一状态源）。
     * {@link AccessScope.All} = 全量放行（SUPER_ADMIN 或系统态），
     * {@link AccessScope.Ids} = 仅该集合内的 KB 可见（空集 = fail-closed）。
     */
    private AccessScope accessScope;

    /**
     * 当前用户对每个可访问 KB 的最高安全等级（kbId → level）。
     * 由 RBAC 解析后预填一次，下游通道 O(1) 查表。
     * key 缺失表示该 KB 不做安全等级过滤（系统态/未登录），value=0 表示仅 level=0 的文档可见。
     */
    private Map<String, Integer> kbSecurityLevels;

    /**
     * Builder-level fail-fast：配置错 (recallTopK < rerankTopK 或非正) 直接 IAE，
     * 避免错配沉默流到 channel / rerank 后再爆。这是和
     * {@link com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties#validate()} 的第二道保险。
     * <p>
     * 不用 Lombok @Builder 是因为需要在 build() 内做校验；Lombok 生成的 build() 可以通过
     * 继承覆盖，但依赖内部字段名 (metadata$value) 和 positional ctor 顺序，跨 Lombok 版本
     * 脆弱。手写 builder 换来完全可控 + 零 Lombok 内部耦合。
     */
    public static SearchContextBuilder builder() {
        return new SearchContextBuilder();
    }

    public static class SearchContextBuilder {
        private String originalQuestion;
        private String rewrittenQuestion;
        private List<String> subQuestions;
        private List<SubQuestionIntent> intents;
        private int recallTopK;
        private int rerankTopK;
        private AccessScope accessScope;
        private Map<String, Integer> kbSecurityLevels;

        public SearchContextBuilder originalQuestion(String v) {
            this.originalQuestion = v;
            return this;
        }

        public SearchContextBuilder rewrittenQuestion(String v) {
            this.rewrittenQuestion = v;
            return this;
        }

        public SearchContextBuilder subQuestions(List<String> v) {
            this.subQuestions = v;
            return this;
        }

        public SearchContextBuilder intents(List<SubQuestionIntent> v) {
            this.intents = v;
            return this;
        }

        public SearchContextBuilder recallTopK(int v) {
            this.recallTopK = v;
            return this;
        }

        public SearchContextBuilder rerankTopK(int v) {
            this.rerankTopK = v;
            return this;
        }

        public SearchContextBuilder accessScope(AccessScope v) {
            this.accessScope = v;
            return this;
        }

        public SearchContextBuilder kbSecurityLevels(Map<String, Integer> v) {
            this.kbSecurityLevels = v;
            return this;
        }

        public SearchContext build() {
            if (recallTopK <= 0) {
                throw new IllegalArgumentException(
                        "SearchContext.recallTopK must be > 0, got: " + recallTopK);
            }
            if (rerankTopK <= 0) {
                throw new IllegalArgumentException(
                        "SearchContext.rerankTopK must be > 0, got: " + rerankTopK);
            }
            if (recallTopK < rerankTopK) {
                throw new IllegalArgumentException(
                        "SearchContext.recallTopK (" + recallTopK
                                + ") must be >= rerankTopK (" + rerankTopK + ")");
            }
            SearchContext ctx = new SearchContext();
            ctx.setOriginalQuestion(originalQuestion);
            ctx.setRewrittenQuestion(rewrittenQuestion);
            ctx.setSubQuestions(subQuestions);
            ctx.setIntents(intents);
            ctx.setRecallTopK(recallTopK);
            ctx.setRerankTopK(rerankTopK);
            ctx.setAccessScope(accessScope);
            ctx.setKbSecurityLevels(kbSecurityLevels);
            return ctx;
        }
    }

    /**
     * 获取主问题（优先使用重写后的问题）
     */
    public String getMainQuestion() {
        return rewrittenQuestion != null ? rewrittenQuestion : originalQuestion;
    }
}
