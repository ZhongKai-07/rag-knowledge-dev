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

import com.knowledgebase.ai.ragent.rag.core.intent.IntentResolver;
import com.knowledgebase.ai.ragent.rag.core.memory.DefaultConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.memory.JdbcConversationMemorySummaryService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.IntentDirectedSearchChannel;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.VectorGlobalSearchChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutorInjectionQualifierTest {

    @Test
    void executorDependenciesUseExplicitQualifiers() {
        List<InjectionPoint> points = List.of(
                new InjectionPoint(IntentResolver.class, 1, "intentClassifyExecutor"),
                new InjectionPoint(RetrievalEngine.class, 5, "ragContextExecutor"),
                new InjectionPoint(RetrievalEngine.class, 6, "mcpBatchExecutor"),
                new InjectionPoint(MultiChannelRetrievalEngine.class, 5, "ragRetrievalExecutor"),
                new InjectionPoint(IntentDirectedSearchChannel.class, 3, "innerRetrievalExecutor"),
                new InjectionPoint(VectorGlobalSearchChannel.class, 4, "innerRetrievalExecutor"),
                new InjectionPoint(DefaultConversationMemoryService.class, 2, "memoryLoadExecutor"),
                new InjectionPoint(JdbcConversationMemorySummaryService.class, 6, "memorySummaryExecutor")
        );

        assertThat(points).allSatisfy(point -> {
            Constructor<?> constructor = constructorFor(point);
            Qualifier qualifier = qualifierAt(constructor, point.parameterIndex());

            assertThat(qualifier)
                    .as("%s constructor parameter %d", point.owner().getSimpleName(), point.parameterIndex())
                    .isNotNull();
            assertThat(qualifier.value()).isEqualTo(point.beanName());
        });
    }

    private static Constructor<?> constructorFor(InjectionPoint point) {
        return Arrays.stream(point.owner().getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() > point.parameterIndex())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
    }

    private static Qualifier qualifierAt(Constructor<?> constructor, int parameterIndex) {
        Annotation[] annotations = constructor.getParameterAnnotations()[parameterIndex];
        return Arrays.stream(annotations)
                .filter(Qualifier.class::isInstance)
                .map(Qualifier.class::cast)
                .findFirst()
                .orElse(null);
    }

    private record InjectionPoint(Class<?> owner, int parameterIndex, String beanName) {
    }
}
