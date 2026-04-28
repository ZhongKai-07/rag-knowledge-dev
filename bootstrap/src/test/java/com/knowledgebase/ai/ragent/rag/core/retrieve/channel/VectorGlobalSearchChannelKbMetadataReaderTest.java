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
import com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorGlobalSearchChannelKbMetadataReaderTest {

    @Mock
    private RetrieverService retrieverService;

    @Mock
    private KbMetadataReader kbMetadataReader;

    @Mock
    private MetadataFilterBuilder metadataFilterBuilder;

    private VectorGlobalSearchChannel channel;

    @BeforeEach
    void setUp() {
        SearchChannelProperties properties = new SearchChannelProperties();
        Executor directExecutor = Runnable::run;
        channel = new VectorGlobalSearchChannel(
                retrieverService,
                properties,
                kbMetadataReader,
                metadataFilterBuilder,
                directExecutor
        );
    }

    @Test
    void all_scope_lists_all_kbs_and_applies_per_kb_filters_to_each_collection() {
        MetadataFilter kb1Filter = new MetadataFilter(
                "security_level",
                MetadataFilter.FilterOp.LTE_OR_MISSING,
                1
        );
        MetadataFilter kb2Filter = new MetadataFilter(
                "security_level",
                MetadataFilter.FilterOp.LTE_OR_MISSING,
                2
        );
        Set<String> visibleKbIds = Set.of("kb-1", "kb-2");
        when(kbMetadataReader.listAllKbIds()).thenReturn(visibleKbIds);
        when(kbMetadataReader.getCollectionNames(visibleKbIds))
                .thenReturn(Map.of("kb-1", "collection_1", "kb-2", "collection_2"));
        when(metadataFilterBuilder.build(any(SearchContext.class), eq("kb-1")))
                .thenReturn(List.of(kb1Filter));
        when(metadataFilterBuilder.build(any(SearchContext.class), eq("kb-2")))
                .thenReturn(List.of(kb2Filter));
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        SearchChannelResult result = channel.search(buildContext(AccessScope.all()));

        assertThat(result.getChannelType()).isEqualTo(SearchChannelType.VECTOR_GLOBAL);
        assertThat(result.getChunks()).isEmpty();
        verify(kbMetadataReader).listAllKbIds();
        verify(kbMetadataReader).getCollectionNames(visibleKbIds);

        ArgumentCaptor<RetrieveRequest> requestCaptor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, times(2)).retrieve(requestCaptor.capture());
        Map<String, RetrieveRequest> byCollection = requestCaptor.getAllValues().stream()
                .collect(Collectors.toMap(RetrieveRequest::getCollectionName, request -> request));
        assertThat(byCollection.get("collection_1").getMetadataFilters()).containsExactly(kb1Filter);
        assertThat(byCollection.get("collection_2").getMetadataFilters()).containsExactly(kb2Filter);
    }

    @Test
    void ids_scope_skips_list_all_and_uses_ids_directly_for_collection_names() {
        MetadataFilter kbFilter = new MetadataFilter(
                "security_level",
                MetadataFilter.FilterOp.LTE_OR_MISSING,
                3
        );
        Set<String> ids = Set.of("kb-1");
        when(kbMetadataReader.getCollectionNames(ids)).thenReturn(Map.of("kb-1", "collection_1"));
        when(metadataFilterBuilder.build(any(SearchContext.class), eq("kb-1"))).thenReturn(List.of(kbFilter));
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        SearchChannelResult result = channel.search(buildContext(AccessScope.ids(ids)));

        assertThat(result.getChannelType()).isEqualTo(SearchChannelType.VECTOR_GLOBAL);
        assertThat(result.getChunks()).isEmpty();
        verify(kbMetadataReader, never()).listAllKbIds();
        verify(kbMetadataReader).getCollectionNames(ids);

        ArgumentCaptor<RetrieveRequest> requestCaptor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getCollectionName()).isEqualTo("collection_1");
        assertThat(requestCaptor.getValue().getMetadataFilters()).containsExactly(kbFilter);
    }

    @Test
    void all_scope_with_no_visible_kbs_skips_collection_name_lookup_and_retrieval() {
        when(kbMetadataReader.listAllKbIds()).thenReturn(Set.of());

        SearchChannelResult result = channel.search(buildContext(AccessScope.all()));

        assertThat(result.getChannelType()).isEqualTo(SearchChannelType.VECTOR_GLOBAL);
        assertThat(result.getChunks()).isEmpty();
        verify(kbMetadataReader).listAllKbIds();
        verify(kbMetadataReader, never()).getCollectionNames(any());
        verifyNoInteractions(metadataFilterBuilder);
        verifyNoInteractions(retrieverService);
    }

    @Test
    void ids_scope_with_empty_set_skips_metadata_reader_batch_lookup_and_retrieval() {
        SearchChannelResult result = channel.search(buildContext(AccessScope.ids(Set.of())));

        assertThat(result.getChannelType()).isEqualTo(SearchChannelType.VECTOR_GLOBAL);
        assertThat(result.getChunks()).isEmpty();
        verify(kbMetadataReader, never()).listAllKbIds();
        verify(kbMetadataReader, never()).getCollectionNames(any());
        verifyNoInteractions(metadataFilterBuilder);
        verifyNoInteractions(retrieverService);
    }

    private SearchContext buildContext(AccessScope scope) {
        return SearchContext.builder()
                .originalQuestion("question")
                .rewrittenQuestion("question")
                .intents(List.of(new SubQuestionIntent("question", List.of())))
                .recallTopK(5)
                .rerankTopK(3)
                .accessScope(scope)
                .kbSecurityLevels(Map.of())
                .build();
    }
}
