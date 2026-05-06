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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.parser.DocumentParser;
import com.knowledgebase.ai.ragent.core.parser.DocumentParserSelector;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserNodeParseResultPropagationTest {

    @Test
    void parserNode_putsFullParseResultOnContext_andStillSetsRawText() {
        DocumentParser parser = mock(DocumentParser.class);
        ParseResult full = new ParseResult(
                "page1 text", Map.of(),
                List.of(new DocumentPageText(null, 1, "page1 text", null, null, List.of())),
                List.of());
        when(parser.parse(any(), any(), any())).thenReturn(full);

        DocumentParserSelector selector = mock(DocumentParserSelector.class);
        when(selector.selectByParseMode(ParseMode.BASIC)).thenReturn(parser);

        ObjectMapper objectMapper = new ObjectMapper();
        ParserNode node = new ParserNode(objectMapper, selector);

        IngestionContext ctx = IngestionContext.builder()
                .rawBytes("hello".getBytes())
                .mimeType("text/plain")
                .parseMode(ParseMode.BASIC.getValue())
                .build();
        NodeConfig config = NodeConfig.builder()
                .settings(objectMapper.createObjectNode())
                .build();

        node.execute(ctx, config);

        assertThat(ctx.getParseResult()).isSameAs(full);
        assertThat(ctx.getRawText()).isEqualTo("page1 text");
        assertThat(ctx.getParseResult().pages()).hasSize(1);
    }
}
