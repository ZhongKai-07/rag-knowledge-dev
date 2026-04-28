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

package com.knowledgebase.ai.ragent.rag.core.retrieve.filter;

import com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.assertj.core.api.InstanceOfAssertFactories;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR5 Layer 3 — 通道级 per-KB filter 对齐契约共享断言。
 *
 * <p>spec QSI-4 不变量：通道每次调用 {@code retrieverService.retrieve(req)} 时，
 * {@code req.getCollectionName()} 与 {@code req.getMetadataFilters()} 中
 * {@code kb_id IN [kbId]} filter 的 value（List 单元素）必须配对来自同一 KB。
 *
 * <p>跨包测试复用（{@code retrieve/} / {@code retrieve/channel/} /
 * {@code retrieve/channel/strategy/}），故必须 public class。
 */
public final class FilterAlignmentAssertions {

    private FilterAlignmentAssertions() {
    }

    /**
     * 断言 {@link RetrieveRequest} 的 collectionName 与其 metadata 中
     * {@code kb_id IN [kbId]} filter 的 value 单元素一致：
     * 即 {@code collectionToKbId.get(req.collectionName) == kbIdFilter.value[0]}。
     *
     * @param req               通道传给 retriever 的请求
     * @param collectionToKbId  测试预设的 collectionName → 期望 kbId 映射
     */
    public static void assertCollectionAlignsKbIdFilter(RetrieveRequest req,
                                                        Map<String, String> collectionToKbId) {
        String collection = req.getCollectionName();
        assertThat(collection)
                .as("RetrieveRequest.collectionName must not be null")
                .isNotNull();

        assertThat(req.getMetadataFilters())
                .as("RetrieveRequest must carry metadataFilters")
                .isNotNull();

        MetadataFilter kbIdFilter = req.getMetadataFilters().stream()
                .filter(f -> VectorMetadataFields.KB_ID.equals(f.field())
                        && f.op() == MetadataFilter.FilterOp.IN)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing kb_id IN filter for collection=" + collection
                                + ", filters=" + req.getMetadataFilters()));

        List<?> values = assertThat(kbIdFilter.value())
                .as("kb_id filter value must be a singleton List<kbId> (per-KB call)")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(1)
                .actual();

        String expectedKbId = collectionToKbId.get(collection);
        assertThat(expectedKbId)
                .as("collection " + collection + " has no expected kbId mapping in test setup")
                .isNotNull();
        assertThat(values.get(0))
                .as("collection " + collection + " ↔ kb_id filter must align to same KB")
                .isEqualTo(expectedKbId);
    }
}
