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

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.rag.config.RagRetrievalProperties;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.knowledgebase.ai.ragent.rag.core.prompt.ContextFormatter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.scope.RetrievalScope;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import com.knowledgebase.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEngineScoreGateRegressionTest {

    private final Executor directExecutor = Runnable::run;

    @Test
    void lowScoreKbIntentIsNotPassedToContextFormatter() {
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        when(contextFormatter.formatKbContext(anyList(), any(), anyInt())).thenReturn("kb context");

        MultiChannelRetrievalEngine multiChannelRetrievalEngine = mock(MultiChannelRetrievalEngine.class);
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(any(), any(), any()))
                .thenReturn(List.of(RetrievedChunk.builder().id("chunk-1").text("chunk").score(0.9F).build()));

        RetrievalEngine retrievalEngine = retrievalEngine(contextFormatter, mock(MCPToolRegistry.class),
                multiChannelRetrievalEngine);

        NodeScore lowKb = score(kbNode("low-kb", null), 0.1);
        NodeScore highKb = score(kbNode("high-kb", null), 0.9);

        retrievalEngine.retrieve(
                List.of(new SubQuestionIntent("question", List.of(lowKb, highKb))),
                RetrievalScope.all(null));

        ArgumentCaptor<List<NodeScore>> kbIntentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(contextFormatter).formatKbContext(kbIntentsCaptor.capture(), any(), anyInt());
        assertThat(kbIntentsCaptor.getValue())
                .extracting(ns -> ns.getNode().getId())
                .containsExactly("high-kb");
    }

    @Test
    void lowScoreMcpIntentIsNotResolvedOrExecuted() {
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        MultiChannelRetrievalEngine multiChannelRetrievalEngine = mock(MultiChannelRetrievalEngine.class);
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(any(), any(), any()))
                .thenReturn(List.of());

        MCPToolRegistry mcpToolRegistry = mock(MCPToolRegistry.class);
        RetrievalEngine retrievalEngine = retrievalEngine(contextFormatter, mcpToolRegistry, multiChannelRetrievalEngine);

        NodeScore lowMcp = score(mcpNode("approval_query"), 0.1);

        retrievalEngine.retrieve(
                List.of(new SubQuestionIntent("check approvals", List.of(lowMcp))),
                RetrievalScope.all(null));

        verify(mcpToolRegistry, never()).getExecutor("approval_query");
        verify(contextFormatter, never()).formatMcpContext(anyList(), anyList());
    }

    @Test
    void lowScoreKbTopKOverrideDoesNotInfluenceRetrievalPlan() {
        ContextFormatter contextFormatter = mock(ContextFormatter.class);
        MultiChannelRetrievalEngine multiChannelRetrievalEngine = mock(MultiChannelRetrievalEngine.class);
        when(multiChannelRetrievalEngine.retrieveKnowledgeChannels(any(), any(), any()))
                .thenReturn(List.of());

        RetrievalEngine retrievalEngine = retrievalEngine(contextFormatter, mock(MCPToolRegistry.class),
                multiChannelRetrievalEngine);

        NodeScore lowKb = score(kbNode("low-kb", 50), 0.1);

        retrievalEngine.retrieve(
                List.of(new SubQuestionIntent("question", List.of(lowKb))),
                RetrievalScope.all(null));

        ArgumentCaptor<RetrievalEngine.RetrievalPlan> planCaptor =
                ArgumentCaptor.forClass(RetrievalEngine.RetrievalPlan.class);
        verify(multiChannelRetrievalEngine).retrieveKnowledgeChannels(any(), planCaptor.capture(), any());
        assertThat(planCaptor.getValue().recallTopK()).isEqualTo(30);
        assertThat(planCaptor.getValue().rerankTopK()).isEqualTo(10);
    }

    private RetrievalEngine retrievalEngine(ContextFormatter contextFormatter,
                                            MCPToolRegistry mcpToolRegistry,
                                            MultiChannelRetrievalEngine multiChannelRetrievalEngine) {
        return new RetrievalEngine(
                contextFormatter,
                mock(MCPParameterExtractor.class),
                mcpToolRegistry,
                multiChannelRetrievalEngine,
                new RagRetrievalProperties(),
                directExecutor,
                directExecutor);
    }

    private static IntentNode kbNode(String id, Integer topK) {
        return IntentNode.builder()
                .id(id)
                .name(id)
                .kind(IntentKind.KB)
                .topK(topK)
                .build();
    }

    private static IntentNode mcpNode(String toolId) {
        return IntentNode.builder()
                .id(toolId)
                .name(toolId)
                .kind(IntentKind.MCP)
                .mcpToolId(toolId)
                .build();
    }

    private static NodeScore score(IntentNode node, double score) {
        return NodeScore.builder()
                .node(node)
                .score(score)
                .build();
    }
}
