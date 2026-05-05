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

package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoclingResponseAdapterTest {

    private final DoclingResponseAdapter adapter = new DoclingResponseAdapter();

    @Test
    void mapsBlocksAndTables_groupedByPageAndOrderedByReadingOrder() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document(
                        "all text",
                        List.of(
                                new DoclingConvertResponse.DoclingBlock(
                                        "b2",
                                        "paragraph",
                                        1,
                                        new DoclingConvertResponse.Bbox(0, 30, 100, 40),
                                        2,
                                        0.98D,
                                        "NATIVE_TEXT",
                                        null,
                                        "Body...",
                                        List.of("Chapter 1")),
                                new DoclingConvertResponse.DoclingBlock(
                                        "b1",
                                        "heading",
                                        1,
                                        new DoclingConvertResponse.Bbox(0, 0, 100, 20),
                                        1,
                                        0.99D,
                                        "NATIVE_TEXT",
                                        1,
                                        "Chapter 1",
                                        List.of())),
                        List.of(new DoclingConvertResponse.DoclingTable(
                                "t1",
                                2,
                                new DoclingConvertResponse.Bbox(10, 10, 200, 100),
                                List.of(List.of("a", "b"), List.of("1", "2")),
                                List.of("Chapter 1", "1.1 Tables"),
                                3,
                                0.97D))),
                Map.of("source", "test"));

        ParseResult r = adapter.toParseResult(resp);

        assertEquals("all text", r.text());
        assertEquals(1, r.pages().size(), "all blocks live on page 1");
        assertEquals(2, r.pages().get(0).blocks().size());
        // 验证按 readingOrder 排序后，heading 在前
        assertEquals("b1", r.pages().get(0).blocks().get(0).blockId());
        assertEquals(BlockType.TITLE, r.pages().get(0).blocks().get(0).blockType());
        assertEquals(1, r.pages().get(0).blocks().get(0).headingLevel());
        assertEquals("NATIVE_TEXT", r.pages().get(0).textLayerType());
        // pageText 按 readingOrder 拼接
        assertTrue(r.pages().get(0).text().startsWith("Chapter 1\n"));
        assertEquals(1, r.tables().size());
        assertEquals(2, r.tables().get(0).rows().size());
        assertEquals("test", r.metadata().get("source"));
    }

    @Test
    void unknownBlockType_mapsToOther() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document(
                        "x",
                        List.of(new DoclingConvertResponse.DoclingBlock(
                                "b1", "weird-type", 1, null, null, null, "NATIVE_TEXT", null, "x", List.of())),
                        List.of()),
                Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals(BlockType.OTHER, r.pages().get(0).blocks().get(0).blockType());
    }

    @Test
    void mixedTextLayer_resolvesToMixed() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document(
                        "x",
                        List.of(
                                new DoclingConvertResponse.DoclingBlock(
                                        "b1", "paragraph", 1, null, 1, 0.9D, "NATIVE_TEXT", null, "n", List.of()),
                                new DoclingConvertResponse.DoclingBlock(
                                        "b2", "paragraph", 1, null, 2, 0.8D, "OCR", null, "o", List.of())),
                        List.of()),
                Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals("MIXED", r.pages().get(0).textLayerType());
    }

    @Test
    void nullDocument_yieldsEmptyResult() {
        DoclingConvertResponse resp = new DoclingConvertResponse(null, Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals("", r.text());
        assertNotNull(r.pages());
        assertTrue(r.pages().isEmpty());
        assertTrue(r.tables().isEmpty());
    }

    @Test
    void nullResponse_yieldsEmptyResultWithEmptyMetadata() {
        ParseResult r = adapter.toParseResult(null);
        assertEquals("", r.text());
        assertTrue(r.pages().isEmpty());
        assertTrue(r.tables().isEmpty());
        assertNotNull(r.metadata());
    }

    @Test
    void emptyBlocksAndTables_doesNotThrow() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document("only text", null, null), Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals("only text", r.text());
        assertTrue(r.pages().isEmpty());
        assertTrue(r.tables().isEmpty());
    }

    @Test
    void allTextLayerTypesNull_resultPageTextLayerIsNull() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DoclingConvertResponse.Document(
                        "x",
                        List.of(new DoclingConvertResponse.DoclingBlock(
                                "b1", "paragraph", 1, null, 1, 0.9D, null, null, "n", List.of())),
                        List.of()),
                Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertNull(r.pages().get(0).textLayerType());
    }
}
