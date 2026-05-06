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
import com.knowledgebase.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingDispatcher;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingInput;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import com.knowledgebase.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.knowledgebase.ai.ragent.ingestion.domain.result.NodeResult;
import com.knowledgebase.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文本分块节点
 * 负责将输入的完整文本（原始文本或增强后的文本）按照指定的策略切分成多个较小的文本块（Chunk）
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final IngestionChunkingDispatcher dispatcher;
    private final ChunkEmbeddingService chunkEmbeddingService;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        ChunkerSettings settings = parseSettings(config.getSettings());
        ChunkingMode mode = settings.getStrategy();
        if (mode == null) {
            return NodeResult.fail(new ClientException("ChunkerNode 缺少 settings.strategy"));
        }
        IngestionChunkingInput input = IngestionChunkingInput.from(context);
        ChunkingOptions options = convertToChunkConfig(settings);

        List<VectorChunk> chunks = dispatcher.chunk(mode, input, options);
        chunkEmbeddingService.embed(chunks, null);
        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        return settings.getStrategy().createDefaultOptions(
                settings.getChunkSize(), settings.getOverlapSize());
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
