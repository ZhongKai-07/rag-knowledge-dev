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

package com.knowledgebase.ai.ragent.rag.core.retrieve.channel;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.rag.config.SearchChannelProperties;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.DefaultMetadataFilterBuilder;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.FilterAlignmentAssertions;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR5 Layer 3 — {@link VectorGlobalSearchChannel} 每次 fan-out 迭代的
 * {@code collectionName ↔ kb_id filter} 必须严格对齐当前迭代 KB。
 *
 * <p>锁住 spec QSI-4：channel 在 {@code retrieveFromAllCollections} 里把
 * {@code (kb.collectionName, builder.build(ctx, kb.kbId))} 配对成一条
 * {@code CollectionTask}；retriever 收到 N 个请求时，每条请求的 collection
 * 与其 kb_id filter 必须来自同一对 (kbId, collectionName)。
 *
 * <p>关键设计：使用真实 {@link DefaultMetadataFilterBuilder}（不 mock builder）。
 * Direct executor 让 fan-out 顺序确定可断言。
 */
@ExtendWith(MockitoExtension.class)
class VectorGlobalChannelFilterAlignmentTest {

    @Mock
    private RetrieverService retrieverService;

    @Mock
    private KbMetadataReader kbMetadataReader;

    private VectorGlobalSearchChannel channel;

    @BeforeEach
    void setUp() {
        SearchChannelProperties properties = new SearchChannelProperties();
        Executor directExecutor = Runnable::run;
        channel = new VectorGlobalSearchChannel(
                retrieverService,
                properties,
                kbMetadataReader,
                new DefaultMetadataFilterBuilder(),
                directExecutor
        );
    }

    @Test
    void allScope_eachFanOutRequest_collectionAndKbIdFilterAlignToSameKb() {
        Set<String> visibleKbIds = Set.of("kb-1", "kb-2");
        Map<String, String> kbToCollection = Map.of(
                "kb-1", "collection_1",
                "kb-2", "collection_2");
        when(kbMetadataReader.listAllKbIds()).thenReturn(visibleKbIds);
        when(kbMetadataReader.getCollectionNames(visibleKbIds)).thenReturn(kbToCollection);
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        channel.search(buildContext(AccessScope.all(), Map.of("kb-1", 1, "kb-2", 2)));

        Map<String, String> collectionToKbId = Map.of(
                "collection_1", "kb-1",
                "collection_2", "kb-2");
        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, times(2)).retrieve(captor.capture());
        for (RetrieveRequest req : captor.getAllValues()) {
            FilterAlignmentAssertions.assertCollectionAlignsKbIdFilter(req, collectionToKbId);
        }
    }

    @Test
    void idsScope_singleKbFanOut_collectionAndKbIdFilterAlign() {
        Set<String> ids = Set.of("kb-only");
        when(kbMetadataReader.getCollectionNames(ids))
                .thenReturn(Map.of("kb-only", "collection_only"));
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        channel.search(buildContext(AccessScope.ids(ids), Map.of("kb-only", 3)));

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(captor.capture());
        FilterAlignmentAssertions.assertCollectionAlignsKbIdFilter(
                captor.getValue(),
                Map.of("collection_only", "kb-only"));
    }

    private SearchContext buildContext(AccessScope scope, Map<String, Integer> securityLevels) {
        return SearchContext.builder()
                .originalQuestion("question")
                .rewrittenQuestion("question")
                .intents(List.of(new SubQuestionIntent("question", List.of())))
                .recallTopK(5)
                .rerankTopK(3)
                .accessScope(scope)
                .kbSecurityLevels(securityLevels)
                .build();
    }
}
