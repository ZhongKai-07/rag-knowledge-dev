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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingService;
import com.knowledgebase.ai.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenSearchRetrieverServiceTest {

    @Test
    void toRetrievedChunk_shouldExtractDocIdAndChunkIndex() {
        OpenSearchRetrieverService service = newService();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", "kb_xyz");
        metadata.put("security_level", 1);
        metadata.put("doc_id", "doc_abc");
        metadata.put("chunk_index", 12);

        Map<String, Object> source = new HashMap<>();
        source.put("id", "c_1");
        source.put("content", "hello world");
        source.put("metadata", metadata);

        Map<String, Object> hit = new HashMap<>();
        hit.put("_source", source);
        hit.put("_score", 0.85);

        RetrievedChunk chunk = ReflectionTestUtils.invokeMethod(service, "toRetrievedChunk", hit);

        assertThat(chunk).isNotNull();
        assertThat(chunk.getId()).isEqualTo("c_1");
        assertThat(chunk.getDocId()).isEqualTo("doc_abc");
        assertThat(chunk.getChunkIndex()).isEqualTo(12);
        assertThat(chunk.getKbId()).isEqualTo("kb_xyz");
        assertThat(chunk.getSecurityLevel()).isEqualTo(1);
    }

    @Test
    void toRetrievedChunk_shouldTolerateMissingDocIdOrChunkIndex() {
        OpenSearchRetrieverService service = newService();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", "kb_xyz");
        // 故意不放 doc_id / chunk_index — 模拟老数据

        Map<String, Object> source = new HashMap<>();
        source.put("id", "c_1");
        source.put("content", "hello");
        source.put("metadata", metadata);

        Map<String, Object> hit = new HashMap<>();
        hit.put("_source", source);
        hit.put("_score", 0.5);

        RetrievedChunk chunk = ReflectionTestUtils.invokeMethod(service, "toRetrievedChunk", hit);

        assertThat(chunk.getDocId()).isNull();
        assertThat(chunk.getChunkIndex()).isNull();
        assertThat(chunk.getKbId()).isEqualTo("kb_xyz");
    }

    @Test
    void toRetrievedChunk_shouldTolerateBlankDocId() {
        OpenSearchRetrieverService service = newService();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", "   "); // 空白字符串当作 null

        Map<String, Object> source = new HashMap<>();
        source.put("id", "c_1");
        source.put("content", "");
        source.put("metadata", metadata);

        Map<String, Object> hit = new HashMap<>();
        hit.put("_source", source);
        hit.put("_score", 0.5);

        RetrievedChunk chunk = ReflectionTestUtils.invokeMethod(service, "toRetrievedChunk", hit);

        assertThat(chunk.getDocId()).isNull();
    }

    /**
     * 按 @RequiredArgsConstructor 生成的构造器顺序构造 service 实例。
     * toRetrievedChunk 仅依赖入参 hit，不访问任何实例字段，所以全部 mock 即可。
     * 字段声明顺序（OpenSearchRetrieverService.java 顶部）:
     *   1. OpenSearchClient client
     *   2. EmbeddingService embeddingService
     *   3. RAGDefaultProperties ragDefaultProperties
     *   4. ObjectMapper objectMapper
     */
    private OpenSearchRetrieverService newService() {
        return new OpenSearchRetrieverService(
                mock(OpenSearchClient.class),
                mock(EmbeddingService.class),
                mock(RAGDefaultProperties.class),
                mock(ObjectMapper.class));
    }
}
