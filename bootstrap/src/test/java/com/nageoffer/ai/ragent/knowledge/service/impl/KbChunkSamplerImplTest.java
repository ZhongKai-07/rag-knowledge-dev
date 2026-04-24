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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort.ChunkSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KbChunkSamplerImplTest {

    @Autowired KbChunkSamplerPort port;
    @Autowired JdbcTemplate jdbc;

    private static final String KB_ID = "eval-sampler-kb";

    @BeforeEach
    void seedFixture() {
        jdbc.update("INSERT INTO t_knowledge_base(id, name, collection_name, embedding_model, created_by, dept_id) "
                + "VALUES (?, 'sampler-kb', 'sampler_kb', 'text-embedding-v3', 'sys', '1')", KB_ID);
        jdbc.update("INSERT INTO t_knowledge_document(id, kb_id, doc_name, file_url, file_type, status, enabled, created_by) "
                + "VALUES ('d1', ?, 'Doc A', 'url-a', 'pdf', 'success', 1, 'sys')", KB_ID);
        jdbc.update("INSERT INTO t_knowledge_document(id, kb_id, doc_name, file_url, file_type, status, enabled, created_by) "
                + "VALUES ('d2', ?, 'Doc B', 'url-b', 'pdf', 'success', 1, 'sys')", KB_ID);
        jdbc.update("INSERT INTO t_knowledge_document(id, kb_id, doc_name, file_url, file_type, status, enabled, created_by) "
                + "VALUES ('d3', ?, 'Doc C pending', 'url-c', 'pdf', 'pending', 1, 'sys')", KB_ID);
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                    + "VALUES (?, ?, 'd1', ?, ?, 1, 'sys')", "d1-c" + i, KB_ID, i, "doc1 chunk " + i);
            jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                    + "VALUES (?, ?, 'd2', ?, ?, 1, 'sys')", "d2-c" + i, KB_ID, i, "doc2 chunk " + i);
            jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                    + "VALUES (?, ?, 'd3', ?, ?, 1, 'sys')", "d3-c" + i, KB_ID, i, "should-not-appear " + i);
        }
        jdbc.update("INSERT INTO t_knowledge_chunk(id, kb_id, doc_id, chunk_index, content, enabled, created_by) "
                + "VALUES ('d1-disabled', ?, 'd1', 99, 'disabled chunk', 0, 'sys')", KB_ID);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_knowledge_chunk WHERE kb_id=?", KB_ID);
        jdbc.update("DELETE FROM t_knowledge_document WHERE kb_id=?", KB_ID);
        jdbc.update("DELETE FROM t_knowledge_base WHERE id=?", KB_ID);
    }

    @Test
    void sampleForSynthesis_respects_count_and_per_doc_cap() {
        List<ChunkSample> samples = port.sampleForSynthesis(KB_ID, 6, 3);

        assertThat(samples).hasSize(6);

        Map<String, Long> perDoc = samples.stream()
                .collect(Collectors.groupingBy(ChunkSample::docId, Collectors.counting()));
        perDoc.values().forEach(c -> assertThat(c).isLessThanOrEqualTo(3L));

        assertThat(samples).extracting(ChunkSample::docId).doesNotContain("d3");
        assertThat(samples).extracting(ChunkSample::chunkId).doesNotContain("d1-disabled");

        samples.forEach(s -> {
            assertThat(s.chunkText()).isNotBlank();
            assertThat(s.docName()).isIn("Doc A", "Doc B");
        });
    }

    @Test
    void sampleForSynthesis_returns_fewer_when_pool_too_small() {
        List<ChunkSample> samples = port.sampleForSynthesis(KB_ID, 100, 3);
        assertThat(samples).hasSize(6);
    }
}
