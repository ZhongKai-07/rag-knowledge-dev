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

import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.FixedSizeOptions;
import com.knowledgebase.ai.ragent.core.chunk.TextBoundaryOptions;

/**
 * 把异构 ChunkingOptions 收口为 {@link StructuredChunkingDimensions}，让
 * {@link StructuredChunkingStrategy} 不直接耦合 TextBoundaryOptions / FixedSizeOptions。
 *
 * <p><b>真实 API 注意</b>：FixedSizeOptions / TextBoundaryOptions 都是 records，accessor
 * 是 record-style（{@code tb.targetChars()} 而非 {@code tb.getTargetChars()}）。
 */
final class StructuredChunkingOptionsResolver {

    private StructuredChunkingOptionsResolver() {}

    static StructuredChunkingDimensions resolve(ChunkingOptions options) {
        if (options instanceof TextBoundaryOptions tb) {
            return new StructuredChunkingDimensions(tb.targetChars(), tb.maxChars(), tb.minChars());
        }
        if (options instanceof FixedSizeOptions fs) {
            int target = fs.chunkSize();
            int max = (int) Math.ceil(target * 1.3);
            int min = Math.max(1, (int) (target * 0.4));
            return new StructuredChunkingDimensions(target, max, min);
        }
        return new StructuredChunkingDimensions(1400, 1800, 600);
    }
}
