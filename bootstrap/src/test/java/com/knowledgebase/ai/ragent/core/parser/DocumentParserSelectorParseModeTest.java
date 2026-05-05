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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 纯单元测试，直接装配 {@link DocumentParserSelector} 验证 {@code selectByParseMode} 路由逻辑。
 * 不走 {@code @SpringBootTest}：本测试只检查路由分支，无需 Redis/PG/OpenSearch 等容器。
 */
class DocumentParserSelectorParseModeTest {

    private static DocumentParser fakeParser(String type) {
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

    @Test
    void basicMode_returnsTikaParser() {
        DocumentParser tika = fakeParser(ParserType.TIKA.getType());
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tika));

        DocumentParser p = selector.selectByParseMode(ParseMode.BASIC);

        assertSame(tika, p);
    }

    @Test
    void enhancedMode_whenDoclingRegistered_returnsDocling() {
        DocumentParser tika = fakeParser(ParserType.TIKA.getType());
        DocumentParser docling = fakeParser(ParserType.DOCLING.getType());
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tika, docling));

        DocumentParser p = selector.selectByParseMode(ParseMode.ENHANCED);

        assertSame(docling, p);
    }

    @Test
    void enhancedMode_whenDoclingMissing_fallsBackToTika() {
        DocumentParser tika = fakeParser(ParserType.TIKA.getType());
        DocumentParserSelector selector = new DocumentParserSelector(List.of(tika));

        DocumentParser p = selector.selectByParseMode(ParseMode.ENHANCED);

        assertNotNull(p);
        assertSame(tika, p);
    }
}
