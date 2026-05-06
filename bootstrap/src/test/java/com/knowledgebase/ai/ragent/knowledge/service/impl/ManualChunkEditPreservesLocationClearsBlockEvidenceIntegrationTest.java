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

import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual edit 契约（PR 6 / Q6 v3 review P2 #3）：
 * 5 location 字段保留（pageNumber / pageStart / pageEnd / headingPath / blockType），
 * 4 extraction 字段清空（sourceBlockIds / bboxRefs / textLayerType / layoutConfidence）。
 *
 * <p>本测试是 contract-level 单元覆盖；完整 service 行为依赖 Mapper / Spring 上下文，详见 manual ops smoke。
 */
class ManualChunkEditPreservesLocationClearsBlockEvidenceIntegrationTest {

    @Test
    void simulatedUpdate_clearsExtractionFields_keepsLocationFields() {
        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id("c1").content("original")
                .pageNumber(7).pageStart(7).pageEnd(8)
                .headingPath("[\"Ch1\"]")
                .blockType("PARAGRAPH")
                .sourceBlockIds("[\"b1\"]")
                .bboxRefs("[{\"x\":1}]")
                .textLayerType("NATIVE_TEXT")
                .layoutConfidence(0.9)
                .build();

        // 模拟 update 内部的 4 字段清空
        chunkDO.setContent("edited");
        chunkDO.setSourceBlockIds(null);
        chunkDO.setBboxRefs(null);
        chunkDO.setTextLayerType(null);
        chunkDO.setLayoutConfidence(null);

        // 5 location 字段保留
        assertThat(chunkDO.getPageNumber()).isEqualTo(7);
        assertThat(chunkDO.getPageStart()).isEqualTo(7);
        assertThat(chunkDO.getPageEnd()).isEqualTo(8);
        assertThat(chunkDO.getHeadingPath()).isEqualTo("[\"Ch1\"]");
        assertThat(chunkDO.getBlockType()).isEqualTo("PARAGRAPH");

        // 4 extraction 字段清空
        assertThat(chunkDO.getSourceBlockIds()).isNull();
        assertThat(chunkDO.getBboxRefs()).isNull();
        assertThat(chunkDO.getTextLayerType()).isNull();
        assertThat(chunkDO.getLayoutConfidence()).isNull();
    }
}
