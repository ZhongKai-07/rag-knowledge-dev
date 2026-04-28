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

package com.knowledgebase.ai.ragent.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagVectorTypeValidatorTest {

    @Test
    void opensearchWithIncompleteBackendDisabledDoesNotThrow() {
        RagVectorTypeValidator validator = validator("opensearch", false);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void milvusWithIncompleteBackendDisabledThrows() {
        RagVectorTypeValidator validator = validator("milvus", false);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("milvus")
                .hasMessageContaining("allow-incomplete-backend");
    }

    @Test
    void milvusWithIncompleteBackendEnabledDoesNotThrow() {
        RagVectorTypeValidator validator = validator("milvus", true);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void pgWithIncompleteBackendEnabledDoesNotThrow() {
        RagVectorTypeValidator validator = validator("pg", true);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void blankWithIncompleteBackendEnabledThrows() {
        RagVectorTypeValidator validator = validator("  ", true);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rag.vector.type");
    }

    @Test
    void unknownWithIncompleteBackendEnabledThrows() {
        RagVectorTypeValidator validator = validator("pgvector", true);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pgvector")
                .hasMessageContaining("opensearch");
    }

    private RagVectorTypeValidator validator(String vectorType, boolean allowIncomplete) {
        RagVectorTypeValidator validator = new RagVectorTypeValidator();
        ReflectionTestUtils.setField(validator, "vectorType", vectorType);
        ReflectionTestUtils.setField(validator, "allowIncomplete", allowIncomplete);
        return validator;
    }
}
