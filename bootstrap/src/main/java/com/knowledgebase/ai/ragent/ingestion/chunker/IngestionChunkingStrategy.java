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

import java.util.List;

/**
 * A' chunker 抽象（PR 6 / Q5）：capability-based dispatch + priority。
 *
 * <p>{@link #supports(ChunkingMode, IngestionChunkingInput)} 不依赖 settings.strategy 一个轴，
 * structured strategy 看 layout 可用性、legacy adapter 看 mode 严格匹配。
 *
 * <p>{@link #priority()} 决定 dispatch 时的优先顺序：StructuredChunkingStrategy = 100，
 * LegacyTextChunkingStrategyAdapter = 10。同优先级多命中由
 * {@link IngestionChunkingDispatcher} fail-fast。
 */
public interface IngestionChunkingStrategy {

    /** Higher value tried first in dispatch. */
    int priority();

    /** 是否能处理当前 (mode, input) 组合。MUST be cheap — 每文档调一次。 */
    boolean supports(ChunkingMode mode, IngestionChunkingInput input);

    /**
     * Chunk the input. layout 字段（如果有）由实现通过
     * {@code ChunkLayoutMetadata.writer(...)} 写入返回 chunk 的 metadata Map。
     */
    List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options);
}
