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

package com.knowledgebase.ai.ragent.rag.core.intent;

import com.knowledgebase.ai.ragent.rag.core.rewrite.RewriteResult;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class IntentResolverDegradationTest {

    private final Executor directExecutor = Runnable::run;

    @Test
    void resolveReturnsEmptyIntentForFailedSubQuestionClassification() {
        IntentClassifier classifier = question -> {
            throw new IllegalStateException("classifier unavailable");
        };
        IntentResolver resolver = new IntentResolver(classifier, directExecutor);

        List<SubQuestionIntent> result = resolver.resolve(
                new RewriteResult("rewritten question", List.of("failed sub-question")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).subQuestion()).isEqualTo("failed sub-question");
        assertThat(result.get(0).nodeScores()).isEmpty();
    }
}
