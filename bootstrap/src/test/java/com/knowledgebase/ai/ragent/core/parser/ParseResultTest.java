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
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseResultTest {

    @Test
    void legacyTwoArgConstructor_yieldsEmptyPagesAndTables() {
        ParseResult r = new ParseResult("hello", Map.of("k", "v"));
        assertEquals("hello", r.text());
        assertEquals("v", r.metadata().get("k"));
        assertTrue(r.pages().isEmpty());
        assertTrue(r.tables().isEmpty());
    }

    @Test
    void legacyStaticFactories_remainAvailableForExistingParsers() {
        assertEquals("hello", ParseResult.ofText("hello").text());
        assertEquals("v", ParseResult.of("hello", Map.of("k", "v")).metadata().get("k"));
    }

    @Test
    void fullConstructor_carriesAllFields() {
        LayoutBlock b = new LayoutBlock("b1", BlockType.TITLE, 1, null, "Title", 1, 0.99D, 1, List.of());
        DocumentPageText page = new DocumentPageText("doc1", 1, "Title", "NATIVE_TEXT", 0.99D, List.of(b));
        ParseResult r = new ParseResult("t", Map.of(), List.of(page), List.of());
        assertEquals(1, r.pages().size());
        assertEquals("Title", r.pages().get(0).blocks().get(0).text());
    }
}
