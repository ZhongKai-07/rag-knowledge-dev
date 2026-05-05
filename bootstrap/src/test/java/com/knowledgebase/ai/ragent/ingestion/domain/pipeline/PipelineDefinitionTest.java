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

package com.knowledgebase.ai.ragent.ingestion.domain.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineDefinitionTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void withParserNodeSetting_injectsKeyOnParserNodeOnly() {
        ObjectNode parserSettings = om.createObjectNode();
        parserSettings.put("foo", "bar");

        PipelineDefinition def = PipelineDefinition.builder()
                .id("p1")
                .name("test")
                .nodes(List.of(
                        NodeConfig.builder().nodeId("fetch").nodeType("fetcher").nextNodeId("parse").build(),
                        NodeConfig.builder().nodeId("parse").nodeType("parser").settings(parserSettings).nextNodeId("chunk").build(),
                        NodeConfig.builder().nodeId("chunk").nodeType("chunker").nextNodeId("index").build(),
                        NodeConfig.builder().nodeId("index").nodeType("indexer").build()))
                .build();

        PipelineDefinition mutated = def.withParserNodeSetting("parseMode", "enhanced");

        assertNotSame(def, mutated, "should return a new instance");
        // original parser settings unchanged
        assertNull(parserSettings.get("parseMode"), "original settings must not be mutated");
        assertEquals("bar", parserSettings.get("foo").asText(), "original settings must not be mutated");

        // new instance has parseMode injected on parser node
        NodeConfig parser = mutated.getNodes().stream()
                .filter(n -> "parser".equals(n.getNodeType()))
                .findFirst()
                .orElseThrow();
        assertNotNull(parser.getSettings());
        assertEquals("enhanced", parser.getSettings().get("parseMode").asText());
        // foo preserved on copy
        assertEquals("bar", parser.getSettings().get("foo").asText());

        // non-parser nodes untouched
        for (NodeConfig n : mutated.getNodes()) {
            if (!"parser".equals(n.getNodeType())) {
                assertTrue(n.getSettings() == null, "non-parser node settings should remain null");
            }
        }
    }

    @Test
    void withParserNodeSetting_createsSettingsWhenNull() {
        PipelineDefinition def = PipelineDefinition.builder()
                .nodes(List.of(NodeConfig.builder().nodeId("p").nodeType("parser").build()))
                .build();

        PipelineDefinition mutated = def.withParserNodeSetting("parseMode", "basic");
        assertEquals("basic", mutated.getNodes().get(0).getSettings().get("parseMode").asText());
    }

    @Test
    void withParserNodeSetting_emptyOrNullNodes_returnsSelf() {
        PipelineDefinition empty = PipelineDefinition.builder().nodes(List.of()).build();
        assertEquals(empty, empty.withParserNodeSetting("parseMode", "enhanced"));

        PipelineDefinition nullNodes = PipelineDefinition.builder().build();
        assertEquals(nullNodes, nullNodes.withParserNodeSetting("parseMode", "enhanced"));
    }
}
