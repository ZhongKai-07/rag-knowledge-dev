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

package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionChunkingInputTest {

    @Test
    void from_prefersEnhancedTextOverRawText() {
        IngestionContext ctx = IngestionContext.builder()
                .rawText("raw")
                .enhancedText("enhanced")
                .parseMode(ParseMode.ENHANCED.getValue())
                .build();
        IngestionChunkingInput input = IngestionChunkingInput.from(ctx);
        assertThat(input.text()).isEqualTo("enhanced");
        assertThat(input.parseMode()).isEqualTo(ParseMode.ENHANCED);
    }

    @Test
    void from_fallsBackToRawTextWhenEnhancedBlank() {
        IngestionContext ctx = IngestionContext.builder()
                .rawText("raw")
                .enhancedText("")
                .parseMode(null)
                .build();
        IngestionChunkingInput input = IngestionChunkingInput.from(ctx);
        assertThat(input.text()).isEqualTo("raw");
        assertThat(input.parseMode()).isEqualTo(ParseMode.BASIC); // null → BASIC per ParseMode.fromValue
    }

    @Test
    void from_carriesParseResult() {
        ParseResult pr = new ParseResult("t", Map.of(), List.of(), List.of());
        IngestionContext ctx = IngestionContext.builder().parseResult(pr).rawText("t").build();
        IngestionChunkingInput input = IngestionChunkingInput.from(ctx);
        assertThat(input.parseResult()).isSameAs(pr);
    }

    @Test
    void compactCtor_nullMetadataBecomesEmptyMap() {
        IngestionChunkingInput input = new IngestionChunkingInput("t", null, ParseMode.BASIC, null);
        assertThat(input.metadata()).isEmpty();
    }
}
