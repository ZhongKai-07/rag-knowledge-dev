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

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Decorator that runs {@code primary} first; on any exception, falls back to {@code fallback}
 * and stamps observability metadata on the resulting {@link ParseResult} so callers (and the
 * frontend) can see which engine actually produced the text.
 *
 * <p>Even when primary succeeds, metadata records {@code parse_engine_actual = primaryName}
 * for traceability.
 *
 * <p>Phase 2.5 / PR-DOCINT-1a / PR 2: this is the safety-net shim that lets ENHANCED requests
 * survive a missing or unhealthy Docling engine without bringing down ingestion. PR 5 will
 * register the real {@code DoclingDocumentParser} as the primary; PR 2 wires the decorator in
 * with primary == fallback == Tika so the metadata channel is already there.
 */
@Slf4j
public class FallbackParserDecorator implements DocumentParser {

    /** Metadata keys are public so adapters / consumers don't drift on string literals. */
    public static final String META_ENGINE_REQUESTED = "parse_engine_requested";

    public static final String META_ENGINE_ACTUAL = "parse_engine_actual";
    public static final String META_FALLBACK_REASON = "parse_fallback_reason";
    public static final String REASON_PRIMARY_FAILED = "primary_failed";

    private final DocumentParser primary;
    private final DocumentParser fallback;
    private final String primaryName;
    private final String fallbackName;

    public FallbackParserDecorator(
            DocumentParser primary, DocumentParser fallback, String primaryName, String fallbackName) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
    }

    @Override
    public String getParserType() {
        // Decorator advertises the requested engine name so consumers see "docling"
        // even when the actual call degraded to Tika.
        return primaryName;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        try {
            ParseResult r = primary.parse(content, mimeType, options);
            return stamp(r, primaryName, primaryName, null);
        } catch (Exception e) {
            log.warn(
                    "Primary parser '{}' failed, falling back to '{}': {}",
                    primaryName,
                    fallbackName,
                    e.getMessage());
            ParseResult r = fallback.parse(content, mimeType, options);
            return stamp(r, primaryName, fallbackName, REASON_PRIMARY_FAILED);
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try {
            return primary.extractText(stream, fileName);
        } catch (Exception e) {
            log.warn(
                    "Primary parser '{}' extractText failed, falling back to '{}': {}",
                    primaryName,
                    fallbackName,
                    e.getMessage());
            return fallback.extractText(stream, fileName);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        return primary.supports(mimeType) || fallback.supports(mimeType);
    }

    private ParseResult stamp(ParseResult r, String requested, String actual, String reason) {
        Map<String, Object> md = new HashMap<>(r.metadata() == null ? Map.of() : r.metadata());
        md.put(META_ENGINE_REQUESTED, requested);
        md.put(META_ENGINE_ACTUAL, actual);
        if (reason != null) {
            md.put(META_FALLBACK_REASON, reason);
        }
        return new ParseResult(r.text(), md, r.pages(), r.tables());
    }
}
