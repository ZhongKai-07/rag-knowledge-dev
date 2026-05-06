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

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.FixedSizeOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionChunkingDispatcherTest {

    @Test
    void dispatch_picksHighestPriorityMatchingStrategy() {
        IngestionChunkingStrategy high = mock(IngestionChunkingStrategy.class);
        IngestionChunkingStrategy low = mock(IngestionChunkingStrategy.class);
        when(high.priority()).thenReturn(100);
        when(low.priority()).thenReturn(10);
        when(high.supports(any(), any())).thenReturn(true);
        when(low.supports(any(), any())).thenReturn(true);
        when(high.chunk(any(), any())).thenReturn(List.of(new VectorChunk()));

        IngestionChunkingDispatcher d = new IngestionChunkingDispatcher(List.of(low, high));
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());
        d.chunk(ChunkingMode.STRUCTURE_AWARE, in, new FixedSizeOptions(512, 64));

        verify(high).chunk(any(), any());
        verify(low, never()).chunk(any(), any());
    }

    @Test
    void dispatch_failsFastOnAmbiguousSamePriority() {
        IngestionChunkingStrategy a = mock(IngestionChunkingStrategy.class);
        IngestionChunkingStrategy b = mock(IngestionChunkingStrategy.class);
        when(a.priority()).thenReturn(50);
        when(b.priority()).thenReturn(50);
        when(a.supports(any(), any())).thenReturn(true);
        when(b.supports(any(), any())).thenReturn(true);

        IngestionChunkingDispatcher d = new IngestionChunkingDispatcher(List.of(a, b));
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());

        assertThatThrownBy(() -> d.chunk(ChunkingMode.STRUCTURE_AWARE, in, new FixedSizeOptions(512, 64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous chunking strategies");
    }

    @Test
    void dispatch_throwsWhenNoStrategyMatches() {
        IngestionChunkingStrategy s = mock(IngestionChunkingStrategy.class);
        when(s.priority()).thenReturn(10);
        when(s.supports(any(), any())).thenReturn(false);

        IngestionChunkingDispatcher d = new IngestionChunkingDispatcher(List.of(s));
        IngestionChunkingInput in = new IngestionChunkingInput("t", null, ParseMode.BASIC, Map.of());

        assertThatThrownBy(() -> d.chunk(ChunkingMode.STRUCTURE_AWARE, in, new FixedSizeOptions(512, 64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No chunking strategy supports");
    }
}
