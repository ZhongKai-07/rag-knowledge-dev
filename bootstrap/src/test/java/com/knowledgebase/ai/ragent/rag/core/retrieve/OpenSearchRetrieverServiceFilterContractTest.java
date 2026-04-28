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
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingService;
import com.knowledgebase.ai.ragent.rag.config.RAGDefaultProperties;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * PR5 Layer 2 — OpenSearchRetrieverService 的 filter 契约守卫 + DSL shape。
 *
 * <p>fail-fast 用例通过 {@code retrieveByVector} 入口触发 doSearch（避开 embedding mock 链）；
 * DSL shape 用例直接反射调 {@code buildKnnOnlyQuery} / {@code buildHybridQuery}，
 * 不走 HTTP client mock 链——这两个 private 方法仅依赖入参，无任何实例字段访问。
 */
class OpenSearchRetrieverServiceFilterContractTest {

    private static final String COLLECTION = "kb-1-collection";

    @Test
    void emptyFilters_failFast() {
        OpenSearchRetrieverService service = newService();

        RetrieveRequest req = RetrieveRequest.builder()
                .query("q")
                .topK(5)
                .collectionName(COLLECTION)
                .metadataFilters(List.of())
                .build();

        float[] vector = {0.1f, 0.2f};
        assertThatThrownBy(() -> service.retrieveByVector(vector, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filter contract violated")
                .hasMessageContaining("hasKbIdFilter=false")
                .hasMessageContaining("hasSecurityLevelFilter=false");
    }

    @Test
    void nullFilters_failFast() {
        OpenSearchRetrieverService service = newService();

        RetrieveRequest req = RetrieveRequest.builder()
                .query("q")
                .topK(5)
                .collectionName(COLLECTION)
                .metadataFilters(null)
                .build();

        float[] vector = {0.1f, 0.2f};
        assertThatThrownBy(() -> service.retrieveByVector(vector, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filter contract violated");
    }

    @Test
    void onlyKbId_failFast() {
        OpenSearchRetrieverService service = newService();

        RetrieveRequest req = RetrieveRequest.builder()
                .query("q")
                .topK(5)
                .collectionName(COLLECTION)
                .metadataFilters(List.of(kbIdFilter("kb-1")))
                .build();

        float[] vector = {0.1f, 0.2f};
        assertThatThrownBy(() -> service.retrieveByVector(vector, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hasKbIdFilter=true")
                .hasMessageContaining("hasSecurityLevelFilter=false");
    }

    @Test
    void onlySecurityLevel_failFast() {
        OpenSearchRetrieverService service = newService();

        RetrieveRequest req = RetrieveRequest.builder()
                .query("q")
                .topK(5)
                .collectionName(COLLECTION)
                .metadataFilters(List.of(securityLevelFilter(2)))
                .build();

        float[] vector = {0.1f, 0.2f};
        assertThatThrownBy(() -> service.retrieveByVector(vector, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hasKbIdFilter=false")
                .hasMessageContaining("hasSecurityLevelFilter=true");
    }

    @Test
    void wrongOpForKbId_failFast() {
        // kb_id 必须用 IN，不能用 EQ — 守卫要求 op=IN 才认作合规
        OpenSearchRetrieverService service = newService();

        RetrieveRequest req = RetrieveRequest.builder()
                .query("q")
                .topK(5)
                .collectionName(COLLECTION)
                .metadataFilters(List.of(
                        new MetadataFilter(VectorMetadataFields.KB_ID,
                                MetadataFilter.FilterOp.EQ, "kb-1"),
                        securityLevelFilter(2)))
                .build();

        float[] vector = {0.1f, 0.2f};
        assertThatThrownBy(() -> service.retrieveByVector(vector, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hasKbIdFilter=false");
    }

    @Test
    void buildKnnOnlyQuery_dslContainsTermsAndRange() {
        OpenSearchRetrieverService service = newService();
        float[] vector = {0.1f, 0.2f, 0.3f};
        List<MetadataFilter> filters = List.of(kbIdFilter("kb-1"), securityLevelFilter(2));

        String dsl = ReflectionTestUtils.invokeMethod(
                service, "buildKnnOnlyQuery", vector, 5, filters);

        assertThat(dsl).isNotNull();
        assertThat(dsl).contains("\"terms\"");
        assertThat(dsl).contains("metadata.kb_id");
        assertThat(dsl).contains("\"range\"");
        assertThat(dsl).contains("metadata.security_level");
    }

    @Test
    void buildHybridQuery_dslContainsTermsAndRange() {
        OpenSearchRetrieverService service = newService();
        float[] vector = {0.1f, 0.2f, 0.3f};
        List<MetadataFilter> filters = List.of(kbIdFilter("kb-1"), securityLevelFilter(2));

        String dsl = ReflectionTestUtils.invokeMethod(
                service, "buildHybridQuery", "test query", vector, 5, filters);

        assertThat(dsl).isNotNull();
        assertThat(dsl).contains("\"terms\"");
        assertThat(dsl).contains("metadata.kb_id");
        assertThat(dsl).contains("\"range\"");
        assertThat(dsl).contains("metadata.security_level");
    }

    @Test
    void buildKnnOnlyQuery_noOpRangeWithMaxValue() {
        // AccessScope.All 路径: builder 输出 Integer.MAX_VALUE，DSL 中应该出现 2147483647
        OpenSearchRetrieverService service = newService();
        float[] vector = {0.1f, 0.2f, 0.3f};
        List<MetadataFilter> filters = List.of(
                kbIdFilter("kb-1"),
                securityLevelFilter(Integer.MAX_VALUE));

        String dsl = ReflectionTestUtils.invokeMethod(
                service, "buildKnnOnlyQuery", vector, 5, filters);

        assertThat(dsl).contains("2147483647");
        assertThat(dsl).contains("metadata.security_level");
    }

    private static MetadataFilter kbIdFilter(String kbId) {
        return new MetadataFilter(
                VectorMetadataFields.KB_ID,
                MetadataFilter.FilterOp.IN,
                List.of(kbId));
    }

    private static MetadataFilter securityLevelFilter(int level) {
        return new MetadataFilter(
                VectorMetadataFields.SECURITY_LEVEL,
                MetadataFilter.FilterOp.LTE_OR_MISSING,
                level);
    }

    /**
     * 按 OpenSearchRetrieverService 字段声明顺序构造（@RequiredArgsConstructor 生成的 ctor）：
     *   1. OpenSearchClient client
     *   2. EmbeddingService embeddingService
     *   3. RAGDefaultProperties ragDefaultProperties
     *   4. ObjectMapper objectMapper
     *
     * doSearch 守卫不依赖任何实例字段，buildKnnOnlyQuery / buildHybridQuery 同样仅
     * 依赖入参——全部 mock 即可。
     */
    private static OpenSearchRetrieverService newService() {
        return new OpenSearchRetrieverService(
                mock(OpenSearchClient.class),
                mock(EmbeddingService.class),
                mock(RAGDefaultProperties.class),
                mock(ObjectMapper.class));
    }
}
