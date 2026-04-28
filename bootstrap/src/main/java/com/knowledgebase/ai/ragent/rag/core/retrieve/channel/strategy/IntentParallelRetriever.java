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

package com.knowledgebase.ai.ragent.rag.core.retrieve.channel.strategy;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 意图并行检索器
 * 继承模板类，实现意图特定的检索逻辑
 */
@Slf4j
public class IntentParallelRetriever extends AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    private final RetrieverService retrieverService;
    private final MetadataFilterBuilder metadataFilterBuilder;

    public record IntentTask(NodeScore nodeScore, int intentTopK, List<MetadataFilter> metadataFilters) {
    }

    public IntentParallelRetriever(RetrieverService retrieverService,
                                   MetadataFilterBuilder metadataFilterBuilder,
                                   Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
        this.metadataFilterBuilder = metadataFilterBuilder;
    }

    /**
     * 并行检索每个意图的 KB，每个目标各自取 perIntentRecallTopK 个候选。
     * 通道级 sort+cap 由 caller (IntentDirectedSearchChannel) 统一处理。
     */
    public List<RetrievedChunk> executeParallelRetrieval(String question,
                                                         List<NodeScore> targets,
                                                         int perIntentRecallTopK,
                                                         SearchContext context) {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> new IntentTask(
                        nodeScore,
                        resolveIntentRecallTopK(nodeScore, perIntentRecallTopK),
                        metadataFilterBuilder.build(
                                context, nodeScore.getNode().getKbId())))
                .toList();
        return super.executeParallelRetrieval(question, intentTasks, perIntentRecallTopK);
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, IntentTask task, int ignoredTopK) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        try {
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(node.getCollectionName())
                            .query(question)
                            .topK(task.intentTopK())
                            .metadataFilters(task.metadataFilters())
                            .build()
            );
        } catch (Exception e) {
            log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collection: {}, 错误: {}",
                    node.getId(), node.getName(), node.getCollectionName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(IntentTask task) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        return String.format("意图ID: %s, 意图名称: %s", node.getId(), node.getName());
    }

    @Override
    protected String getStatisticsName() {
        return "意图检索";
    }

    /**
     * 单意图召回数：优先 node.topK（已改语义为最终保留数，这里按最终保留数兜底放大到 perIntent recall），
     * 否则用全局 perIntentRecallTopK。
     */
    private int resolveIntentRecallTopK(NodeScore nodeScore, int perIntentRecallTopK) {
        if (nodeScore != null && nodeScore.getNode() != null) {
            Integer nodeTopK = nodeScore.getNode().getTopK();
            if (nodeTopK != null && nodeTopK > 0) {
                // node.topK 是"最终保留数"。召回至少放到与全局 recall 同等规模，保证 rerank 有挑选空间。
                return Math.max(perIntentRecallTopK, nodeTopK);
            }
        }
        return perIntentRecallTopK;
    }
}
