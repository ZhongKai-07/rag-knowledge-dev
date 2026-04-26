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

package com.nageoffer.ai.ragent.eval.service;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.RagRetrievalProperties;
import com.nageoffer.ai.ragent.rag.config.RagSourcesProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * eval 域 - 系统配置快照构造器（spec §5.3 / §10 gotcha #1）。
 *
 * <p>历史对比的唯一凭证。任何影响 RAG 行为的新配置必须在这里加字段。
 * 加字段后单测会因 hash 漂移而提示，PR review checklist 必查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSnapshotBuilder {

    private final ObjectMapper objectMapper;
    private final RagRetrievalProperties retrievalProps;
    private final RagSourcesProperties sourcesProps;
    private final AIModelProperties aiProps;
    private final Environment environment;

    public String build() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recall_top_k", retrievalProps.getRecallTopK());
        node.put("rerank_top_k", retrievalProps.getRerankTopK());
        node.put("sources_enabled", Boolean.TRUE.equals(sourcesProps.getEnabled()));
        node.put("sources_min_top_score",
                sourcesProps.getMinTopScore() != null ? sourcesProps.getMinTopScore() : 0.55D);
        // P2-1: 常量声明 eval 链路关闭 citation；同步必须更新 ChatForEvalService.cards
        node.put("eval_sources_disabled", true);
        node.put("chat_model", aiProps.getChat().getDefaultModel());
        node.put("embedding_model", aiProps.getEmbedding().getDefaultModel());
        node.put("rerank_model", aiProps.getRerank().getDefaultModel());

        node.put("config_hash", "sha256:" + SecureUtil.sha256(serialize(node)));

        // git_commit 是元数据不进 hash —— 项目无 git-commit-id-maven-plugin 永远 "unknown"
        node.put("git_commit", environment.getProperty("git.commit.id.abbrev", "unknown"));

        return serialize(node);
    }

    private String serialize(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("snapshot serialization failed", e);
        }
    }
}
