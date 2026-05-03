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

import com.knowledgebase.ai.ragent.rag.core.mcp.MCPRequest;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPResponse;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralFieldLookupMCPExecutorTest {

    private final CollateralFieldLookupMCPExecutor executor =
            new CollateralFieldLookupMCPExecutor(new CollateralSeedData(), new CollateralFieldLookupSlotRouter());

    private MCPRequest req(String userQuestion, String cp, String at, String fn) {
        Map<String, Object> params = new HashMap<>();
        if (cp != null) {
            params.put("counterparty", cp);
        }
        if (at != null) {
            params.put("agreementType", at);
        }
        if (fn != null) {
            params.put("fieldName", fn);
        }
        return MCPRequest.builder()
                .toolId("collateral_field_lookup")
                .userQuestion(userQuestion)
                .parameters(params)
                .build();
    }

    @Test
    void toolDefinition_exposesThreeRequiredParameters() {
        MCPTool tool = executor.getToolDefinition();
        assertThat(tool.getToolId()).isEqualTo("collateral_field_lookup");
        assertThat(tool.getParameters()).containsOnlyKeys("counterparty", "agreementType", "fieldName");
        assertThat(tool.getParameters().get("counterparty").isRequired()).isTrue();
        assertThat(tool.getParameters().get("agreementType").isRequired()).isTrue();
        assertThat(tool.getParameters().get("fieldName").isRequired()).isTrue();
    }

    @Test
    void supports_matchesByToolId() {
        assertThat(executor.supports(MCPRequest.builder().toolId("collateral_field_lookup").build())).isTrue();
        assertThat(executor.supports(MCPRequest.builder().toolId("other").build())).isFalse();
    }

    @Test
    void execute_aHit_returnsPartOnePartTwo() {
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少",
                "HSBC", "ISDA&CSA", "minimum transfer amount"));
        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getTextResult())
                .contains("Part 1:")
                .contains("Minimum Transfer Amount: US$250,000.00")
                .contains("Part 2:")
                .contains("OTCD - ISDA & CSA in same doc.pdf")
                .contains("P23")
                .contains("source_chunk_id=demo-hsbc-isda-csa-mta-p23")
                .contains("\"Minimum Transfer Amount\"");
    }

    @Test
    void execute_aHit_mtaSynonym() {
        MCPResponse resp = executor.execute(req(
                "HSBC 的 ISDA&CSA 下 MTA 是多少", "HSBC", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_aHit_counterpartyAliasFullName() {
        MCPResponse resp = executor.execute(req(
                "The HongKong and Shanghai Banking Corporation Limited 的 ISDA&CSA 下 MTA",
                "The HongKong and Shanghai Banking Corporation Limited", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_aHit_agreementTypeNormalisation() {
        MCPResponse resp = executor.execute(req(
                "HSBC ISDA & CSA MTA", "HSBC", "ISDA & CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_houseAliasFallback_secondaryMatchSucceeds() {
        // LLM 把"华泰"塞进 counterparty。executor 回退到 userQuestion 找 HSBC 二次匹配
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的ISDA&CSA下的 MTA 是多少",
                "华泰", "ISDA&CSA", "MTA"));
        assertThat(resp.getTextResult()).contains("US$250,000.00");
    }

    @Test
    void execute_houseAliasFallback_secondaryMatchFails_returnsCounterpartyMissingText() {
        MCPResponse resp = executor.execute(req(
                "华泰自己的 ISDA&CSA 下 MTA 是多少",
                "华泰", "ISDA&CSA", "MTA"));
        // 二次仍找不到对手方 → 等价 counterparty 缺失
        assertThat(resp.getTextResult())
                .contains("请补充更多信息以定位文档")
                .contains("Please provide Counterparty");
    }

    @Test
    void execute_bMiss_unknownAgreement() {
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的GMRA下的 MTA 是多少", "HSBC", "GMRA", "MTA"));
        assertThat(resp.getTextResult())
                .contains("未找到与")
                .contains("GMRA")
                .contains("HSBC");
    }

    @Test
    void execute_bPrimeMiss_unknownField_butKnownAgreement() {
        MCPResponse resp = executor.execute(req(
                "华泰和HSBC交易的ISDA&CSA下的 unknown field 是什么",
                "HSBC", "ISDA&CSA", "unknown field"));
        assertThat(resp.getTextResult())
                .contains("已定位协议")
                .contains("未找到字段")
                .contains("unknown field");
    }

    @Test
    void execute_routerCMiss_returnsRouterText() {
        MCPResponse resp = executor.execute(req(
                "HSBC 的 MTA 是多少", "HSBC", null, "MTA"));
        assertThat(resp.getTextResult())
                .contains("请补充更多信息以定位文档")
                .contains("Please provide Agreement Type");
    }

    @Test
    void execute_routerDMiss_returnsRouterText() {
        MCPResponse resp = executor.execute(req(
                "这个协议是什么意思", null, null, null));
        assertThat(resp.getTextResult()).contains("请提供更具体的协议筛查条件");
    }
}
