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

package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalScopeTest {

    @Test
    void empty_returns_empty_ids_and_no_target() {
        RetrievalScope scope = RetrievalScope.empty();

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.Ids.class);
        assertThat(((AccessScope.Ids) scope.accessScope()).kbIds()).isEmpty();
        assertThat(scope.kbSecurityLevels()).isEmpty();
        assertThat(scope.targetKbId()).isNull();
        assertThat(scope.isSingleKb()).isFalse();
    }

    @Test
    void all_returns_sentinel_with_targetKbId() {
        RetrievalScope scope = RetrievalScope.all("kb-1");

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.All.class);
        assertThat(scope.kbSecurityLevels()).isEmpty();
        assertThat(scope.targetKbId()).isEqualTo("kb-1");
        assertThat(scope.isSingleKb()).isTrue();
    }

    @Test
    void all_with_null_targetKbId_supports_multi_kb_super_admin() {
        RetrievalScope scope = RetrievalScope.all(null);

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.All.class);
        assertThat(scope.isSingleKb()).isFalse();
    }

    @Test
    void constructor_null_accessScope_throws() {
        assertThatThrownBy(() -> new RetrievalScope(null, Map.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("accessScope");
    }

    @Test
    void constructor_isolates_mutable_map() {
        Map<String, Integer> mutable = new HashMap<>();
        mutable.put("kb-1", 2);

        RetrievalScope scope = new RetrievalScope(AccessScope.empty(), mutable, null);
        mutable.put("kb-2", 3);

        assertThat(scope.kbSecurityLevels()).containsOnlyKeys("kb-1");
        assertThatThrownBy(() -> scope.kbSecurityLevels().put("kb-3", 4))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructor_null_levels_normalized_to_empty_map() {
        RetrievalScope scope = new RetrievalScope(AccessScope.empty(), null, null);

        assertThat(scope.kbSecurityLevels()).isNotNull().isEmpty();
    }

    @Test
    void constructor_isolates_mutable_kb_ids_set() {
        Set<String> mutableIds = new HashSet<>();
        mutableIds.add("kb-1");
        AccessScope unsafe = AccessScope.ids(mutableIds);

        RetrievalScope scope = new RetrievalScope(unsafe, Map.of(), null);
        mutableIds.add("kb-2");

        AccessScope.Ids frozen = (AccessScope.Ids) scope.accessScope();
        assertThat(frozen.kbIds()).containsOnly("kb-1");
        assertThatThrownBy(() -> frozen.kbIds().add("kb-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
