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

package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkLayoutMapperTest {

    private final KnowledgeChunkLayoutMapper mapper = new KnowledgeChunkLayoutMapper(new ObjectMapper());

    @Test
    void copyFromDO_fullDoFillsAll9Keys() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1")
                .pageNumber(12).pageStart(12).pageEnd(13)
                .headingPath("[\"第三章\",\"3.2\"]")
                .blockType("PARAGRAPH")
                .sourceBlockIds("[\"b1\",\"b2\"]")
                .bboxRefs("[]")  // helper writer 跳过 "[]"
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.95)
                .build();
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();

        mapper.copyFromDO(chunkDO, vc);

        assertThat(ChunkLayoutMetadata.pageNumber(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(13);
        assertThat(ChunkLayoutMetadata.headingPath(vc)).containsExactly("第三章", "3.2");
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo("PARAGRAPH");
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).containsExactly("b1", "b2");
        assertThat(ChunkLayoutMetadata.textLayerType(vc)).isEqualTo("NATIVE_TEXT");
        assertThat(ChunkLayoutMetadata.layoutConfidence(vc)).isEqualTo(0.95);
        // bbox_refs="[]" 被 writer 主动跳过
        assertThat(vc.getMetadata()).doesNotContainKey(VectorMetadataFields.BBOX_REFS);
    }

    @Test
    void copyFromDO_allNullDo_writesNoKeys() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder().id("c1").build();  // 全 null
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();

        mapper.copyFromDO(chunkDO, vc);

        assertThat(vc.getMetadata()).doesNotContainKeys(
                VectorMetadataFields.PAGE_NUMBER, VectorMetadataFields.PAGE_START,
                VectorMetadataFields.PAGE_END, VectorMetadataFields.HEADING_PATH,
                VectorMetadataFields.BLOCK_TYPE, VectorMetadataFields.SOURCE_BLOCK_IDS,
                VectorMetadataFields.BBOX_REFS, VectorMetadataFields.TEXT_LAYER_TYPE,
                VectorMetadataFields.LAYOUT_CONFIDENCE);
    }

    @Test
    void copyFromDO_invalidJsonHeadingPath_logsWarnNotThrow() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1").headingPath("{not valid json").build();
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();

        mapper.copyFromDO(chunkDO, vc);

        assertThat(vc.getMetadata()).doesNotContainKey(VectorMetadataFields.HEADING_PATH);
    }

    @Test
    void copyToCreateRequest_metadataFullToCreateRequest9Fields() {
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(7).pageRange(7, 8)
                .headingPath(List.of("第一章", "1.1"))
                .blockType("PARAGRAPH")
                .sourceBlockIds(List.of("b1"))
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.88);
        KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();

        mapper.copyToCreateRequest(vc, req);

        assertThat(req.getPageNumber()).isEqualTo(7);
        assertThat(req.getPageStart()).isEqualTo(7);
        assertThat(req.getPageEnd()).isEqualTo(8);
        assertThat(req.getHeadingPath()).isEqualTo("[\"第一章\",\"1.1\"]");
        assertThat(req.getBlockType()).isEqualTo("PARAGRAPH");
        assertThat(req.getSourceBlockIds()).isEqualTo("[\"b1\"]");
        assertThat(req.getTextLayerType()).isEqualTo("NATIVE_TEXT");
        assertThat(req.getLayoutConfidence()).isEqualTo(0.88);
    }

    @Test
    void copyToCreateRequest_emptyMetadataAllNull() {
        VectorChunk vc = VectorChunk.builder().metadata(new HashMap<>()).build();
        KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();

        mapper.copyToCreateRequest(vc, req);

        assertThat(req.getPageNumber()).isNull();
        assertThat(req.getHeadingPath()).isNull();
        assertThat(req.getSourceBlockIds()).isNull();
        assertThat(req.getBlockType()).isNull();
    }
}
