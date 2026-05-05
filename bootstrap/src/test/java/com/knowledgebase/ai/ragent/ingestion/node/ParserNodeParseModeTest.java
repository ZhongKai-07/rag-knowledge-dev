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

package com.knowledgebase.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.parser.DocumentParser;
import com.knowledgebase.ai.ragent.core.parser.DocumentParserSelector;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.knowledgebase.ai.ragent.ingestion.domain.result.NodeResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 ParserNode 从 NodeConfig.settings.parseMode 数据驱动路由。
 * 用纯单元测试 + 自定义 stub selector，避免 @SpringBootTest 启容器依赖。
 */
class ParserNodeParseModeTest {

    private final ObjectMapper om = new ObjectMapper();

    /** 记录 selector 被调用时的 ParseMode；返回一个固定文本的 fake parser。 */
    private static class RecordingSelector extends DocumentParserSelector {
        final AtomicReference<ParseMode> lastMode = new AtomicReference<>();

        RecordingSelector() {
            super(java.util.List.of());
        }

        @Override
        public DocumentParser selectByParseMode(ParseMode mode) {
            lastMode.set(mode);
            return new DocumentParser() {
                @Override
                public String getParserType() {
                    return "FAKE";
                }

                @Override
                public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
                    return ParseResult.ofText("fake-parsed-text");
                }
            };
        }
    }

    private IngestionContext newCtx() {
        IngestionContext ctx = IngestionContext.builder()
                .rawBytes("hello world".getBytes())
                .mimeType("text/plain")
                .build();
        return ctx;
    }

    private NodeConfig cfgWithParseMode(String parseModeValue) throws Exception {
        Map<String, Object> map = new HashMap<>();
        if (parseModeValue != null) {
            map.put("parseMode", parseModeValue);
        }
        JsonNode settings = om.valueToTree(map);
        return NodeConfig.builder()
                .nodeId("p1")
                .nodeType("ParserNode")
                .settings(settings)
                .build();
    }

    @Test
    void noParseModeKey_defaultsToBasic() throws Exception {
        RecordingSelector selector = new RecordingSelector();
        ParserNode node = new ParserNode(om, selector);

        NodeResult result = node.execute(newCtx(), cfgWithParseMode(null));

        assertTrue(result.isSuccess());
        assertEquals(ParseMode.BASIC, selector.lastMode.get());
    }

    @Test
    void parseModeBasic_routesAsBasic() throws Exception {
        RecordingSelector selector = new RecordingSelector();
        ParserNode node = new ParserNode(om, selector);

        NodeResult result = node.execute(newCtx(), cfgWithParseMode("basic"));

        assertTrue(result.isSuccess());
        assertEquals(ParseMode.BASIC, selector.lastMode.get());
    }

    @Test
    void parseModeEnhanced_routesAsEnhanced() throws Exception {
        RecordingSelector selector = new RecordingSelector();
        ParserNode node = new ParserNode(om, selector);

        NodeResult result = node.execute(newCtx(), cfgWithParseMode("enhanced"));

        assertTrue(result.isSuccess());
        assertEquals(ParseMode.ENHANCED, selector.lastMode.get());
    }

    @Test
    void nullSettings_defaultsToBasic() {
        RecordingSelector selector = new RecordingSelector();
        ParserNode node = new ParserNode(om, selector);
        NodeConfig cfg = NodeConfig.builder().nodeId("p1").nodeType("ParserNode").settings(null).build();

        NodeResult result = node.execute(newCtx(), cfg);

        assertTrue(result.isSuccess());
        assertEquals(ParseMode.BASIC, selector.lastMode.get());
    }
}
