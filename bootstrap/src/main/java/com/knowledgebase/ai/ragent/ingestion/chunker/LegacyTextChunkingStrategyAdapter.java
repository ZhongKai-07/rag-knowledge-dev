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
import com.knowledgebase.ai.ragent.core.chunk.ChunkingStrategy;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;

import java.util.List;

/**
 * 包装老 {@link ChunkingStrategy}（FixedSizeTextChunker / StructureAwareTextChunker），
 * 使其符合 A' 接口。priority=10。
 *
 * <p><b>关键契约</b>：本 adapter 绝不写任何 layout 字段到 VectorChunk.metadata —— layout
 * 写入唯一入口是 {@link StructuredChunkingStrategy}。这条保证 BASIC byte-equivalent 不破。
 */
public class LegacyTextChunkingStrategyAdapter implements IngestionChunkingStrategy {

    private final ChunkingMode supportedMode;
    private final ChunkingStrategy delegate;

    public LegacyTextChunkingStrategyAdapter(ChunkingMode supportedMode, ChunkingStrategy delegate) {
        this.supportedMode = supportedMode;
        this.delegate = delegate;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(ChunkingMode mode, IngestionChunkingInput input) {
        return this.supportedMode == mode;
    }

    @Override
    public List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options) {
        return delegate.chunk(input.text(), options);
    }
}
