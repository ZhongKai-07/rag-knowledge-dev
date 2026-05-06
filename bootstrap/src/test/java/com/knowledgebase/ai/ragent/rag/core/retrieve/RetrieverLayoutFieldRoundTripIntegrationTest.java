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

package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索读路径：OS metadata Map → RetrievedChunk 6 字段。
 * 验证 OpenSearchRetrieverService.toRetrievedChunk 能正确把 OS _source.metadata
 * 反序列化为 RetrievedChunk 6 字段（特别 List<String> 不被错误处理为 String）。
 */
class RetrieverLayoutFieldRoundTripIntegrationTest {

    @Test
    void toRetrievedChunkExtractsLayout_listFieldsAsList() {
        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("kb_id", "kb1");
        metaMap.put("security_level", 0);
        metaMap.put("doc_id", "d1");
        metaMap.put("chunk_index", 0);
        metaMap.put("page_number", 7);
        metaMap.put("page_start", 7);
        metaMap.put("page_end", 8);
        metaMap.put("heading_path", List.of("第一章", "1.1"));
        metaMap.put("block_type", "PARAGRAPH");
        metaMap.put("source_block_ids", List.of("b1", "b2"));

        RetrievedChunk chunk = OpenSearchRetrieverService.toRetrievedChunkForTest(metaMap, "id1", "text", 0.95F);

        assertThat(chunk.getPageNumber()).isEqualTo(7);
        assertThat(chunk.getPageStart()).isEqualTo(7);
        assertThat(chunk.getPageEnd()).isEqualTo(8);
        assertThat(chunk.getHeadingPath()).containsExactly("第一章", "1.1");
        assertThat(chunk.getBlockType()).isEqualTo("PARAGRAPH");
        assertThat(chunk.getSourceBlockIds()).containsExactly("b1", "b2");
    }
}
