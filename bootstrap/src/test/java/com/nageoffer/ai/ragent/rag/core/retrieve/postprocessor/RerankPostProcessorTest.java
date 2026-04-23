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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RerankPostProcessorTest {

    @Test
    void capsInputToRecallTopKSortedByScoreDescBeforeRerank() {
        RerankService rerank = mock(RerankService.class);
        // Stub: echo back first rerankTopK candidates to keep test focused on input shape.
        when(rerank.rerank(anyString(), any(), anyInt())).thenAnswer(inv -> {
            List<RetrievedChunk> in = inv.getArgument(1);
            int topN = inv.getArgument(2);
            return in.subList(0, Math.min(in.size(), topN));
        });

        RerankPostProcessor processor = new RerankPostProcessor(rerank);

        // 60 chunks with random-looking scores; expect top 30 (by score desc) reach rerank.
        List<RetrievedChunk> chunks = IntStream.range(0, 60)
                .mapToObj(i -> RetrievedChunk.builder()
                        .id("c" + i)
                        .text("t" + i)
                        // alternating score pattern ensures a mix of hi/lo — top30 will be
                        // the 30 ids with the highest (i % 7 * 0.1f + 0.05f) style scores.
                        .score(((i * 13) % 100) / 100.0f)
                        .build())
                .toList();

        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        List<RetrievedChunk> out = processor.process(chunks, List.of(), ctx);

        ArgumentCaptor<List<RetrievedChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(rerank).rerank(anyString(), captor.capture(), anyInt());
        List<RetrievedChunk> sentToRerank = captor.getValue();

        assertThat(sentToRerank).hasSize(30);
        // Must be sorted by score desc
        for (int i = 1; i < sentToRerank.size(); i++) {
            float prev = sentToRerank.get(i - 1).getScore();
            float cur = sentToRerank.get(i).getScore();
            assertThat(prev).isGreaterThanOrEqualTo(cur);
        }
        // Output is the rerank truncation (stub returned first 10)
        assertThat(out).hasSize(10);
    }

    @Test
    void callsRerankWithRerankTopKAsTopN() {
        RerankService rerank = mock(RerankService.class);
        when(rerank.rerank(anyString(), any(), anyInt())).thenReturn(List.of());
        RerankPostProcessor processor = new RerankPostProcessor(rerank);

        List<RetrievedChunk> chunks = IntStream.range(0, 15)
                .mapToObj(i -> RetrievedChunk.builder().id("c" + i).score(0.1f * i).build())
                .toList();

        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        processor.process(chunks, List.of(), ctx);

        verify(rerank).rerank(anyString(), any(), org.mockito.ArgumentMatchers.eq(10));
    }

    @Test
    void skipsRerankWhenChunksEmpty() {
        RerankService rerank = mock(RerankService.class);
        RerankPostProcessor processor = new RerankPostProcessor(rerank);

        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        List<RetrievedChunk> out = processor.process(List.of(), List.of(), ctx);

        assertThat(out).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(rerank);
    }
}
