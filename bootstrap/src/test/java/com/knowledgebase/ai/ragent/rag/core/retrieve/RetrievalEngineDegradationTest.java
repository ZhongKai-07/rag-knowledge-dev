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

package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPResponse;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.knowledgebase.ai.ragent.rag.core.prompt.ContextFormatter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
import com.knowledgebase.ai.ragent.rag.dto.RetrievalContext;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import com.knowledgebase.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEngineDegradationTest {

    private final Executor directExecutor = Runnable::run;

    @Test
    void retrieveReturnsEmptyContextForFailedSubQuestionRetrieval() {
        MultiChannelRetrievalEngine multiChannelRetrievalEngine = mock(MultiChannelRetrievalEngine.class);
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(any(), any(), any()))
                .thenThrow(new IllegalStateException("retrieval unavailable"));

        RetrievalEngine retrievalEngine = new RetrievalEngine(
                mock(ContextFormatter.class),
                mock(MCPParameterExtractor.class),
                mock(MCPToolRegistry.class),
                multiChannelRetrievalEngine,
                new RagRetrievalProperties(),
                directExecutor,
                directExecutor);

        IntentNode node = IntentNode.builder()
                .id("intent-1")
                .name("intent-1")
                .kind(IntentKind.KB)
                .build();
        NodeScore score = NodeScore.builder().node(node).score(0.9).build();

        RetrievalContext context = retrievalEngine.retrieve(
                List.of(new SubQuestionIntent("question", List.of(score))),
                RetrievalScope.all(null));

        assertThat(context.getKbContext()).isEmpty();
        assertThat(context.getMcpContext()).isEmpty();
        assertThat(context.getIntentChunks()).isEmpty();
    }

    @Test
    void retrieveFormatsMcpTimeoutWhenBatchExecutorRejectsSubmission() {
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(contextFormatter.formatMcpContext(anyList(), anyList())).thenReturn("mcp timeout context");

        MCPParameterExtractor parameterExtractor = mock(MCPParameterExtractor.class);
        when(parameterExtractor.extractParameters(any(), any(), any())).thenReturn(Map.of());

        MCPToolExecutor toolExecutor = mock(MCPToolExecutor.class);
        when(toolExecutor.getToolDefinition()).thenReturn(MCPTool.builder()
                .toolId("approval_query")
                .description("approval query")
                .build());

        MCPToolRegistry toolRegistry = mock(MCPToolRegistry.class);
        when(toolRegistry.getExecutor("approval_query")).thenReturn(Optional.of(toolExecutor));

        Executor rejectingMcpExecutor = command -> {
            throw new RejectedExecutionException("mcp pool saturated");
        };

        RetrievalEngine retrievalEngine = new RetrievalEngine(
                contextFormatter,
                parameterExtractor,
                toolRegistry,
                mock(MultiChannelRetrievalEngine.class),
                new RagRetrievalProperties(),
                directExecutor,
                rejectingMcpExecutor);

        IntentNode node = IntentNode.builder()
                .id("intent-mcp")
                .name("intent-mcp")
                .kind(IntentKind.MCP)
                .mcpToolId("approval_query")
                .build();
        NodeScore score = NodeScore.builder().node(node).score(0.9).build();

        RetrievalContext context = retrievalEngine.retrieve(
                List.of(new SubQuestionIntent("check my approvals", List.of(score))),
                RetrievalScope.all(null));

        assertThat(context.getMcpContext()).contains("mcp timeout context");

        ArgumentCaptor<List<MCPResponse>> responsesCaptor = ArgumentCaptor.forClass(List.class);
        verify(contextFormatter).formatMcpContext(responsesCaptor.capture(), anyList());
        assertThat(responsesCaptor.getValue())
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.isSuccess()).isFalse();
                    assertThat(response.getToolId()).isEqualTo("approval_query");
                    assertThat(response.getErrorCode()).isEqualTo("TIMEOUT");
                    assertThat(response.getErrorMessage()).contains("mcp pool saturated");
                });
    }
}
