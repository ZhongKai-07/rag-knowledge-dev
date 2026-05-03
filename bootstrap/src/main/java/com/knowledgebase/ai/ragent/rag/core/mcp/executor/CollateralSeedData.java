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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Collateral 字段筛查 MVP 静态 seed 数据。
 * <p>
 * MVP 阶段仅服务 PRD 样例：HSBC × ISDA&CSA × Minimum Transfer Amount。
 * 替换为真实数据前必须先实现 KB-scoped 意图过滤 + KbReadAccessPort 校验
 * （见 spec：2026-05-03-collateral-mcp-mvp-design.md「硬前置门」）。
 */
@Component
public class CollateralSeedData {

    private static final String CANONICAL_HSBC = "HSBC";
    private static final String CANONICAL_ISDA_CSA = "ISDA&CSA";
    private static final String CANONICAL_MTA = "Minimum Transfer Amount";

    private static final Map<String, String> COUNTERPARTY_ALIAS_TO_CANONICAL = Map.of(
            "hsbc", CANONICAL_HSBC,
            "the hongkong and shanghai banking corporation limited", CANONICAL_HSBC
    );

    private static final Set<String> HOUSE_PARTY_ALIASES = Set.of(
            "华泰", "ht", "huatai", "huatai capital investment limited"
    );

    private static final Map<String, String> FIELD_ALIAS_TO_CANONICAL = Map.of(
            "minimum transfer amount", CANONICAL_MTA,
            "mta", CANONICAL_MTA
    );

    private static final List<CollateralSeedRecord> RECORDS = List.of(
            new CollateralSeedRecord(
                    CANONICAL_HSBC,
                    CANONICAL_ISDA_CSA,
                    CANONICAL_MTA,
                    "US$250,000.00",
                    "OTCD - ISDA & CSA in same doc.pdf",
                    "P23",
                    "demo-hsbc-isda-csa-mta-p23",
                    "\"Minimum Transfer Amount\" means with respect to Party A: US$250,000.00 ..."
            )
    );

    public Optional<String> resolveCounterparty(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(COUNTERPARTY_ALIAS_TO_CANONICAL.get(key));
    }

    public boolean isHouseParty(String raw) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        return HOUSE_PARTY_ALIASES.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    public String normalizeAgreementType(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        // 中文连接词 "和" 归一化为 "&"：query rewriter 会把 "ISDA&CSA" 改写成 "ISDA和CSA"，
        // 必须在 executor 端再吸收一次，否则 LLM 抽参后的 agreementType 永远不命中 seed canonical。
        return raw.replaceAll("\\s+", "")
                .replace("-", "&")
                .replace("+", "&")
                .replace("和", "&")
                .toUpperCase(Locale.ROOT);
    }

    public Optional<String> resolveField(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(FIELD_ALIAS_TO_CANONICAL.get(key));
    }

    public Optional<CollateralSeedRecord> findAgreement(String counterpartyRaw, String agreementTypeRaw) {
        Optional<String> cp = resolveCounterparty(counterpartyRaw);
        if (cp.isEmpty()) {
            return Optional.empty();
        }
        String at = normalizeAgreementType(agreementTypeRaw);
        return RECORDS.stream()
                .filter(r -> r.counterpartyCanonical().equalsIgnoreCase(cp.get()))
                .filter(r -> r.agreementTypeCanonical().equalsIgnoreCase(at))
                .findFirst();
    }

    public Optional<CollateralSeedRecord> findField(String counterpartyRaw, String agreementTypeRaw, String fieldRaw) {
        Optional<CollateralSeedRecord> agreement = findAgreement(counterpartyRaw, agreementTypeRaw);
        if (agreement.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> field = resolveField(fieldRaw);
        if (field.isEmpty()) {
            return Optional.empty();
        }
        return RECORDS.stream()
                .filter(r -> r.counterpartyCanonical().equalsIgnoreCase(agreement.get().counterpartyCanonical()))
                .filter(r -> r.agreementTypeCanonical().equalsIgnoreCase(agreement.get().agreementTypeCanonical()))
                .filter(r -> r.fieldCanonical().equalsIgnoreCase(field.get()))
                .findFirst();
    }
}
