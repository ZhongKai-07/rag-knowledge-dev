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

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine.RetrievalPlan;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.DefaultMetadataFilterBuilder;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.FilterAlignmentAssertions;
import com.knowledgebase.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR5 Layer 3 — {@link MultiChannelRetrievalEngine} 单 KB 路径的
 * {@code req.collectionName ↔ req.metadataFilters[kb_id]} 对齐契约。
 *
 * <p>锁住 spec QSI-4：当 {@code scope.isSingleKb()} 时，engine 必须用
 * {@code scope.targetKbId()} 同时驱动 {@code KbMetadataReader.getCollectionName}
 * 与 {@code MetadataFilterBuilder.build}，使 retriever 收到的请求里
 * collection 与 kb_id filter 来自同一 KB。
 *
 * <p>关键设计：使用真实 {@link DefaultMetadataFilterBuilder}（不 mock builder）
 * —— 这才是 end-to-end 锁住 PR5 c1 builder 实际产物与 collection 对齐的契约。
 */
@ExtendWith(MockitoExtension.class)
class MultiChannelRetrievalEngineFilterAlignmentTest {

    /** 两个 case 共用的 plan / 安全等级 —— 用例间唯一变化只应是 KB id 自身。 */
    private static final RetrievalPlan PLAN = new RetrievalPlan(5, 3);
    private static final int SECURITY_LEVEL = 2;

    @Mock
    private RetrieverService retrieverService;

    @Mock
    private KbMetadataReader kbMetadataReader;

    private MultiChannelRetrievalEngine engine;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        engine = new MultiChannelRetrievalEngine(
                List.of(),
                List.<SearchResultPostProcessor>of(),
                retrieverService,
                kbMetadataReader,
                new DefaultMetadataFilterBuilder(),
                directExecutor
        );
    }

    @Test
    void singleKbPath_collectionAndKbIdFilterAlignToTargetKbId() {
        when(kbMetadataReader.getCollectionName("kb-1")).thenReturn("collection_1");
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        engine.retrieveKnowledgeChannels(
                List.of(new SubQuestionIntent("q", List.of())),
                PLAN,
                new RetrievalScope(
                        AccessScope.ids(Set.of("kb-1")),
                        Map.of("kb-1", SECURITY_LEVEL),
                        "kb-1")
        );

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(captor.capture());
        FilterAlignmentAssertions.assertCollectionAlignsKbIdFilter(
                captor.getValue(),
                Map.of("collection_1", "kb-1"));
    }

    @Test
    void singleKbPath_alignmentHoldsForDifferentKb() {
        // 第二个用例：换一个 KB，确保对齐契约不是侥幸（避免单一硬编码 happy path）。
        when(kbMetadataReader.getCollectionName("kb-finance")).thenReturn("collection_finance");
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        engine.retrieveKnowledgeChannels(
                List.of(new SubQuestionIntent("q2", List.of())),
                PLAN,
                new RetrievalScope(
                        AccessScope.ids(Set.of("kb-finance")),
                        Map.of("kb-finance", SECURITY_LEVEL),
                        "kb-finance")
        );

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(captor.capture());
        FilterAlignmentAssertions.assertCollectionAlignsKbIdFilter(
                captor.getValue(),
                Map.of("collection_finance", "kb-finance"));
    }
}
