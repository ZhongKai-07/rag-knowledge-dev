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

package com.knowledgebase.ai.ragent.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties;
import com.knowledgebase.ai.ragent.rag.config.RagSourcesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemSnapshotBuilderTest {

    private RagRetrievalProperties retrieval;
    private RagSourcesProperties sources;
    private AIModelProperties ai;
    private Environment env;
    private SystemSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        retrieval = mock(RagRetrievalProperties.class);
        sources = mock(RagSourcesProperties.class);
        ai = mock(AIModelProperties.class);
        env = new MockEnvironment().withProperty("git.commit.id.abbrev", "ad467a08");

        when(retrieval.getRecallTopK()).thenReturn(30);
        when(retrieval.getRerankTopK()).thenReturn(10);
        when(sources.getEnabled()).thenReturn(Boolean.TRUE);
        when(sources.getMinTopScore()).thenReturn(0.55D);

        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        chat.setDefaultModel("qwen3-max");
        AIModelProperties.ModelGroup emb = new AIModelProperties.ModelGroup();
        emb.setDefaultModel("qwen-emb-8b");
        AIModelProperties.ModelGroup rr = new AIModelProperties.ModelGroup();
        rr.setDefaultModel("qwen3-rerank");
        when(ai.getChat()).thenReturn(chat);
        when(ai.getEmbedding()).thenReturn(emb);
        when(ai.getRerank()).thenReturn(rr);

        builder = new SystemSnapshotBuilder(new ObjectMapper(), retrieval, sources, ai, env);
    }

    @Test
    void build_emits_all_required_fields() throws Exception {
        String json = builder.build();
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("recall_top_k").asInt()).isEqualTo(30);
        assertThat(root.get("rerank_top_k").asInt()).isEqualTo(10);
        assertThat(root.get("sources_enabled").asBoolean()).isTrue();
        assertThat(root.get("sources_min_top_score").asDouble()).isEqualTo(0.55D);
        assertThat(root.get("eval_sources_disabled").asBoolean()).isTrue();
        assertThat(root.get("chat_model").asText()).isEqualTo("qwen3-max");
        assertThat(root.get("embedding_model").asText()).isEqualTo("qwen-emb-8b");
        assertThat(root.get("rerank_model").asText()).isEqualTo("qwen3-rerank");
        assertThat(root.get("git_commit").asText()).isEqualTo("ad467a08");
        assertThat(root.get("config_hash").asText()).startsWith("sha256:");
    }

    @Test
    void same_config_yields_same_hash() {
        String a = builder.build();
        String b = builder.build();
        assertThat(extractHash(a)).isEqualTo(extractHash(b));
    }

    @Test
    void config_change_yields_different_hash() throws Exception {
        String before = builder.build();
        when(retrieval.getRecallTopK()).thenReturn(40);
        String after = builder.build();
        assertThat(extractHash(before)).isNotEqualTo(extractHash(after));
    }

    private String extractHash(String json) {
        try {
            return new ObjectMapper().readTree(json).get("config_hash").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
