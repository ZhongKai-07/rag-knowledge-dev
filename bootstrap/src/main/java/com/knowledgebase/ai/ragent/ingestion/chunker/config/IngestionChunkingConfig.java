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

package com.knowledgebase.ai.ragent.ingestion.chunker.config;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.strategy.FixedSizeTextChunker;
import com.knowledgebase.ai.ragent.core.chunk.strategy.StructureAwareTextChunker;
import com.knowledgebase.ai.ragent.ingestion.chunker.LegacyTextChunkingStrategyAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册两个 LegacyAdapter bean，每个对应一个 {@link ChunkingMode}. */
@Configuration
public class IngestionChunkingConfig {

    @Bean
    public LegacyTextChunkingStrategyAdapter legacyFixedSizeAdapter(FixedSizeTextChunker delegate) {
        return new LegacyTextChunkingStrategyAdapter(ChunkingMode.FIXED_SIZE, delegate);
    }

    @Bean
    public LegacyTextChunkingStrategyAdapter legacyStructureAwareAdapter(StructureAwareTextChunker delegate) {
        return new LegacyTextChunkingStrategyAdapter(ChunkingMode.STRUCTURE_AWARE, delegate);
    }
}
