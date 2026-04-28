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

package com.knowledgebase.ai.ragent.rag.core.retrieve.channel.strategy;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
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
 * PR5 Layer 3 — {@link IntentParallelRetriever} per-intent 循环的
 * {@code collectionName ↔ kb_id filter} 必须对齐每个 {@link IntentNode} 自身
 * 的 (kbId, collectionName) 对。
 *
 * <p>注意：与 {@link com.knowledgebase.ai.ragent.rag.core.retrieve.channel.VectorGlobalSearchChannel}
 * 不同，{@code IntentParallelRetriever} 的 collectionName **直接来自
 * {@code IntentNode.getCollectionName()}**（不走 {@code KbMetadataReader}）；
 * builder 的入参 kbId 来自 {@code IntentNode.getKbId()}。这是 PR5 之后两条
 * 通道唯一的 collection 来源差异，但对齐契约本身相同。
 *
 * <p>关键设计：使用真实 {@link DefaultMetadataFilterBuilder}（不 mock builder）。
 * Direct executor 让 fan-out 顺序确定可断言。
 */
@ExtendWith(MockitoExtension.class)
class IntentParallelRetrieverFilterAlignmentTest {

    @Mock
    private RetrieverService retrieverService;

    private IntentParallelRetriever retriever;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        retriever = new IntentParallelRetriever(
                retrieverService,
                new DefaultMetadataFilterBuilder(),
                directExecutor);
    }

    @Test
    void multiIntent_eachRequest_collectionFromIntentNodeAlignsKbIdFilter() {
        NodeScore score1 = nodeScore("intent-1", "kb-1", "collection_1");
        NodeScore score2 = nodeScore("intent-2", "kb-2", "collection_2");
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        SearchContext context = buildContext(Map.of("kb-1", 1, "kb-2", 2));
        retriever.executeParallelRetrieval("question", List.of(score1, score2), 5, context);

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
    void singleIntent_collectionFromIntentNodeAlignsKbIdFilter() {
        NodeScore score = nodeScore("intent-only", "kb-only", "collection_only");
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        SearchContext context = buildContext(Map.of("kb-only", 3));
        retriever.executeParallelRetrieval("question", List.of(score), 5, context);

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(captor.capture());
        FilterAlignmentAssertions.assertCollectionAlignsKbIdFilter(
                captor.getValue(),
                Map.of("collection_only", "kb-only"));
    }

    private static NodeScore nodeScore(String intentId, String kbId, String collectionName) {
        IntentNode node = IntentNode.builder()
                .id(intentId)
                .kbId(kbId)
                .name(intentId)
                .collectionName(collectionName)
                .build();
        return NodeScore.builder().node(node).score(0.9).build();
    }

    private static SearchContext buildContext(Map<String, Integer> securityLevels) {
        // IntentParallelRetriever does not consume accessScope; we set it from the same
        // keySet only to satisfy SearchContext invariants without inventing extra inputs.
        return SearchContext.builder()
                .originalQuestion("question")
                .rewrittenQuestion("question")
                .intents(List.of(new SubQuestionIntent("question", List.of())))
                .recallTopK(5)
                .rerankTopK(3)
                .accessScope(AccessScope.ids(Set.copyOf(securityLevels.keySet())))
                .kbSecurityLevels(securityLevels)
                .build();
    }
}
