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
import com.knowledgebase.ai.ragent.core.chunk.FixedSizeOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LegacyTextChunkingStrategyAdapterTest {

    @Test
    void supports_strictModeMatchOnly() {
        ChunkingStrategy delegate = mock(ChunkingStrategy.class);
        LegacyTextChunkingStrategyAdapter adapter =
                new LegacyTextChunkingStrategyAdapter(ChunkingMode.STRUCTURE_AWARE, delegate);

        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());
        assertThat(adapter.supports(ChunkingMode.STRUCTURE_AWARE, in)).isTrue();
        assertThat(adapter.supports(ChunkingMode.FIXED_SIZE, in)).isFalse();
        assertThat(adapter.priority()).isEqualTo(10);
    }

    @Test
    void chunk_doesNotWriteAnyLayoutField() {
        ChunkingStrategy delegate = mock(ChunkingStrategy.class);
        VectorChunk delegateOutput = VectorChunk.builder()
                .chunkId("c1").index(0).content("hello").metadata(new HashMap<>()).build();
        when(delegate.chunk(eq("hello"), any())).thenReturn(List.of(delegateOutput));

        LegacyTextChunkingStrategyAdapter adapter =
                new LegacyTextChunkingStrategyAdapter(ChunkingMode.STRUCTURE_AWARE, delegate);
        IngestionChunkingInput in = new IngestionChunkingInput("hello", null, ParseMode.BASIC, Map.of());

        ChunkingOptions opts = new FixedSizeOptions(512, 64);
        List<VectorChunk> out = adapter.chunk(in, opts);

        assertThat(out).hasSize(1);
        Map<String, Object> meta = out.get(0).getMetadata();
        // 关键回归：legacy 路径不能写任何 layout key
        assertThat(meta).doesNotContainKeys(
                VectorMetadataFields.PAGE_NUMBER, VectorMetadataFields.PAGE_START,
                VectorMetadataFields.PAGE_END, VectorMetadataFields.HEADING_PATH,
                VectorMetadataFields.BLOCK_TYPE, VectorMetadataFields.SOURCE_BLOCK_IDS,
                VectorMetadataFields.BBOX_REFS, VectorMetadataFields.TEXT_LAYER_TYPE,
                VectorMetadataFields.LAYOUT_CONFIDENCE);
    }
}
