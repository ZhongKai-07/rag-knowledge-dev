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
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredChunkingStrategyTest {

    private final StructuredChunkingStrategy strategy = new StructuredChunkingStrategy();

    @Test
    void priority_is100() {
        assertThat(strategy.priority()).isEqualTo(100);
    }

    @Test
    void supports_falseWhenParseResultNull() {
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.ENHANCED, Map.of());
        assertThat(strategy.supports(ChunkingMode.STRUCTURE_AWARE, in)).isFalse();
    }

    @Test
    void supports_falseWhenPagesAndTablesEmpty() {
        ParseResult pr = new ParseResult("t", Map.of(), List.of(), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("t", pr, ParseMode.ENHANCED, Map.of());
        assertThat(strategy.supports(ChunkingMode.STRUCTURE_AWARE, in)).isFalse();
    }

    @Test
    void supports_trueWhenPagesNonEmpty_regardlessOfMode() {
        DocumentPageText page = new DocumentPageText(null, 1, "hello", null, null, List.of());
        ParseResult pr = new ParseResult("hello", Map.of(), List.of(page), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("hello", pr, ParseMode.ENHANCED, Map.of());

        assertThat(strategy.supports(ChunkingMode.STRUCTURE_AWARE, in)).isTrue();
        assertThat(strategy.supports(ChunkingMode.FIXED_SIZE, in)).isTrue();
    }

    @Test
    void chunk_titleBoundarySplit_keepsHeadingPath() {
        List<LayoutBlock> blocks = List.of(
                new LayoutBlock("b1", BlockType.TITLE, 1, null, "Chapter 1", 1, 0.99D, 1, List.of()),
                new LayoutBlock("b2", BlockType.PARAGRAPH, 1, null, "Para A", 2, 0.98D, null, List.of("Chapter 1")));
        DocumentPageText page = new DocumentPageText(null, 1, "Chapter 1\nPara A", "NATIVE_TEXT", 0.98D, blocks);
        ParseResult pr = new ParseResult("Chapter 1\nPara A", Map.of(), List.of(page), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("Chapter 1\nPara A", pr, ParseMode.ENHANCED, Map.of());

        List<VectorChunk> out = strategy.chunk(in, null);

        assertThat(out).hasSize(1);
        VectorChunk vc = out.get(0);
        assertThat(ChunkLayoutMetadata.headingPath(vc)).containsExactly("Chapter 1");
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(1);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(1);
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo(BlockType.PARAGRAPH.name());
    }

    @Test
    void chunk_tableIsAtomic_singleChunk() {
        LayoutTable table = new LayoutTable("t1", 3, null,
                List.of(List.of("h1", "h2"), List.of("a", "b")),
                List.of("Section 5"), 1, 0.99D);
        ParseResult pr = new ParseResult("", Map.of(), List.of(), List.of(table));
        IngestionChunkingInput in = new IngestionChunkingInput("", pr, ParseMode.ENHANCED, Map.of());

        List<VectorChunk> out = strategy.chunk(in, null);

        assertThat(out).hasSize(1);
        VectorChunk vc = out.get(0);
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo(BlockType.TABLE.name());
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(3);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(3);
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).containsExactly("t1");
    }

    @Test
    void chunk_v051Limits_textLayerTypeAndConfidenceStayNull() {
        DocumentPageText page = new DocumentPageText(null, 1, "x",
                /*textLayerType*/ null, /*confidence*/ null,
                List.of(new LayoutBlock("b1", BlockType.PARAGRAPH, 1, null, "x", 1, /*confidence*/ null, null, List.of())));
        ParseResult pr = new ParseResult("x", Map.of(), List.of(page), List.of());
        IngestionChunkingInput in = new IngestionChunkingInput("x", pr, ParseMode.ENHANCED, Map.of());

        List<VectorChunk> out = strategy.chunk(in, null);

        assertThat(out).hasSize(1);
        Map<String, Object> meta = out.get(0).getMetadata();
        assertThat(meta).doesNotContainKey(VectorMetadataFields.TEXT_LAYER_TYPE);
        assertThat(meta).doesNotContainKey(VectorMetadataFields.LAYOUT_CONFIDENCE);
    }
}
