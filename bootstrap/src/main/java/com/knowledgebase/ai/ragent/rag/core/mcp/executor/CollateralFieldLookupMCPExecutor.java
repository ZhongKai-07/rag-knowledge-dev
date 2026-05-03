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

import cn.hutool.core.util.StrUtil;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPRequest;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPResponse;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Collateral 字段筛查 MVP 本地 executor（spec：2026-05-03-collateral-mcp-mvp-design.md）。
 * <p>
 * execute() 顺序：
 * <ol>
 *   <li>SlotRouter 判 C/D，直接返回。</li>
 *   <li>我方别名（华泰/HT/...）回退：从 userQuestion 二次匹配 counterparty alias。</li>
 *   <li>findAgreement miss → B 档；findField miss → B' 档；命中 → A 档 Part1+Part2。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollateralFieldLookupMCPExecutor implements MCPToolExecutor {

    static final String TOOL_ID = "collateral_field_lookup";

    private final CollateralSeedData seedData;
    private final CollateralFieldLookupSlotRouter slotRouter;

    @Override
    public MCPTool getToolDefinition() {
        Map<String, MCPTool.ParameterDef> params = new LinkedHashMap<>();
        params.put("counterparty", MCPTool.ParameterDef.builder()
                .description("交易对手方，如 HSBC、The HongKong and Shanghai Banking Corporation Limited")
                .type("string").required(true).build());
        params.put("agreementType", MCPTool.ParameterDef.builder()
                .description("协议类型，如 ISDA&CSA、ISDA、CSA、GMRA")
                .type("string").required(true).build());
        params.put("fieldName", MCPTool.ParameterDef.builder()
                .description("业务字段，如 minimum transfer amount、MTA、Rounding")
                .type("string").required(true).build());
        return MCPTool.builder()
                .toolId(TOOL_ID)
                .description("Collateral 协议字段筛查：根据 counterparty + agreementType + fieldName 返回字段值与原文证据。")
                .parameters(params)
                .requireUserId(false)
                .build();
    }

    @Override
    public MCPResponse execute(MCPRequest request) {
        Optional<MCPResponse> routed = slotRouter.routeBeforeExecutor(request);
        if (routed.isPresent()) {
            return routed.get();
        }

        String counterpartyRaw = request.getStringParameter("counterparty");
        String agreementType = request.getStringParameter("agreementType");
        String fieldName = request.getStringParameter("fieldName");

        String effectiveCounterparty = resolveEffectiveCounterparty(counterpartyRaw, request.getUserQuestion());
        if (StrUtil.isBlank(effectiveCounterparty)) {
            return MCPResponse.success(TOOL_ID,
                    "请补充更多信息以定位文档,例如:Please provide Counterparty");
        }

        Optional<CollateralSeedRecord> agreement = seedData.findAgreement(effectiveCounterparty, agreementType);
        if (agreement.isEmpty()) {
            return MCPResponse.success(TOOL_ID,
                    "未找到与 " + agreementType + " + " + effectiveCounterparty + " 相关的文档");
        }

        Optional<CollateralSeedRecord> rec = seedData.findField(effectiveCounterparty, agreementType, fieldName);
        if (rec.isEmpty()) {
            return MCPResponse.success(TOOL_ID,
                    "已定位协议，但未找到字段 " + fieldName + " 的示例答案");
        }

        return MCPResponse.success(TOOL_ID, formatPartOnePartTwo(rec.get()));
    }

    private String resolveEffectiveCounterparty(String raw, String userQuestion) {
        if (StrUtil.isNotBlank(raw) && !seedData.isHouseParty(raw)) {
            return raw;
        }
        // 我方别名回退：从原问题中二次匹配 counterparty alias
        if (StrUtil.isBlank(userQuestion)) {
            return null;
        }
        String lower = userQuestion.toLowerCase(Locale.ROOT);
        if (lower.contains("hsbc")) {
            return "HSBC";
        }
        if (lower.contains("the hongkong and shanghai banking corporation limited")) {
            return "The HongKong and Shanghai Banking Corporation Limited";
        }
        return null;
    }

    private String formatPartOnePartTwo(CollateralSeedRecord r) {
        return "Part 1:\n"
                + r.fieldCanonical() + ": " + r.value() + "\n\n"
                + "Part 2:\n"
                + "Source: " + r.docName() + ", " + r.page() + ", source_chunk_id=" + r.sourceChunkId() + "\n"
                + "Original Text: " + r.sourceText();
    }
}
