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

package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.FixedSizeOptions;
import com.knowledgebase.ai.ragent.core.chunk.TextBoundaryOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredChunkingOptionsResolverTest {

    @Test
    void textBoundaryOptions_passesThroughTargetMaxMin() {
        TextBoundaryOptions opt = new TextBoundaryOptions(1000, 100, 1500, 400);
        StructuredChunkingDimensions d = StructuredChunkingOptionsResolver.resolve(opt);
        assertThat(d.target()).isEqualTo(1000);
        assertThat(d.max()).isEqualTo(1500);
        assertThat(d.min()).isEqualTo(400);
    }

    @Test
    void fixedSizeOptions_derivesMaxAndMin() {
        FixedSizeOptions opt = new FixedSizeOptions(1000, 0);
        StructuredChunkingDimensions d = StructuredChunkingOptionsResolver.resolve(opt);
        assertThat(d.target()).isEqualTo(1000);
        assertThat(d.max()).isEqualTo(1300);   // 30% 弹性
        assertThat(d.min()).isEqualTo(400);    // 40% 下限
    }

    @Test
    void nullOrUnknown_fallsBackToStructuredDefaults() {
        StructuredChunkingDimensions d = StructuredChunkingOptionsResolver.resolve(null);
        assertThat(d.target()).isEqualTo(1400);
        assertThat(d.max()).isEqualTo(1800);
        assertThat(d.min()).isEqualTo(600);
    }
}
