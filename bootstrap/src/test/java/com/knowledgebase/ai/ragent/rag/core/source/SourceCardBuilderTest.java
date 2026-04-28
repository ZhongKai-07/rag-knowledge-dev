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

package com.knowledgebase.ai.ragent.rag.core.source;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import com.knowledgebase.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SourceCardBuilderTest {

    private KnowledgeDocumentService documentService;
    private SourceCardBuilder builder;

    @BeforeEach
    void setUp() {
        documentService = mock(KnowledgeDocumentService.class);
        builder = new SourceCardBuilder(documentService);
    }

    private static RetrievedChunk chunk(String id, String docId, Integer idx, double score, String text) {
        return RetrievedChunk.builder()
                .id(id).docId(docId).chunkIndex(idx).score((float) score).text(text).build();
    }

    @Test
    void shouldReturnEmptyListOnEmptyInput() {
        List<SourceCard> cards = builder.build(List.of(), 8, 200);

        assertThat(cards).isEmpty();
        verifyNoInteractions(documentService);
    }

    @Test
    void shouldAggregateByDocIdSortByTopScoreDesc() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d1", 0, 0.5d, "hello A"),
                chunk("c2", "d2", 1, 0.9d, "hello B"),
                chunk("c3", "d1", 2, 0.7d, "hello C")
        );
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "Doc One", "kb1"),
                new DocumentMetaSnapshot("d2", "Doc Two", "kb2")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).getDocId()).isEqualTo("d2");
        assertThat(cards.get(0).getIndex()).isEqualTo(1);
        assertThat(cards.get(0).getTopScore()).isEqualTo(0.9f);
        assertThat(cards.get(1).getDocId()).isEqualTo("d1");
        assertThat(cards.get(1).getIndex()).isEqualTo(2);
        assertThat(cards.get(1).getTopScore()).isEqualTo(0.7f);
    }

    @Test
    void shouldSortChunksWithinCardByChunkIndexAsc() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d1", 5, 0.7d, "five"),
                chunk("c2", "d1", 1, 0.5d, "one"),
                chunk("c3", "d1", 3, 0.9d, "three")
        );
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getChunks()).extracting("chunkIndex")
                .containsExactly(1, 3, 5);
    }

    @Test
    void shouldDropChunksWithNullDocId() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", null, 0, 0.5d, "null docid"),
                chunk("c2", "d1", 0, 0.8d, "valid"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getDocId()).isEqualTo("d1");
    }

    @Test
    void shouldFilterCardsWhoseDocIdMissingFromMetaQuery() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d_unknown", 0, 0.9d, "ghost"),
                chunk("c2", "d1", 0, 0.5d, "real"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getDocId()).isEqualTo("d1");
    }

    @Test
    void shouldReturnEmptyWhenAllDocIdsMissFromMetaQuery() {
        List<RetrievedChunk> chunks = List.of(chunk("c1", "d_ghost", 0, 0.9d, "ghost"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of());

        List<SourceCard> cards = builder.build(chunks, 8, 200);

        assertThat(cards).isEmpty();
    }

    @Test
    void shouldTruncatePreviewByCodePoints() {
        String longText = "中文内容".repeat(100);
        List<RetrievedChunk> chunks = List.of(chunk("c1", "d1", 0, 0.9d, longText));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 8, 10);

        String preview = cards.get(0).getChunks().get(0).getPreview();
        assertThat(preview.codePointCount(0, preview.length())).isLessThanOrEqualTo(10);
    }

    @Test
    void shouldClipToMaxCards() {
        List<RetrievedChunk> chunks = List.of(
                chunk("c1", "d1", 0, 0.9d, "a"),
                chunk("c2", "d2", 0, 0.8d, "b"),
                chunk("c3", "d3", 0, 0.7d, "c"));
        when(documentService.findMetaByIds(any())).thenReturn(List.of(
                new DocumentMetaSnapshot("d1", "D1", "kb1"),
                new DocumentMetaSnapshot("d2", "D2", "kb1"),
                new DocumentMetaSnapshot("d3", "D3", "kb1")));

        List<SourceCard> cards = builder.build(chunks, 2, 200);

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting("docId").containsExactly("d1", "d2");
    }
}
