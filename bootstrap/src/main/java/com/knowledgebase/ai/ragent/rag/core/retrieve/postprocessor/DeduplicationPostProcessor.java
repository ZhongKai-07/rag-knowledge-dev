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

package com.knowledgebase.ai.ragent.rag.core.retrieve.postprocessor;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 去重后置处理器
 *
 * <p>只允许在当前工作集 {@code chunks} 内做去重，不能从原始 {@code results}
 * 中恢复已经被前序处理器删除的 chunk。
 *
 * <p>通道优先级映射使用 {@link IdentityHashMap}（对象引用），依赖前序处理器保持
 * {@link RetrievedChunk} 引用稳定。若某个处理器改为 rebuild chunk 实例，需同步评估这里的优先级查表行为。
 */
@Slf4j
@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "Deduplication";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        Map<RetrievedChunk, Integer> priorityByChunk = buildPriorityByChunk(results);
        Map<RetrievedChunk, Integer> firstSeenByChunk = buildFirstSeenByChunk(chunks);
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();

        for (RetrievedChunk chunk : chunks) {
            String key = generateChunkKey(chunk);
            RetrievedChunk existing = chunkMap.get(key);
            if (existing == null || isBetterCandidate(chunk, existing, priorityByChunk, firstSeenByChunk)) {
                chunkMap.put(key, chunk);
            }
        }

        return new ArrayList<>(chunkMap.values());
    }

    private Map<RetrievedChunk, Integer> buildPriorityByChunk(List<SearchChannelResult> results) {
        Map<RetrievedChunk, Integer> priorityByChunk = new IdentityHashMap<>();
        for (SearchChannelResult result : results) {
            int priority = getChannelPriority(result.getChannelType());
            for (RetrievedChunk chunk : result.getChunks()) {
                priorityByChunk.merge(chunk, priority, Math::min);
            }
        }
        return priorityByChunk;
    }

    private Map<RetrievedChunk, Integer> buildFirstSeenByChunk(List<RetrievedChunk> chunks) {
        Map<RetrievedChunk, Integer> firstSeen = new IdentityHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            firstSeen.putIfAbsent(chunks.get(i), i);
        }
        return firstSeen;
    }

    private boolean isBetterCandidate(RetrievedChunk candidate, RetrievedChunk existing,
                                      Map<RetrievedChunk, Integer> priorityByChunk,
                                      Map<RetrievedChunk, Integer> firstSeenByChunk) {
        int candidatePriority = priorityByChunk.getOrDefault(candidate, Integer.MAX_VALUE);
        int existingPriority = priorityByChunk.getOrDefault(existing, Integer.MAX_VALUE);
        if (candidatePriority != existingPriority) {
            return candidatePriority < existingPriority;
        }

        float candidateScore = candidate.getScore() == null ? Float.NEGATIVE_INFINITY : candidate.getScore();
        float existingScore = existing.getScore() == null ? Float.NEGATIVE_INFINITY : existing.getScore();
        if (Float.compare(candidateScore, existingScore) != 0) {
            return candidateScore > existingScore;
        }

        return firstSeenByChunk.getOrDefault(candidate, Integer.MAX_VALUE)
                < firstSeenByChunk.getOrDefault(existing, Integer.MAX_VALUE);
    }

    private String generateChunkKey(RetrievedChunk chunk) {
        return chunk.getId() != null
                ? chunk.getId()
                : String.valueOf(chunk.getText().hashCode());
    }

    private int getChannelPriority(SearchChannelType type) {
        return switch (type) {
            case INTENT_DIRECTED -> 1;
            case KEYWORD_ES -> 2;
            case VECTOR_GLOBAL -> 3;
            default -> 99;
        };
    }
}
