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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchContextBuilderTest {

    @Test
    void validBuildsContextWithBothTopK() {
        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .recallTopK(30)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        assertThat(ctx.getRecallTopK()).isEqualTo(30);
        assertThat(ctx.getRerankTopK()).isEqualTo(10);
    }

    @Test
    void equalTopKIsAllowed() {
        SearchContext ctx = SearchContext.builder()
                .originalQuestion("q")
                .recallTopK(10)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build();

        assertThat(ctx.getRecallTopK()).isEqualTo(10);
        assertThat(ctx.getRerankTopK()).isEqualTo(10);
    }

    @Test
    void recallLessThanRerankThrowsIAE() {
        assertThatThrownBy(() -> SearchContext.builder()
                .originalQuestion("q")
                .recallTopK(5)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recallTopK")
                .hasMessageContaining("rerankTopK");
    }

    @Test
    void zeroTopKThrowsIAE() {
        assertThatThrownBy(() -> SearchContext.builder()
                .originalQuestion("q")
                .recallTopK(0)
                .rerankTopK(10)
                .accessScope(AccessScope.all())
                .build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SearchContext.builder()
                .originalQuestion("q")
                .recallTopK(30)
                .rerankTopK(0)
                .accessScope(AccessScope.all())
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
