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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collateral 字段筛查槽位分流器（spec：四档文案 v2）。
 * <ul>
 *   <li>3 槽位（raw nonblank） → 返回 empty，由 executor 继续判定 A/B/B'。</li>
 *   <li>1 或 2 槽位 → C 档：拼接缺失槽位英文标签。</li>
 *   <li>0 槽位 → D 档：返回固定通用引导文案，不回流 IntentGuidanceService。</li>
 * </ul>
 * 不在本类做 fieldName 同义词匹配；同义词归一化与 unknown field 判定在 executor 内。
 */
@Component
public class CollateralFieldLookupSlotRouter {

    static final String TOOL_ID = "collateral_field_lookup";

    static final String LABEL_COUNTERPARTY = "Counterparty";
    static final String LABEL_AGREEMENT_TYPE = "Agreement Type";
    static final String LABEL_FIELD_NAME = "Field Name";

    static final String D_TEXT =
            "请提供更具体的协议筛查条件，例如交易对手方（Counterparty）、协议类型（Agreement Type）以及您想查询的字段（Field Name）。";

    public Optional<MCPResponse> routeBeforeExecutor(MCPRequest request) {
        String counterparty = request.getStringParameter("counterparty");
        String agreementType = request.getStringParameter("agreementType");
        String fieldName = request.getStringParameter("fieldName");

        List<String> missing = new ArrayList<>(3);
        if (StrUtil.isBlank(agreementType)) {
            missing.add(LABEL_AGREEMENT_TYPE);
        }
        if (StrUtil.isBlank(counterparty)) {
            missing.add(LABEL_COUNTERPARTY);
        }
        if (StrUtil.isBlank(fieldName)) {
            missing.add(LABEL_FIELD_NAME);
        }

        if (missing.isEmpty()) {
            return Optional.empty();
        }

        if (missing.size() == 3) {
            return Optional.of(MCPResponse.success(TOOL_ID, D_TEXT));
        }

        String joined = String.join(" or ", missing);
        String text = "请补充更多信息以定位文档,例如:Please provide " + joined;
        return Optional.of(MCPResponse.success(TOOL_ID, text));
    }
}
