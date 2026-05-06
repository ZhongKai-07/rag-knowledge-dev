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

package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.knowledgebase.ai.ragent.knowledge.service.support.KnowledgeChunkLayoutMapper;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v4 review P1 #4：仅靠 mapper 单测无法证明"5 处 VectorChunk.builder 调用点真的调了 copyFromDO"。
 * 本测试通过验证 {@code buildVectorChunkFromDO} helper（5 处 call site 共用入口）的输出契约，
 * 间接锁定 5 处都不会丢 layout —— 任何 call site 绕开 helper 自己 builder 都会被 review 灯红。
 *
 * <p>完整 service 行为（数据库 + OS）走 manual ops smoke。
 */
class ChunkServiceReindexCallSitesMockTest {

    @Test
    void buildVectorChunkFromDO_propagatesAll9LayoutFieldsToMetadata() {
        KnowledgeChunkLayoutMapper mapper = new KnowledgeChunkLayoutMapper(new ObjectMapper());
        // KnowledgeChunkServiceImpl ctor: 11 fields after Task 14 Step 1
        // Order: chunkMapper, documentMapper, knowledgeBaseMapper, embeddingService, tokenCounterService,
        //        vectorStoreService, transactionOperations, kbReadAccess, kbManageAccess, kbMetadataReader, layoutMapper
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
                null, null, null, null, null, null, null, null, null, null, mapper);

        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1").content("hello").chunkIndex(7)
                .pageNumber(12).pageStart(12).pageEnd(13)
                .headingPath("[\"第三章\",\"3.2\"]")
                .blockType("PARAGRAPH")
                .sourceBlockIds("[\"b1\"]")
                .bboxRefs("[{\"x\":1}]")
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.95)
                .build();

        VectorChunk vc = service.buildVectorChunkFromDO(chunkDO);

        assertThat(vc.getChunkId()).isEqualTo("c1");
        assertThat(vc.getContent()).isEqualTo("hello");
        assertThat(vc.getIndex()).isEqualTo(7);
        assertThat(ChunkLayoutMetadata.pageNumber(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageStart(vc)).isEqualTo(12);
        assertThat(ChunkLayoutMetadata.pageEnd(vc)).isEqualTo(13);
        assertThat(ChunkLayoutMetadata.headingPath(vc)).containsExactly("第三章", "3.2");
        assertThat(ChunkLayoutMetadata.blockType(vc)).isEqualTo("PARAGRAPH");
        assertThat(ChunkLayoutMetadata.sourceBlockIds(vc)).containsExactly("b1");
        assertThat(ChunkLayoutMetadata.bboxRefs(vc)).isEqualTo("[{\"x\":1}]");
        assertThat(ChunkLayoutMetadata.textLayerType(vc)).isEqualTo("NATIVE_TEXT");
        assertThat(ChunkLayoutMetadata.layoutConfidence(vc)).isEqualTo(0.95);
    }
}
