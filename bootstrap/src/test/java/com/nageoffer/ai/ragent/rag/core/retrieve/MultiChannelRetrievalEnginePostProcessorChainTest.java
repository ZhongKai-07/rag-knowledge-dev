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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine.RetrievalPlan;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.DeduplicationPostProcessor;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MultiChannelRetrievalEnginePostProcessorChainTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void retrieveKnowledgeChannels_shouldNotAllowLaterProcessorToRestoreDroppedChunks() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("c1")
                .score(0.80f)
                .text("payload")
                .build();

        SearchChannel channel = new SearchChannel() {
            @Override
            public String getName() {
                return "fake-vector";
            }

            @Override
            public int getPriority() {
                return 0;
            }

            @Override
            public boolean isEnabled(SearchContext context) {
                return true;
            }

            @Override
            public SearchChannelResult search(SearchContext context) {
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.VECTOR_GLOBAL)
                        .channelName(getName())
                        .chunks(List.of(chunk))
                        .confidence(1.0)
                        .latencyMs(0L)
                        .build();
            }

            @Override
            public SearchChannelType getType() {
                return SearchChannelType.VECTOR_GLOBAL;
            }
        };

        SearchResultPostProcessor dropAll = new SearchResultPostProcessor() {
            @Override
            public String getName() {
                return "DropAll";
            }

            @Override
            public int getOrder() {
                return 0;
            }

            @Override
            public boolean isEnabled(SearchContext context) {
                return true;
            }

            @Override
            public List<RetrievedChunk> process(List<RetrievedChunk> chunks, List<SearchChannelResult> results,
                                                SearchContext context) {
                return List.of();
            }
        };

        Executor directExecutor = Runnable::run;
        MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(
                List.of(channel),
                List.of(dropAll, new DeduplicationPostProcessor()),
                mock(RetrieverService.class),
                mock(KnowledgeBaseMapper.class),
                mock(KbReadAccessPort.class),
                mock(MetadataFilterBuilder.class),
                directExecutor
        );

        List<RetrievedChunk> output = engine.retrieveKnowledgeChannels(
                List.of(new SubQuestionIntent("q", List.of())),
                new RetrievalPlan(5, 5),
                AccessScope.all(),
                null
        );

        assertThat(output).isEmpty();
    }
}
