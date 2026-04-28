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
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR5 Layer 1 — DefaultMetadataFilterBuilder 必须为非空 kbId 永远输出
 * kb_id (FilterOp.IN [kbId]) 与 security_level (FilterOp.LTE_OR_MISSING level)
 * 两条 filter，让 OpenSearch DSL 始终包含 terms(metadata.kb_id) + range(metadata.security_level)
 * 成为可静态断言的 query shape invariant。
 */
class DefaultMetadataFilterBuilderTest {

    private final DefaultMetadataFilterBuilder builder = new DefaultMetadataFilterBuilder();

    private static SearchContext ctxWith(Map<String, Integer> levels) {
        SearchContext ctx = new SearchContext();
        ctx.setKbSecurityLevels(levels);
        return ctx;
    }

    @Test
    void normalUser_outputsKbIdAndSecurityLevelFilters() {
        SearchContext ctx = ctxWith(Map.of("kb-1", 1, "kb-2", 2));

        List<MetadataFilter> filters = builder.build(ctx, "kb-1");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-1");
        assertSecurityLevelFilter(filters, 1);
    }

    @Test
    void missingEntry_fallsBackToMaxValueAsNoOpRange() {
        SearchContext ctx = ctxWith(Map.of("kb-1", 1));

        List<MetadataFilter> filters = builder.build(ctx, "kb-2");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-2");
        assertSecurityLevelFilter(filters, Integer.MAX_VALUE);
    }

    @Test
    void nullSecurityLevelMap_fallsBackToMaxValue() {
        SearchContext ctx = ctxWith(null);

        List<MetadataFilter> filters = builder.build(ctx, "kb-1");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-1");
        assertSecurityLevelFilter(filters, Integer.MAX_VALUE);
    }

    @Test
    void levelZero_isEmittedAsZeroNotMaxValue() {
        // 防御回归：level=0 不能被 (Integer == null) 检查误退化为 MAX_VALUE
        SearchContext ctx = ctxWith(Map.of("kb-1", 0));

        List<MetadataFilter> filters = builder.build(ctx, "kb-1");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-1");
        assertSecurityLevelFilter(filters, 0);
    }

    @Test
    void nullKbId_returnsEmpty() {
        // kbId 缺失由 OpenSearchRetrieverService.enforceFilterContract 接住 fail-fast
        SearchContext ctx = ctxWith(Map.of("kb-1", 1));

        List<MetadataFilter> filters = builder.build(ctx, null);

        assertThat(filters).isEmpty();
    }

    @Test
    void emptySecurityLevelMap_fallsBackToMaxValue() {
        SearchContext ctx = ctxWith(new HashMap<>());

        List<MetadataFilter> filters = builder.build(ctx, "kb-1");

        assertThat(filters).hasSize(2);
        assertKbIdFilter(filters, "kb-1");
        assertSecurityLevelFilter(filters, Integer.MAX_VALUE);
    }

    private static void assertKbIdFilter(List<MetadataFilter> filters, String expectedKbId) {
        MetadataFilter f = filters.stream()
                .filter(x -> VectorMetadataFields.KB_ID.equals(x.field()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing kb_id filter; got: " + filters));
        assertThat(f.op()).isEqualTo(MetadataFilter.FilterOp.IN);
        assertThat(f.value()).isEqualTo(List.of(expectedKbId));
    }

    private static void assertSecurityLevelFilter(List<MetadataFilter> filters, int expectedLevel) {
        MetadataFilter f = filters.stream()
                .filter(x -> VectorMetadataFields.SECURITY_LEVEL.equals(x.field()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing security_level filter; got: " + filters));
        assertThat(f.op()).isEqualTo(MetadataFilter.FilterOp.LTE_OR_MISSING);
        assertThat(f.value()).isEqualTo(expectedLevel);
    }
}
