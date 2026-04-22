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

package com.nageoffer.ai.ragent.rag.core.source;

import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationStatsCollectorTest {

    private SourceCard card(int index) {
        return SourceCard.builder()
                .index(index).docId("d" + index).docName("D" + index).kbId("kb")
                .topScore(0.9f).chunks(List.of(SourceChunk.builder()
                        .chunkId("c" + index).chunkIndex(0).preview("p").score(0.9f).build()))
                .build();
    }

    @Test
    void scan_whenAnswerHasMixedValidAndInvalid_thenCountsCorrectly() {
        String answer = "第一句[^1]。第二句[^2]。越界[^99]。";
        var stats = CitationStatsCollector.scan(answer, List.of(card(1), card(2)));
        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.valid()).isEqualTo(2);
        assertThat(stats.invalid()).isEqualTo(1);
    }

    @Test
    void scan_whenAnswerBlank_thenAllZero() {
        var stats = CitationStatsCollector.scan("", List.of(card(1)));
        assertThat(stats.total()).isZero();
        assertThat(stats.valid()).isZero();
        assertThat(stats.invalid()).isZero();
        assertThat(stats.coverage()).isEqualTo(0.0);
    }

    @Test
    void scan_whenCardsEmpty_thenAllZero() {
        var stats = CitationStatsCollector.scan("有些文字[^1]。", List.of());
        assertThat(stats.total()).isZero();
        assertThat(stats.coverage()).isEqualTo(0.0);
    }

    @Test
    void scan_whenCardIndexesNonContiguous_thenValidByMembership() {
        // cards index 集合为 {1, 3, 5}；answer 出现 [^2] 应计为 invalid
        var stats = CitationStatsCollector.scan(
                "句子[^1]。句子[^2]。句子[^3]。句子[^5]。",
                List.of(card(1), card(3), card(5))
        );
        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.valid()).isEqualTo(3);   // 1,3,5
        assertThat(stats.invalid()).isEqualTo(1); // 2
    }

    @Test
    void scan_whenPartialSentencesHaveCitation_thenCoverageProportional() {
        String answer = "A[^1]。B。C[^1]。";   // 3 句粗切，2 句有引用
        var stats = CitationStatsCollector.scan(answer, List.of(card(1)));
        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.coverage()).isBetween(0.66, 0.67);
    }

    @Test
    void scan_whenAnswerLastSentenceHasNoPunctuation_thenCoverageReflectsCoarseSplit() {
        // 锁行为：spec 粗切会漏掉末尾无标点句，total sentence=1（实际无完整句），有引用句=0
        String answer = "无标点尾段[^1]";
        var stats = CitationStatsCollector.scan(answer, List.of(card(1)));
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.valid()).isEqualTo(1);
        assertThat(stats.coverage()).isEqualTo(0.0);   // 无完整句子
    }
}
