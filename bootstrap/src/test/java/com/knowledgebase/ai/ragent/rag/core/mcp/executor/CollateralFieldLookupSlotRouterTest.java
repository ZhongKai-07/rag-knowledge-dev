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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralFieldLookupSlotRouterTest {

    private static final String TOOL_ID = "collateral_field_lookup";

    private final CollateralFieldLookupSlotRouter router = new CollateralFieldLookupSlotRouter();

    private MCPRequest request(String counterparty, String agreementType, String fieldName) {
        Map<String, Object> params = new HashMap<>();
        if (counterparty != null) {
            params.put("counterparty", counterparty);
        }
        if (agreementType != null) {
            params.put("agreementType", agreementType);
        }
        if (fieldName != null) {
            params.put("fieldName", fieldName);
        }
        return MCPRequest.builder()
                .toolId(TOOL_ID)
                .userQuestion("dummy question")
                .parameters(params)
                .build();
    }

    @Test
    void route_threeRawSlots_proceedsToExecutor() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", "ISDA&CSA", "MTA"));
        assertThat(resp).isEmpty();
    }

    @Test
    void route_threeRawSlotsIncludingUnknownField_stillProceeds() {
        // raw nonblank 计数：unknown field 仍计为槽位，让 executor 走 B' 档
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", "ISDA&CSA", "unknown field"));
        assertThat(resp).isEmpty();
    }

    @Test
    void route_twoSlots_missAgreementType_returnsCText() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", null, "MTA"));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("请补充更多信息以定位文档")
                .contains("Please provide Agreement Type");
    }

    @Test
    void route_oneSlot_returnsCTextWithTwoMissingLabels() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request(null, null, "MTA"));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("Please provide Agreement Type or Counterparty");
    }

    @Test
    void route_oneSlot_counterpartyOnly_returnsCText() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("HSBC", null, null));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("Please provide Agreement Type or Field Name");
    }

    @Test
    void route_zeroSlots_returnsDFixedText() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request(null, null, null));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult())
                .contains("请提供更具体的协议筛查条件")
                .contains("Counterparty")
                .contains("Agreement Type")
                .contains("Field Name");
    }

    @Test
    void route_blankStrings_countAsZeroSlots() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request("", "  ", "\t"));
        assertThat(resp).isPresent();
        assertThat(resp.get().getTextResult()).contains("请提供更具体的协议筛查条件");
    }

    @Test
    void route_responseToolIdMatches() {
        Optional<MCPResponse> resp = router.routeBeforeExecutor(request(null, null, null));
        assertThat(resp).isPresent();
        assertThat(resp.get().getToolId()).isEqualTo(TOOL_ID);
        assertThat(resp.get().isSuccess()).isTrue();
    }
}
