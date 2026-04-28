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

package com.knowledgebase.ai.ragent.rag.core.retrieve.postprocessor;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicationPostProcessorTest {

    private final DeduplicationPostProcessor processor = new DeduplicationPostProcessor();

    @Test
    void process_whenCurrentChunksEmpty_shouldNotRestoreChunksFromResults() {
        RetrievedChunk vectorChunk = chunk("same-id", 0.40f, "vector");

        List<RetrievedChunk> output = processor.process(
                List.of(),
                List.of(result(SearchChannelType.VECTOR_GLOBAL, vectorChunk)),
                context()
        );

        assertThat(output).isEmpty();
    }

    @Test
    void process_whenHigherPriorityDuplicateWasFilteredOut_shouldKeepRemainingChunk() {
        RetrievedChunk vectorChunk = chunk("same-id", 0.40f, "vector");
        RetrievedChunk intentChunk = chunk("same-id", 0.95f, "intent");

        List<RetrievedChunk> output = processor.process(
                List.of(vectorChunk),
                List.of(
                        result(SearchChannelType.INTENT_DIRECTED, intentChunk),
                        result(SearchChannelType.VECTOR_GLOBAL, vectorChunk)
                ),
                context()
        );

        assertThat(output).containsExactly(vectorChunk);
    }

    @Test
    void process_whenCurrentChunksContainDuplicates_shouldPreferHigherPriorityChannel() {
        RetrievedChunk vectorChunk = chunk("same-id", 0.99f, "vector");
        RetrievedChunk intentChunk = chunk("same-id", 0.80f, "intent");

        List<RetrievedChunk> output = processor.process(
                List.of(vectorChunk, intentChunk),
                List.of(
                        result(SearchChannelType.VECTOR_GLOBAL, vectorChunk),
                        result(SearchChannelType.INTENT_DIRECTED, intentChunk)
                ),
                context()
        );

        assertThat(output).containsExactly(intentChunk);
    }

    @Test
    void process_whenChannelPriorityTies_shouldPreferHigherScoreThenEarlierChunk() {
        RetrievedChunk lowerScore = chunk("same-id", 0.60f, "first");
        RetrievedChunk higherScore = chunk("same-id", 0.90f, "second");

        List<RetrievedChunk> higherScoreOutput = processor.process(
                List.of(lowerScore, higherScore),
                List.of(
                        result(SearchChannelType.KEYWORD_ES, lowerScore),
                        result(SearchChannelType.KEYWORD_ES, higherScore)
                ),
                context()
        );

        assertThat(higherScoreOutput).containsExactly(higherScore);

        RetrievedChunk first = chunk("same-id", 0.90f, "first");
        RetrievedChunk second = chunk("same-id", 0.90f, "second");
        List<RetrievedChunk> earlierOutput = processor.process(
                List.of(first, second),
                List.of(
                        result(SearchChannelType.KEYWORD_ES, first),
                        result(SearchChannelType.KEYWORD_ES, second)
                ),
                context()
        );

        assertThat(earlierOutput).containsExactly(first);
    }

    private static RetrievedChunk chunk(String id, float score, String text) {
        return RetrievedChunk.builder()
                .id(id)
                .score(score)
                .text(text)
                .build();
    }

    private static SearchChannelResult result(SearchChannelType type, RetrievedChunk... chunks) {
        return SearchChannelResult.builder()
                .channelType(type)
                .channelName(type.name())
                .chunks(List.of(chunks))
                .confidence(1.0)
                .latencyMs(0L)
                .build();
    }

    private static SearchContext context() {
        return SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(5)
                .rerankTopK(5)
                .accessScope(AccessScope.all())
                .build();
    }
}
