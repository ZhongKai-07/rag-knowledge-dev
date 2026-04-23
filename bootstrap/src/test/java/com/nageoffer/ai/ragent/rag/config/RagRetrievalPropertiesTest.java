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

package com.nageoffer.ai.ragent.rag.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagRetrievalPropertiesTest {

    @Test
    void validConfigPasses() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        props.setRecallTopK(30);
        props.setRerankTopK(10);
        props.validate(); // throws IllegalStateException if invalid

        assertThat(props.getRecallTopK()).isEqualTo(30);
        assertThat(props.getRerankTopK()).isEqualTo(10);
    }

    @Test
    void recallLessThanRerankThrows() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        props.setRecallTopK(5);
        props.setRerankTopK(10);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recall-top-k")
                .hasMessageContaining("rerank-top-k");
    }

    @Test
    void zeroOrNegativeValuesThrow() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        props.setRecallTopK(0);
        props.setRerankTopK(10);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);

        props.setRecallTopK(30);
        props.setRerankTopK(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
