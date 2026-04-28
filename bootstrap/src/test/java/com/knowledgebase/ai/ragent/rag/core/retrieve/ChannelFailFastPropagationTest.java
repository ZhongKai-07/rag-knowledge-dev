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

package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.rag.config.SearchChannelProperties;
import com.knowledgebase.ai.ragent.rag.core.intent.IntentNode;
import com.knowledgebase.ai.ragent.rag.core.intent.NodeScore;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.IntentDirectedSearchChannel;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.VectorGlobalSearchChannel;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.strategy.IntentParallelRetriever;
import com.knowledgebase.ai.ragent.rag.core.retrieve.filter.DefaultMetadataFilterBuilder;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PR5 finalize (I-1) — channel 层 fan-out 路径必须把 retriever 抛出的
 * {@link IllegalStateException}（QSI-3 c2 fail-fast 契约违规）传播到调用栈,
 * 不能被 {@code catch (Exception)} 吞成 ERROR log + empty list/result.
 *
 * <p>三个用例分别锁住:
 * <ul>
 *   <li>{@link IntentParallelRetriever#executeParallelRetrieval} 里
 *       {@link java.util.concurrent.CompletableFuture#join()} unwrap 后 rethrow</li>
 *   <li>{@link VectorGlobalSearchChannel#search}（间接走
 *       {@link com.knowledgebase.ai.ragent.rag.core.retrieve.channel.strategy.CollectionParallelRetriever}）
 *       外层 catch 显式 rethrow</li>
 *   <li>{@link IntentDirectedSearchChannel#search} 外层 catch 显式 rethrow</li>
 * </ul>
 *
 * <p>关键设计：使用真实 {@link DefaultMetadataFilterBuilder} + direct executor,
 * 让 fan-out 同步执行, 避免异步 / future unwrap 的不确定性。
 */
@ExtendWith(MockitoExtension.class)
class ChannelFailFastPropagationTest {

    private static final IllegalStateException CONTRACT_VIOLATION = new IllegalStateException(
            "filter contract violated for collection=test, missing kb_id IN filter");

    @Mock
    private RetrieverService retrieverService;

    @Mock
    private KbMetadataReader kbMetadataReader;

    private final Executor directExecutor = Runnable::run;
    private final DefaultMetadataFilterBuilder filterBuilder = new DefaultMetadataFilterBuilder();

    @BeforeEach
    void stubRetrieverThrowsContractViolation() {
        when(retrieverService.retrieve(any(RetrieveRequest.class)))
                .thenThrow(CONTRACT_VIOLATION);
    }

    @Test
    void intentParallelRetriever_propagatesIllegalStateException() {
        IntentParallelRetriever retriever = new IntentParallelRetriever(
                retrieverService, filterBuilder, directExecutor);

        IntentNode node = IntentNode.builder()
                .id("intent-1")
                .kbId("kb-1")
                .name("intent-1")
                .collectionName("collection_1")
                .build();
        NodeScore score = NodeScore.builder().node(node).score(0.9).build();

        SearchContext context = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .intents(List.of(new SubQuestionIntent("q", List.of())))
                .recallTopK(5)
                .rerankTopK(3)
                .accessScope(AccessScope.ids(Set.of("kb-1")))
                .kbSecurityLevels(Map.of("kb-1", 1))
                .build();

        assertThatThrownBy(() -> retriever.executeParallelRetrieval("q", List.of(score), 5, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filter contract violated");
    }

    @Test
    void vectorGlobalSearchChannel_propagatesIllegalStateException() {
        VectorGlobalSearchChannel channel = new VectorGlobalSearchChannel(
                retrieverService,
                new SearchChannelProperties(),
                kbMetadataReader,
                filterBuilder,
                directExecutor);

        Set<String> visibleKbIds = Set.of("kb-1");
        when(kbMetadataReader.listAllKbIds()).thenReturn(visibleKbIds);
        when(kbMetadataReader.getCollectionNames(visibleKbIds))
                .thenReturn(Map.of("kb-1", "collection_1"));

        SearchContext context = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .intents(List.of(new SubQuestionIntent("q", List.of())))
                .recallTopK(5)
                .rerankTopK(3)
                .accessScope(AccessScope.all())
                .kbSecurityLevels(Map.of("kb-1", 1))
                .build();

        assertThatThrownBy(() -> channel.search(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filter contract violated");
    }

    @Test
    void intentDirectedSearchChannel_propagatesIllegalStateException() {
        IntentDirectedSearchChannel channel = new IntentDirectedSearchChannel(
                retrieverService,
                new SearchChannelProperties(),
                filterBuilder,
                directExecutor);

        IntentNode node = IntentNode.builder()
                .id("intent-1")
                .kbId("kb-1")
                .name("intent-1")
                .collectionName("collection_1")
                .kind(com.knowledgebase.ai.ragent.rag.enums.IntentKind.KB)
                .build();
        NodeScore score = NodeScore.builder().node(node).score(0.9).build();

        SearchContext context = SearchContext.builder()
                .originalQuestion("q")
                .rewrittenQuestion("q")
                .intents(List.of(new SubQuestionIntent("q", List.of(score))))
                .recallTopK(5)
                .rerankTopK(3)
                .accessScope(AccessScope.ids(Set.of("kb-1")))
                .kbSecurityLevels(Map.of("kb-1", 1))
                .build();

        assertThatThrownBy(() -> channel.search(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filter contract violated");
    }
}
