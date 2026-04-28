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

import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Sql(
        statements = {
                "DELETE FROM t_knowledge_base WHERE id IN "
                        + "('kb-fixture-1', 'kb-fixture-blank', 'kb-fixture-deleted')",
                "INSERT INTO t_knowledge_base(id, name, collection_name, embedding_model, created_by, dept_id) "
                        + "VALUES ('kb-fixture-1', 'fixture-kb', 'kb_fixture_1', 'text-embedding-v3', 'sys', '1')",
                "INSERT INTO t_knowledge_base(id, name, collection_name, embedding_model, created_by, dept_id) "
                        + "VALUES ('kb-fixture-blank', 'blank-kb', '   ', 'text-embedding-v3', 'sys', '1')",
                "INSERT INTO t_knowledge_base(id, name, collection_name, embedding_model, created_by, dept_id, deleted) "
                        + "VALUES ('kb-fixture-deleted', 'deleted-kb', 'kb_fixture_deleted', "
                        + "'text-embedding-v3', 'sys', '1', 1)"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(
        statements = "DELETE FROM t_knowledge_base WHERE id IN "
                + "('kb-fixture-1', 'kb-fixture-blank', 'kb-fixture-deleted')",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class KbMetadataReaderImplTest {

    @Autowired KbMetadataReader reader;

    @Test
    void getCollectionName_returns_null_when_kbId_null() {
        assertThat(reader.getCollectionName(null)).isNull();
    }

    @Test
    void getCollectionName_returns_null_when_kb_missing_or_deleted() {
        assertThat(reader.getCollectionName("non-existent-id")).isNull();
        assertThat(reader.getCollectionName("kb-fixture-deleted")).isNull();
    }

    @Test
    void getCollectionName_returns_collection_for_existing_kb() {
        assertThat(reader.getCollectionName("kb-fixture-1")).isEqualTo("kb_fixture_1");
    }

    @Test
    void getCollectionNames_empty_input_returns_empty_map() {
        assertThat(reader.getCollectionNames(List.of())).isEmpty();
        assertThat(reader.getCollectionNames(null)).isEmpty();
    }

    @Test
    void getCollectionNames_mixed_batch_filters_missing_deleted_and_blank_collections() {
        Map<String, String> result = reader.getCollectionNames(List.of(
                "kb-fixture-1",
                "kb-fixture-blank",
                "kb-fixture-deleted",
                "non-existent-id"));

        assertThat(result).containsOnly(Map.entry("kb-fixture-1", "kb_fixture_1"));
        assertThatThrownBy(() -> result.put("another-kb", "another_collection"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
