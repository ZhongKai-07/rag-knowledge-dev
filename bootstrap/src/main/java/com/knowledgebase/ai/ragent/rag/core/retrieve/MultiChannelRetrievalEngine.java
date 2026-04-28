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

package com.knowledgebase.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.framework.trace.RagTraceNode;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine.RetrievalPlan;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
import com.knowledgebase.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final RetrieverService retrieverService;
    private final KbMetadataReader kbMetadataReader;
    private final MetadataFilterBuilder metadataFilterBuilder;
    @Qualifier("ragRetrievalThreadPoolExecutor")
    private final Executor ragRetrievalExecutor;

    /**
     * 执行多通道检索（仅 KB 场景）
     *
     * @param subIntents 子问题意图列表
     * @param plan       {@link RetrievalPlan}，携带召回池大小 (recallTopK) 与 rerank 后保留数 (rerankTopK)
     * @return 检索到的 Chunk 列表
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents,
                                                           RetrievalPlan plan,
                                                           RetrievalScope scope) {
        SearchContext context = buildSearchContext(subIntents, plan, scope);

        // 单知识库定向检索路径（召回数直接用 recallTopK）
        if (scope.isSingleKb()) {
            String collectionName = kbMetadataReader.getCollectionName(scope.targetKbId());
            if (collectionName == null) {
                return List.of();
            }
            RetrieveRequest req = RetrieveRequest.builder()
                    .query(context.getMainQuestion())
                    .topK(plan.recallTopK())
                    .collectionName(collectionName)
                    .metadataFilters(metadataFilterBuilder.build(context, scope.targetKbId()))
                    .build();
            List<RetrievedChunk> chunks = retrieverService.retrieve(req);

            SearchChannelResult singleResult = SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName("single-kb-" + collectionName)
                    .chunks(chunks)
                    .confidence(1.0)
                    .latencyMs(0)
                    .build();
            return executePostProcessors(List.of(singleResult), context);
        }

        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return List.of();
        }

        return executePostProcessors(channelResults, context);
    }

    /**
     * 执行所有启用的检索通道
     */
    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 过滤启用的通道
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();

        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        // 并行执行所有通道
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                log.info("执行检索通道：{}", channel.getName());
                                return channel.search(context);
                            } catch (IllegalStateException e) {
                                // QSI-3: c2 retriever fail-fast contract violation must propagate to SSE,
                                // 不能被 catch (Exception) 吞成 ERROR log + empty result.
                                throw e;
                            } catch (Exception e) {
                                log.error("检索通道 {} 执行失败", channel.getName(), e);
                                return SearchChannelResult.builder()
                                        .channelType(channel.getType())
                                        .channelName(channel.getName())
                                        .chunks(List.of())
                                        .confidence(0.0)
                                        .build();
                            }
                        },
                        ragRetrievalExecutor
                ))
                .toList();

        // 等待所有通道完成并统计
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.join();
                    } catch (CompletionException e) {
                        // QSI-3: c2 retriever fail-fast contract violation must propagate to SSE.
                        // CompletableFuture.join() 把 supplier 抛出的 IllegalStateException 包成
                        // CompletionException, 这里 unwrap 后显式 rethrow.
                        if (e.getCause() instanceof IllegalStateException ise) {
                            throw ise;
                        }
                        log.error("获取通道检索结果失败", e);
                        return null;
                    } catch (Exception e) {
                        log.error("获取通道检索结果失败", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        // 打印详细统计信息
        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 置信度：{}，耗时：{}ms",
                        result.getChannelName(),
                        result.getConfidence(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    /**
     * 执行后置处理器链
     */
    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        // 过滤启用的处理器并排序
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
        }

        // 初始 Chunk 列表（所有通道的结果合并）
        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        // 依次执行处理器
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
                // 继续执行下一个处理器，不中断整个链
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    /** 构建检索上下文；权限 scope 与安全等级 map 均来自上游 RetrievalScope。 */
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents,
                                              RetrievalPlan plan,
                                              RetrievalScope scope) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();
        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .recallTopK(plan.recallTopK())
                .rerankTopK(plan.rerankTopK())
                .accessScope(scope.accessScope())
                .kbSecurityLevels(scope.kbSecurityLevels())
                .build();
    }

}
