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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 纯单元测试，直接装配 {@link DocumentParserSelector} 验证 {@code selectByParseMode} 路由逻辑。
 * 不走 {@code @SpringBootTest}：本测试只检查路由分支，无需 Redis/PG/OpenSearch 等容器。
 *
 * <p>PR 2 起 ENHANCED 路径恒返回 {@link FallbackParserDecorator}，所以原本基于 {@code assertSame} 的
 * 引用相等断言改为基于 {@code parse(...)} 之后 metadata stamp 的行为断言。
 */
class DocumentParserSelectorParseModeTest {

    /** 仅返回类型与可见性，{@code parse}/{@code extractText} 走默认实现（抛 UnsupportedOperationException）。*/
    private static DocumentParser typeOnlyFakeParser(String type) {
        return new DocumentParser() {
            @Override
            public String getParserType() {
                return type;
            }

            @Override
            public boolean supports(String mimeType) {
                return true;
            }
        };
    }

    /** 行为型 fake：每次 {@code parse} 返回 stamp 了 {@code source} 的结果，便于断言路由实际走到哪。 */
    private static DocumentParser stampingFakeParser(String type, String source) {
        return new DocumentParser() {
            @Override
            public String getParserType() {
                return type;
            }

            @Override
            public boolean supports(String mimeType) {
                return true;
            }

            @Override
            public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
                Map<String, Object> md = new HashMap<>();
                md.put("source_parser_used", source);
                return new ParseResult("parsed-by-" + source, md);
            }
        };
    }

    @Test
    void basicMode_returnsTikaParser() {
        DocumentParser tika = typeOnlyFakeParser(ParserType.TIKA.getType());
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tika));

        DocumentParser p = selector.selectByParseMode(ParseMode.BASIC);

        // BASIC 路径不包装，直接返回注册的 Tika 实例。
        assertSame(tika, p);
    }

    @Test
    void enhancedMode_whenDoclingRegistered_decoratorRoutesToDocling_andStampsActualDocling() {
        DocumentParser tika = stampingFakeParser(ParserType.TIKA.getType(), "tika");
        DocumentParser docling = stampingFakeParser(ParserType.DOCLING.getType(), "docling");
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tika, docling));

        DocumentParser p = selector.selectByParseMode(ParseMode.ENHANCED);

        ParseResult r = p.parse("hello".getBytes(), "text/plain", new HashMap<>());
        assertEquals("docling", r.metadata().get("source_parser_used")); // 路由命中 docling
        assertEquals(
                ParserType.DOCLING.getType(), r.metadata().get(FallbackParserDecorator.META_ENGINE_REQUESTED));
        assertEquals(
                ParserType.DOCLING.getType(), r.metadata().get(FallbackParserDecorator.META_ENGINE_ACTUAL));
    }

    @Test
    void enhancedMode_whenDoclingMissing_decoratorDegradesToTika_andStampsRequestedDocling() {
        DocumentParser tika = stampingFakeParser(ParserType.TIKA.getType(), "tika");
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tika));

        DocumentParser p = selector.selectByParseMode(ParseMode.ENHANCED);

        assertNotNull(p);
        ParseResult r = p.parse("hello".getBytes(), "text/plain", new HashMap<>());
        assertEquals("tika", r.metadata().get("source_parser_used")); // 实际由 Tika 解析
        // 但 metadata 仍然标记请求是 docling、实际降级到 tika，前端据此决定是否提示降级
        assertEquals(
                ParserType.DOCLING.getType(), r.metadata().get(FallbackParserDecorator.META_ENGINE_REQUESTED));
        assertEquals(
                ParserType.TIKA.getType(), r.metadata().get(FallbackParserDecorator.META_ENGINE_ACTUAL));
    }
}
