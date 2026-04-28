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

package com.knowledgebase.ai.ragent.infra.rerank;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.enums.ModelCapability;
import com.knowledgebase.ai.ragent.infra.model.ModelRoutingExecutor;
import com.knowledgebase.ai.ragent.infra.model.ModelSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式重排服务实现类
 * <p>
 * 该服务通过模型路由机制动态选择合适的重排客户端，并支持失败降级策略
 * 作为主要的重排服务实现，用于对检索到的文档块进行相关性重新排序
 */
@Service
@Primary
@Slf4j
public class RoutingRerankService implements RerankService {

    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, RerankClient> clientsByProvider;

    public RoutingRerankService(ModelSelector selector, ModelRoutingExecutor executor, List<RerankClient> clients) {
        this.selector = selector;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity()));
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        log.info("[rerank-routing] entry: candidates={}, topN={}", candidates == null ? 0 : candidates.size(), topN);
        return executor.executeWithFallback(
                ModelCapability.RERANK,
                selector.selectRerankCandidates(),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> {
                    log.info("[rerank-routing] selected: provider={}, model={}, priority={}",
                            target.candidate().getProvider(),
                            target.candidate().getModel(),
                            target.candidate().getPriority());
                    return client.rerank(query, candidates, topN, target);
                }
        );
    }
}
