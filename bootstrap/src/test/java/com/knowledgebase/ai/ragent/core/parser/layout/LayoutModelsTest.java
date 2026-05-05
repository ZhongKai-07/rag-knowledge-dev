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

package com.knowledgebase.ai.ragent.core.parser.layout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutModelsTest {

    @Test
    void layoutBlock_nullHeadingPath_isCoercedToEmptyList() {
        LayoutBlock b = new LayoutBlock("b1", BlockType.PARAGRAPH, 1, null, "text", null, null, null, null);
        assertNotNull(b.headingPath());
        assertTrue(b.headingPath().isEmpty());
    }

    @Test
    void layoutBlock_carriesAllProvidedFields() {
        LayoutBlock.Bbox bbox = new LayoutBlock.Bbox(1.0, 2.0, 3.0, 4.0);
        LayoutBlock b = new LayoutBlock(
                "b1", BlockType.TITLE, 2, bbox, "Heading", 7, 0.95, 1, List.of("Doc", "Section"));
        assertEquals("b1", b.blockId());
        assertEquals(BlockType.TITLE, b.blockType());
        assertEquals(2, b.pageNo());
        assertEquals(bbox, b.bbox());
        assertEquals("Heading", b.text());
        assertEquals(7, b.readingOrder());
        assertEquals(0.95, b.confidence());
        assertEquals(1, b.headingLevel());
        assertEquals(List.of("Doc", "Section"), b.headingPath());
    }

    @Test
    void documentPageText_nullBlocks_isCoercedToEmptyList() {
        DocumentPageText p = new DocumentPageText("doc1", 1, "text", "NATIVE_TEXT", 0.9, null);
        assertNotNull(p.blocks());
        assertTrue(p.blocks().isEmpty());
    }

    @Test
    void layoutTable_nullRowsAndHeadingPath_areCoercedToEmptyList() {
        LayoutTable t = new LayoutTable("t1", 1, null, null, null, null, null);
        assertNotNull(t.rows());
        assertTrue(t.rows().isEmpty());
        assertNotNull(t.headingPath());
        assertTrue(t.headingPath().isEmpty());
    }
}
