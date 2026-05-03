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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import com.knowledgebase.ai.ragent.rag.core.mcp.DefaultMCPToolRegistry;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralRegistryWiringTest {

    @Test
    void defaultRegistry_autoRegistersCollateralExecutor() {
        CollateralFieldLookupMCPExecutor executor =
                new CollateralFieldLookupMCPExecutor(new CollateralSeedData(), new CollateralFieldLookupSlotRouter());
        DefaultMCPToolRegistry registry = new DefaultMCPToolRegistry(List.of(executor));

        registry.init();

        assertThat(registry.contains("collateral_field_lookup")).isTrue();
        Optional<MCPToolExecutor> resolved = registry.getExecutor("collateral_field_lookup");
        assertThat(resolved).isPresent();

        MCPTool tool = resolved.get().getToolDefinition();
        assertThat(tool.getParameters()).containsOnlyKeys("counterparty", "agreementType", "fieldName");
    }
}
