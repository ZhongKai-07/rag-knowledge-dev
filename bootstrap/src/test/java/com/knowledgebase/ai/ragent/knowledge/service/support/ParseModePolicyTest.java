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

package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParseModePolicyTest {

    private final ParseModePolicy policy = new ParseModePolicy();

    @Test
    void nullRequest_defaultsToBasic() {
        ParseModeDecision d = policy.decide("kb-1", null);
        assertEquals(ParseMode.BASIC, d.parseMode());
        assertEquals("default_basic", d.reason());
    }

    @Test
    void userChoiceBasic_honored() {
        ParseModeDecision d = policy.decide("kb-1", ParseMode.BASIC);
        assertEquals(ParseMode.BASIC, d.parseMode());
        assertEquals("user_choice", d.reason());
    }

    @Test
    void userChoiceEnhanced_honored() {
        ParseModeDecision d = policy.decide("kb-1", ParseMode.ENHANCED);
        assertEquals(ParseMode.ENHANCED, d.parseMode());
        assertEquals("user_choice", d.reason());
    }

    @Test
    void noKbForcedEnhanced_evenForCollateralLikeIds() {
        // Phase 2.5 MVP: backend does not force ENHANCED on any KB; user always chooses.
        ParseModeDecision d = policy.decide("collateral-kb", ParseMode.BASIC);
        assertEquals(ParseMode.BASIC, d.parseMode());
    }
}
