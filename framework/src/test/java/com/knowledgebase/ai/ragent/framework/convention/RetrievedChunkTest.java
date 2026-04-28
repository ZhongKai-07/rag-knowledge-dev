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

package com.knowledgebase.ai.ragent.framework.convention;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievedChunkTest {

    @Test
    void builder_shouldCarryDocIdAndChunkIndex() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("c_1")
                .text("hello")
                .score(0.9f)
                .docId("doc_abc")
                .chunkIndex(3)
                .build();

        assertThat(chunk.getDocId()).isEqualTo("doc_abc");
        assertThat(chunk.getChunkIndex()).isEqualTo(3);
    }

    @Test
    void newFields_shouldBeNullableOptional() {
        // 沿用 kbId/securityLevel 的 nullable 约定, 未设置时必须为 null 且不抛
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("c_1")
                .text("hello")
                .score(0.9f)
                .build();

        assertThat(chunk.getDocId()).isNull();
        assertThat(chunk.getChunkIndex()).isNull();
        assertThat(chunk.getKbId()).isNull();
        assertThat(chunk.getSecurityLevel()).isNull();
    }

    @Test
    void toBuilder_shouldPreserveNewFields() {
        RetrievedChunk original = RetrievedChunk.builder()
                .id("c_1")
                .docId("doc_abc")
                .chunkIndex(3)
                .build();

        RetrievedChunk copy = original.toBuilder().score(0.5f).build();

        assertThat(copy.getDocId()).isEqualTo("doc_abc");
        assertThat(copy.getChunkIndex()).isEqualTo(3);
        assertThat(copy.getScore()).isEqualTo(0.5f);
    }

    @Test
    void equality_shouldBePinnedToIdOnly() {
        // 固定 equality 语义到 id, 避免新增字段静默改变 RAGChatServiceImpl.distinct() 行为.
        // 域语义: chunk 由 vector store PK 唯一标识; 其他字段是表现数据, 不参与身份比较.
        RetrievedChunk a = RetrievedChunk.builder()
                .id("c_1").text("hello").score(0.9f)
                .docId("doc_abc").chunkIndex(3).build();
        RetrievedChunk b = RetrievedChunk.builder()
                .id("c_1").text("different").score(0.1f)
                .docId("doc_xyz").chunkIndex(99).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        RetrievedChunk c = RetrievedChunk.builder()
                .id("c_2").text("hello").score(0.9f)
                .docId("doc_abc").chunkIndex(3).build();
        assertThat(a).isNotEqualTo(c);
    }
}
