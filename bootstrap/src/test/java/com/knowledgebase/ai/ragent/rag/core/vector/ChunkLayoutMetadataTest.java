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

package com.knowledgebase.ai.ragent.rag.core.vector;

import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkLayoutMetadataTest {

    @Test
    void readback_threeShapesAllResolveToList() {
        VectorChunk a = new VectorChunk(); a.setMetadata(new HashMap<>(Map.of(
                VectorMetadataFields.HEADING_PATH, List.of("第三章", "3.2 信用风险"))));
        VectorChunk b = new VectorChunk(); b.setMetadata(new HashMap<>(Map.of(
                VectorMetadataFields.HEADING_PATH, new String[]{"第三章", "3.2 信用风险"})));
        VectorChunk c = new VectorChunk(); c.setMetadata(new HashMap<>(Map.of(
                VectorMetadataFields.HEADING_PATH, "[\"第三章\",\"3.2 信用风险\"]")));

        assertThat(ChunkLayoutMetadata.headingPath(a)).containsExactly("第三章", "3.2 信用风险");
        assertThat(ChunkLayoutMetadata.headingPath(b)).containsExactly("第三章", "3.2 信用风险");
        assertThat(ChunkLayoutMetadata.headingPath(c)).containsExactly("第三章", "3.2 信用风险");
    }

    @Test
    void readback_missingKeyReturnsNull_notEmpty() {
        // v4 review P2 #5：缺 key 返 null（不是空 List），保 SourceChunk 序列化对称
        VectorChunk vc = new VectorChunk(); vc.setMetadata(new HashMap<>());
        assertThat(ChunkLayoutMetadata.headingPath(vc)).isNull();
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).isNull();
    }

    @Test
    void writer_skipsNullAndEmpty() {
        VectorChunk vc = new VectorChunk(); vc.setMetadata(new HashMap<>());
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(null)
                .headingPath(List.of())
                .blockType(null)
                .pageRange(null, null);
        assertThat(vc.getMetadata()).isEmpty();
    }

    @Test
    void writer_putsValueWhenPresent() {
        VectorChunk vc = new VectorChunk(); vc.setMetadata(new HashMap<>());
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(12)
                .pageRange(12, 13)
                .headingPath(List.of("第三章", "3.2"))
                .blockType("PARAGRAPH");
        assertThat(vc.getMetadata())
                .containsEntry(VectorMetadataFields.PAGE_NUMBER, 12)
                .containsEntry(VectorMetadataFields.PAGE_START, 12)
                .containsEntry(VectorMetadataFields.PAGE_END, 13)
                .containsEntry(VectorMetadataFields.BLOCK_TYPE, "PARAGRAPH")
                .containsEntry(VectorMetadataFields.HEADING_PATH, List.of("第三章", "3.2"));
    }
}
