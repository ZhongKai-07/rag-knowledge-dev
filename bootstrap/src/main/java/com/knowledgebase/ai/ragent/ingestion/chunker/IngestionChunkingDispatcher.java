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

package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Priority-based dispatch + 同优先级 fail-fast（PR 6 / Q5）。
 * 多个 strategy supports 同一 (mode, input)，最高 priority 唯一获胜；同 priority → 抛异常。
 */
@Component
public class IngestionChunkingDispatcher {

    private final List<IngestionChunkingStrategy> strategies;

    public IngestionChunkingDispatcher(List<IngestionChunkingStrategy> all) {
        this.strategies = all.stream()
                .sorted(Comparator.comparingInt(IngestionChunkingStrategy::priority).reversed())
                .toList();
    }

    public List<VectorChunk> chunk(ChunkingMode mode, IngestionChunkingInput input, ChunkingOptions options) {
        List<IngestionChunkingStrategy> matched = strategies.stream()
                .filter(s -> s.supports(mode, input))
                .toList();
        if (matched.isEmpty()) {
            int pages = input.parseResult() == null ? -1 : input.parseResult().pages().size();
            int tables = input.parseResult() == null ? -1 : input.parseResult().tables().size();
            throw new IllegalStateException(
                    "No chunking strategy supports mode=" + mode
                            + " (pages=" + pages + ", tables=" + tables + ")");
        }
        int top = matched.get(0).priority();
        List<IngestionChunkingStrategy> tied = matched.stream().filter(s -> s.priority() == top).toList();
        if (tied.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous chunking strategies at priority=" + top + ": "
                            + tied.stream().map(s -> s.getClass().getSimpleName()).toList());
        }
        return tied.get(0).chunk(input, options);
    }
}
