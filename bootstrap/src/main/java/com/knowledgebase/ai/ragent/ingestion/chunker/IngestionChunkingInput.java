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
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Chunker 阶段的统一输入模型。屏蔽 IngestionContext 的具体形态，让
 * {@link IngestionChunkingStrategy} 不直接依赖 ingestion domain 的 god object。
 *
 * <ul>
 *   <li>{@code text}：legacy adapter 用，取值语义 enhancedText &gt; rawText</li>
 *   <li>{@code parseResult}：SoT for layout，structured strategy 消费</li>
 *   <li>{@code parseMode}：用户意图 BASIC / ENHANCED（信号性）</li>
 *   <li>{@code metadata}：ingestion-level metadata，可空</li>
 * </ul>
 */
public record IngestionChunkingInput(
        String text,
        ParseResult parseResult,
        ParseMode parseMode,
        Map<String, Object> metadata) {

    public IngestionChunkingInput {
        if (metadata == null) metadata = Map.of();
    }

    public static IngestionChunkingInput from(IngestionContext context) {
        String text = StringUtils.hasText(context.getEnhancedText())
                ? context.getEnhancedText()
                : context.getRawText();
        ParseMode mode = ParseMode.fromValue(context.getParseMode());
        return new IngestionChunkingInput(
                text == null ? "" : text,
                context.getParseResult(),
                mode,
                context.getMetadata());
    }
}
