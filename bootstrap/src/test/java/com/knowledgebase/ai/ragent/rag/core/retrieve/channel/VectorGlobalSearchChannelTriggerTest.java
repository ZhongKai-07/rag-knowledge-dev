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

package com.knowledgebase.ai.ragent.rag.core.retrieve.channel;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.rag.config.SearchChannelProperties;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.retrieve.RetrieverService;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.MetadataFilterBuilder;
import com.knowledgebase.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class VectorGlobalSearchChannelTriggerTest {

    @Mock
    private RetrieverService retrieverService;

    @Mock
    private KbMetadataReader kbMetadataReader;

    @Mock
    private MetadataFilterBuilder metadataFilterBuilder;

    private VectorGlobalSearchChannel channel;

    @BeforeEach
    void setUp() {
        SearchChannelProperties properties = new SearchChannelProperties();
        Executor directExecutor = Runnable::run;
        channel = new VectorGlobalSearchChannel(
                retrieverService,
                properties,
                kbMetadataReader,
                metadataFilterBuilder,
                directExecutor
        );
    }

    @Test
    void singleIntentBelowSupplementThresholdEnablesChannel() {
        SearchContext context = buildContext(List.of(intent("question", 0.7)));

        assertThat(channel.isEnabled(context)).isTrue();
    }

    @Test
    void singleIntentAtOrAboveSupplementThresholdDisablesChannel() {
        SearchContext atThreshold = buildContext(List.of(intent("question", 0.8)));
        SearchContext aboveThreshold = buildContext(List.of(intent("question", 0.85)));

        assertThat(channel.isEnabled(atThreshold)).isFalse();
        assertThat(channel.isEnabled(aboveThreshold)).isFalse();
    }

    @Test
    void multipleIntentsBelowSupplementThresholdDisablesChannel() {
        SearchContext context = buildContext(List.of(
                intent("question one", 0.75),
                intent("question two", 0.7)
        ));

        assertThat(channel.isEnabled(context)).isFalse();
    }

    private SearchContext buildContext(List<SubQuestionIntent> intents) {
        return SearchContext.builder()
                .originalQuestion("question")
                .rewrittenQuestion("question")
                .intents(intents)
                .recallTopK(5)
                .rerankTopK(3)
                .accessScope(AccessScope.ids(Set.of("kb-1")))
                .kbSecurityLevels(Map.of("kb-1", 1))
                .build();
    }

    private SubQuestionIntent intent(String subQuestion, double score) {
        IntentNode node = IntentNode.builder()
                .id(subQuestion)
                .kbId("kb-1")
                .collectionName("collection_1")
                .build();
        return new SubQuestionIntent(subQuestion, List.of(new NodeScore(node, score)));
    }
}
