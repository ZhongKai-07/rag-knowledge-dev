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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingDispatcher;
import com.knowledgebase.ai.ragent.ingestion.chunker.IngestionChunkingInput;
import com.knowledgebase.ai.ragent.ingestion.domain.context.IngestionContext;
import com.knowledgebase.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkerNodeDispatchTest {

    @Test
    void execute_routesThroughDispatcherAndEmbeds() {
        ObjectMapper objectMapper = new ObjectMapper();
        IngestionChunkingDispatcher dispatcher = mock(IngestionChunkingDispatcher.class);
        ChunkEmbeddingService embedSvc = mock(ChunkEmbeddingService.class);
        VectorChunk chunk = VectorChunk.builder().chunkId("c1").index(0).content("x").build();
        when(dispatcher.chunk(any(), any(IngestionChunkingInput.class), any())).thenReturn(List.of(chunk));

        ChunkerNode node = new ChunkerNode(objectMapper, dispatcher, embedSvc);   // ← 3 参 ctor
        IngestionContext ctx = IngestionContext.builder()
                .rawText("x").parseMode("basic").build();
        ObjectNode settings = objectMapper.createObjectNode();
        settings.put("strategy", ChunkingMode.STRUCTURE_AWARE.getValue());
        settings.put("chunkSize", 1000);
        settings.put("overlapSize", 100);
        NodeConfig config = NodeConfig.builder().settings(settings).build();

        node.execute(ctx, config);

        assertThat(ctx.getChunks()).containsExactly(chunk);
        verify(embedSvc).embed(List.of(chunk), null);
    }
}
