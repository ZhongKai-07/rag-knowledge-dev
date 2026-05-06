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

package com.knowledgebase.ai.ragent.rag.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceChunkSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void layoutFields_omittedWhenNull() throws Exception {
        SourceChunk chunk = SourceChunk.builder()
                .chunkId("c1").chunkIndex(0).preview("hello").score(0.9F)
                .build();  // 6 layout 字段全 null
        String json = mapper.writeValueAsString(chunk);

        assertThat(json).doesNotContain("pageNumber");
        assertThat(json).doesNotContain("headingPath");
        assertThat(json).doesNotContain("blockType");
    }

    @Test
    void layoutFields_includedWhenPresent() throws Exception {
        SourceChunk chunk = SourceChunk.builder()
                .chunkId("c1").chunkIndex(0).preview("hello").score(0.9F)
                .pageNumber(7).pageStart(7).pageEnd(8)
                .headingPath(List.of("第一章"))
                .blockType("PARAGRAPH")
                .build();
        String json = mapper.writeValueAsString(chunk);

        assertThat(json).contains("\"pageNumber\":7");
        assertThat(json).contains("\"headingPath\":[\"第一章\"]");
        assertThat(json).contains("\"blockType\":\"PARAGRAPH\"");
    }
}
