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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 2.5 / PR-DOCINT-1a / PR 2.
 *
 * <p>{@link FallbackParserDecorator} must:
 * <ul>
 *   <li>invoke fallback when primary throws,</li>
 *   <li>stamp {@code parse_engine_requested} / {@code parse_engine_actual} / {@code parse_fallback_reason}
 *       so the frontend can render the degraded-engine warning,</li>
 *   <li>also degrade gracefully on {@code extractText}.</li>
 * </ul>
 */
class FallbackParserDecoratorTest {

    private static final DocumentParser ALWAYS_FAIL = new DocumentParser() {
        @Override
        public String getParserType() {
            return "always-fail";
        }

        @Override
        public ParseResult parse(byte[] c, String m, Map<String, Object> o) {
            throw new RuntimeException("primary down");
        }

        @Override
        public String extractText(InputStream is, String n) {
            throw new RuntimeException("primary down");
        }

        @Override
        public boolean supports(String m) {
            return true;
        }
    };

    private static final DocumentParser FALLBACK = new DocumentParser() {
        @Override
        public String getParserType() {
            return "fallback-stub";
        }

        @Override
        public ParseResult parse(byte[] c, String m, Map<String, Object> o) {
            return new ParseResult("fallback-text", new HashMap<>());
        }

        @Override
        public String extractText(InputStream is, String n) {
            return "fallback-text";
        }

        @Override
        public boolean supports(String m) {
            return true;
        }
    };

    @Test
    void primaryFails_fallbackInvoked_metadataStamped() {
        FallbackParserDecorator dec =
                new FallbackParserDecorator(ALWAYS_FAIL, FALLBACK, "docling", "tika");

        ParseResult r = dec.parse(new byte[0], "application/pdf", new HashMap<>());

        assertEquals("fallback-text", r.text());
        assertEquals("docling", r.metadata().get("parse_engine_requested"));
        assertEquals("tika", r.metadata().get("parse_engine_actual"));
        assertEquals("primary_failed", r.metadata().get("parse_fallback_reason"));
    }

    @Test
    void primarySucceeds_metadataMarksRequestedEngine() {
        FallbackParserDecorator dec =
                new FallbackParserDecorator(FALLBACK, ALWAYS_FAIL, "docling", "tika");

        ParseResult r = dec.parse(new byte[0], "application/pdf", new HashMap<>());

        assertEquals("fallback-text", r.text());
        assertEquals("docling", r.metadata().get("parse_engine_requested"));
        assertEquals("docling", r.metadata().get("parse_engine_actual"));
    }

    @Test
    void extractTextFallback_alsoWorks() {
        FallbackParserDecorator dec =
                new FallbackParserDecorator(ALWAYS_FAIL, FALLBACK, "docling", "tika");

        InputStream is = new ByteArrayInputStream(new byte[0]);

        assertEquals("fallback-text", dec.extractText(is, "f.pdf"));
    }
}
