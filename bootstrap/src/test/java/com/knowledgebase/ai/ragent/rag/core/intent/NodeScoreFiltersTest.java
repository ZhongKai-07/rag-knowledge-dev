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

package com.knowledgebase.ai.ragent.rag.core.intent;

import com.knowledgebase.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeScoreFiltersTest {

    @Test
    void kbReturnsOnlyKbKindAndNullKind() {
        NodeScore kb = score(node("kb", IntentKind.KB, null), 0.9);
        NodeScore nullKind = score(node("legacy-kb", null, null), 0.8);
        NodeScore mcp = score(node("mcp", IntentKind.MCP, "approval_query"), 0.7);

        assertThat(NodeScoreFilters.kb(List.of(kb, nullKind, mcp)))
                .containsExactly(kb, nullKind);
    }

    @Test
    void kbFiltersOutNullNode() {
        NodeScore nullNode = score(null, 0.9);
        NodeScore kb = score(node("kb", IntentKind.KB, null), 0.8);

        assertThat(NodeScoreFilters.kb(List.of(nullNode, kb)))
                .containsExactly(kb);
    }

    @Test
    void kbWithMinScoreDropsBelowGate() {
        NodeScore low = score(node("low-kb", IntentKind.KB, null), 0.34);
        NodeScore high = score(node("high-kb", IntentKind.KB, null), 0.35);

        assertThat(NodeScoreFilters.kb(List.of(low, high), 0.35))
                .containsExactly(high);
    }

    @Test
    void kbWithMinScoreZeroEqualsNoGate() {
        List<NodeScore> scores = List.of(
                score(node("low-kb", IntentKind.KB, null), 0.0),
                score(node("mcp", IntentKind.MCP, "approval_query"), 0.9));

        assertThat(NodeScoreFilters.kb(scores, 0.0))
                .isEqualTo(NodeScoreFilters.kb(scores));
    }

    @Test
    void mcpReturnsOnlyMcpKindWithToolId() {
        NodeScore mcp = score(node("mcp", IntentKind.MCP, "approval_query"), 0.9);
        NodeScore blankTool = score(node("blank-mcp", IntentKind.MCP, " "), 0.8);
        NodeScore kb = score(node("kb", IntentKind.KB, null), 0.7);

        assertThat(NodeScoreFilters.mcp(List.of(mcp, blankTool, kb)))
                .containsExactly(mcp);
    }

    @Test
    void mcpFiltersOutNullNode() {
        NodeScore nullNode = score(null, 0.9);
        NodeScore mcp = score(node("mcp", IntentKind.MCP, "approval_query"), 0.8);

        assertThat(NodeScoreFilters.mcp(List.of(nullNode, mcp)))
                .containsExactly(mcp);
    }

    @Test
    void mcpWithMinScoreDropsBelowGate() {
        NodeScore low = score(node("low-mcp", IntentKind.MCP, "approval_query"), 0.34);
        NodeScore high = score(node("high-mcp", IntentKind.MCP, "approval_query"), 0.35);

        assertThat(NodeScoreFilters.mcp(List.of(low, high), 0.35))
                .containsExactly(high);
    }

    @Test
    void bothOverloadsEmptyInputReturnEmpty() {
        assertThat(NodeScoreFilters.kb(List.of())).isEmpty();
        assertThat(NodeScoreFilters.kb(List.of(), 0.35)).isEmpty();
        assertThat(NodeScoreFilters.mcp(List.of())).isEmpty();
        assertThat(NodeScoreFilters.mcp(List.of(), 0.35)).isEmpty();
    }

    @Test
    void bothOverloadsNullInputReturnEmpty() {
        assertThat(NodeScoreFilters.kb(null)).isEmpty();
        assertThat(NodeScoreFilters.kb(null, 0.35)).isEmpty();
        assertThat(NodeScoreFilters.mcp(null)).isEmpty();
        assertThat(NodeScoreFilters.mcp(null, 0.35)).isEmpty();
    }

    private static IntentNode node(String id, IntentKind kind, String mcpToolId) {
        return IntentNode.builder()
                .id(id)
                .kind(kind)
                .mcpToolId(mcpToolId)
                .build();
    }

    private static NodeScore score(IntentNode node, double score) {
        return NodeScore.builder()
                .node(node)
                .score(score)
                .build();
    }
}
