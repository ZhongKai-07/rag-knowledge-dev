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

import cn.hutool.core.collection.CollUtil;
import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.rag.config.SearchChannelProperties;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 向量全局检索通道
 * <p>
 * 在所有知识库中进行向量检索，作为兜底策略
 * 当意图识别失败或置信度低时启用
 */
@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final KbMetadataReader kbMetadataReader;
    private final CollectionParallelRetriever parallelRetriever;
    private final MetadataFilterBuilder metadataFilterBuilder;

    public VectorGlobalSearchChannel(RetrieverService retrieverService,
                                     SearchChannelProperties properties,
                                     KbMetadataReader kbMetadataReader,
                                     MetadataFilterBuilder metadataFilterBuilder,
                                     @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.kbMetadataReader = kbMetadataReader;
        this.metadataFilterBuilder = metadataFilterBuilder;
        this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "VectorGlobalSearch";
    }

    @Override
    public int getPriority() {
        return 10;  // 较低优先级
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 检查配置是否启用
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }

        // 条件1：没有识别出任何意图
        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        if (CollUtil.isEmpty(allScores)) {
            log.info("未识别出任何意图，启用全局检索");
            return true;
        }

        // 条件2：意图置信度都很低
        double maxScore = allScores.stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (maxScore < threshold) {
            log.info("意图置信度过低（{}），启用全局检索", maxScore);
            return true;
        }

        return false;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("执行向量全局检索，问题：{}", context.getMainQuestion());

            // 获取所有可访问的 KB
            List<KbCollection> kbs = getAccessibleKBs(context);

            if (kbs.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过全局检索");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // 并行在所有 collection 中检索
            int recallTopK = context.getRecallTopK();
            List<RetrievedChunk> fanOutChunks = retrieveFromAllCollections(
                    context.getMainQuestion(),
                    kbs,
                    context,
                    recallTopK
            );

            // 通道级 sort+cap：多 KB fan-out 后可能 N*recallTopK 条，按 score 降序取前 recallTopK。
            List<RetrievedChunk> cappedChunks = fanOutChunks.stream()
                    .sorted(Comparator.comparing(
                            RetrievedChunk::getScore,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(recallTopK)
                    .toList();

            long latency = System.currentTimeMillis() - startTime;

            log.info("向量全局检索完成，fan-out {} -> cap {} 个 Chunk，耗时 {}ms",
                    fanOutChunks.size(), cappedChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(cappedChunks)
                    .confidence(0.7)  // 全局检索置信度中等
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("向量全局检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 获取所有可访问的 KB（受 RBAC 约束）
     */
    private List<KbCollection> getAccessibleKBs(SearchContext context) {
        AccessScope scope = context.getAccessScope();
        Set<String> visibleKbIds;
        if (scope instanceof AccessScope.All) {
            visibleKbIds = kbMetadataReader.listAllKbIds();
        } else if (scope instanceof AccessScope.Ids ids) {
            visibleKbIds = ids.kbIds();
        } else {
            return List.of();
        }
        if (CollUtil.isEmpty(visibleKbIds)) {
            return List.of();
        }

        Map<String, String> collectionNames = kbMetadataReader.getCollectionNames(visibleKbIds);
        return collectionNames.entrySet().stream()
                .map(e -> new KbCollection(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * 并行在所有 collection 中检索（per-KB metadata filters）
     */
    private List<RetrievedChunk> retrieveFromAllCollections(String question,
                                                            List<KbCollection> kbs,
                                                            SearchContext context,
                                                            int topK) {
        List<CollectionParallelRetriever.CollectionTask> tasks = kbs.stream()
                .map(kb -> new CollectionParallelRetriever.CollectionTask(
                        kb.collectionName(),
                        metadataFilterBuilder.build(context, kb.kbId())))
                .toList();
        return parallelRetriever.executeParallelRetrieval(question, tasks, topK);
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }

    private record KbCollection(String kbId, String collectionName) {
    }
}
