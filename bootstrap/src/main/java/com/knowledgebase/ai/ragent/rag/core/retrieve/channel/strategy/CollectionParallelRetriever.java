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
import com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Collection 并行检索器
 * 继承模板类，实现 Collection 特定的检索逻辑
 */
@Slf4j
public class CollectionParallelRetriever extends AbstractParallelRetriever<CollectionParallelRetriever.CollectionTask> {

    private final RetrieverService retrieverService;

    /**
     * 单次检索任务载体：collection 名称 + 类型化的 metadata 过滤条件。
     */
    public record CollectionTask(String collectionName, List<MetadataFilter> metadataFilters) {
    }

    public CollectionParallelRetriever(RetrieverService retrieverService, Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, CollectionTask task, int topK) {
        try {
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(task.collectionName())
                            .query(question)
                            .topK(topK)
                            .metadataFilters(task.metadataFilters())
                            .build()
            );
        } catch (IllegalStateException e) {
            // QSI-3: c2 retriever fail-fast contract violation must propagate to SSE,
            // 不能被 catch (Exception) 吞成 ERROR log + empty list.
            throw e;
        } catch (Exception e) {
            log.error("在 collection {} 中检索失败，错误: {}", task.collectionName(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(CollectionTask task) {
        return "Collection: " + task.collectionName();
    }

    @Override
    protected String getStatisticsName() {
        return "全局检索";
    }
}
