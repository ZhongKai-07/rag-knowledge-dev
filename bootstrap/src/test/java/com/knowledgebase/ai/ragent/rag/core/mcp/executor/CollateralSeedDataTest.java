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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralSeedDataTest {

    private final CollateralSeedData data = new CollateralSeedData();

    @Test
    void findField_exactHsbcIsdaCsaMta_returnsRecord() {
        Optional<CollateralSeedRecord> rec = data.findField("HSBC", "ISDA&CSA", "Minimum Transfer Amount");
        assertThat(rec).isPresent();
        assertThat(rec.get().value()).isEqualTo("US$250,000.00");
        assertThat(rec.get().docName()).isEqualTo("OTCD - ISDA & CSA in same doc.pdf");
        assertThat(rec.get().page()).isEqualTo("P23");
        assertThat(rec.get().sourceChunkId()).isEqualTo("demo-hsbc-isda-csa-mta-p23");
        assertThat(rec.get().sourceText()).contains("Minimum Transfer Amount");
    }

    @Test
    void findField_mtaSynonym_resolvesToCanonical() {
        Optional<CollateralSeedRecord> rec = data.findField("HSBC", "ISDA&CSA", "MTA");
        assertThat(rec).isPresent();
        assertThat(rec.get().value()).isEqualTo("US$250,000.00");
    }

    @Test
    void findAgreement_unknownAgreementType_returnsEmpty() {
        assertThat(data.findAgreement("HSBC", "GMRA")).isEmpty();
    }

    @Test
    void findAgreement_caseInsensitiveCounterpartyAlias() {
        assertThat(data.findAgreement("the hongkong and shanghai banking corporation limited", "ISDA&CSA"))
                .isPresent();
    }

    @Test
    void isHouseParty_recognisesHuataiAliases() {
        assertThat(data.isHouseParty("华泰")).isTrue();
        assertThat(data.isHouseParty("HT")).isTrue();
        assertThat(data.isHouseParty("Huatai")).isTrue();
        assertThat(data.isHouseParty("HUATAI CAPITAL INVESTMENT LIMITED")).isTrue();
        assertThat(data.isHouseParty("HSBC")).isFalse();
    }

    @Test
    void normalizeAgreementType_collapsesIsdaCsaSpacings() {
        assertThat(data.normalizeAgreementType("ISDA & CSA")).isEqualTo("ISDA&CSA");
        assertThat(data.normalizeAgreementType("ISDA-CSA")).isEqualTo("ISDA&CSA");
        assertThat(data.normalizeAgreementType("ISDA+CSA")).isEqualTo("ISDA&CSA");
        assertThat(data.normalizeAgreementType("isda&csa")).isEqualTo("ISDA&CSA");
    }

    @Test
    void blankOrNullInputs_returnSafeDefaults() {
        assertThat(data.resolveCounterparty("")).isEmpty();
        assertThat(data.resolveCounterparty(null)).isEmpty();
        assertThat(data.isHouseParty("")).isFalse();
        assertThat(data.isHouseParty(null)).isFalse();
        assertThat(data.normalizeAgreementType("")).isEqualTo("");
        assertThat(data.normalizeAgreementType(null)).isEqualTo("");
        assertThat(data.resolveField("")).isEmpty();
        assertThat(data.resolveField(null)).isEmpty();
        assertThat(data.findAgreement(null, "ISDA&CSA")).isEmpty();
        assertThat(data.findAgreement("HSBC", null)).isEmpty();
        assertThat(data.findField(null, "ISDA&CSA", "MTA")).isEmpty();
        assertThat(data.findField("HSBC", null, "MTA")).isEmpty();
        assertThat(data.findField("HSBC", "ISDA&CSA", null)).isEmpty();
    }
}
