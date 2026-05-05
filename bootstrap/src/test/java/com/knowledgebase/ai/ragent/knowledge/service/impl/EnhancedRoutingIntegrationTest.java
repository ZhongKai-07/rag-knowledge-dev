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

package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import com.knowledgebase.ai.ragent.knowledge.enums.ProcessMode;
import com.knowledgebase.ai.ragent.knowledge.service.support.ParseModeDecision;
import com.knowledgebase.ai.ragent.knowledge.service.support.ParseModePolicy;
import com.knowledgebase.ai.ragent.knowledge.service.support.ParseModeRouter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Light-weight integration test that locks the upload-side contract:
 * regardless of what the legacy frontend sends as {@code processMode}, the effective
 * {@link ProcessMode} is derived from {@link ParseMode} via
 * {@link ParseModePolicy} → {@link ParseModeRouter}.
 *
 * <p>This is the same chain {@code KnowledgeDocumentServiceImpl.upload(...)} runs;
 * a focused unit test here keeps regressions visible without booting Spring.</p>
 */
class EnhancedRoutingIntegrationTest {

    private final ParseModePolicy policy = new ParseModePolicy();
    private final ParseModeRouter router = new ParseModeRouter();

    @Test
    void enhancedNeverYieldsChunkRegardlessOfFrontendProcessMode() {
        ParseModeDecision decision = policy.decide("any-kb", ParseMode.ENHANCED);
        ProcessMode resolved = router.resolve(decision.parseMode());
        assertEquals(ProcessMode.PIPELINE, resolved);
        assertNotEquals(ProcessMode.CHUNK, resolved);
    }

    @Test
    void basicYieldsChunk_whenUserChooseBasic() {
        ParseModeDecision decision = policy.decide("any-kb", ParseMode.BASIC);
        assertEquals(ProcessMode.CHUNK, router.resolve(decision.parseMode()));
    }

    @Test
    void nullRequest_yieldsChunkAsBasicDefault() {
        ParseModeDecision decision = policy.decide("any-kb", null);
        assertEquals(ProcessMode.CHUNK, router.resolve(decision.parseMode()));
    }
}
