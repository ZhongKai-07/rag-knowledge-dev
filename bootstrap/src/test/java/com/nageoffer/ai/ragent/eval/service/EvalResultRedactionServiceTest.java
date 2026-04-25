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

package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalResultRedactionServiceTest {

    private EvalResultRedactionService svc;

    @BeforeEach
    void setUp() {
        svc = new EvalResultRedactionService();
    }

    private RetrievedChunkSnapshot snap(String id, Integer level, String text) {
        return new RetrievedChunkSnapshot(id, "doc-" + id, "doc.pdf", level, text, 0.9);
    }

    @Test
    void super_admin_sees_all_text() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", 3, "secret"), snap("c2", 1, "public"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, Integer.MAX_VALUE);
        assertThat(out.get(0).text()).isEqualTo("secret");
        assertThat(out.get(1).text()).isEqualTo("public");
    }

    @Test
    void principal_below_ceiling_sees_text() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", 1, "public"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, 2);
        assertThat(out.get(0).text()).isEqualTo("public");
    }

    @Test
    void principal_above_ceiling_redacts_text() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", 3, "secret-doc"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, 2);
        assertThat(out.get(0).text()).isEqualTo("[REDACTED]");
        assertThat(out.get(0).chunkId()).isEqualTo("c1");
        assertThat(out.get(0).securityLevel()).isEqualTo(3);
        assertThat(out.get(0).docId()).isEqualTo("doc-c1");
    }

    @Test
    void null_security_level_treated_as_zero() {
        List<RetrievedChunkSnapshot> in = List.of(snap("c1", null, "open data"));
        List<RetrievedChunkSnapshot> out = svc.redact(in, 0);
        assertThat(out.get(0).text()).isEqualTo("open data");
    }

    @Test
    void null_input_returns_empty_list() {
        assertThat(svc.redact(null, 0)).isEmpty();
    }
}
