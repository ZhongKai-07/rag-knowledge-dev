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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.rag.core.vector.VectorMetadataFields;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消费 {@link SecurityLevelRefreshEvent}，通过 {@code VectorStoreService.updateChunksMetadata}
 * 刷新 OpenSearch 里所有相关 chunk 的 metadata。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-document-security-level_topic${unique-name:}",
        consumerGroup = "knowledge-document-security-level_cg${unique-name:}"
)
public class KnowledgeDocumentSecurityLevelRefreshConsumer
        implements RocketMQListener<MessageWrapper<SecurityLevelRefreshEvent>> {

    private final VectorStoreService vectorStoreService;

    @Override
    public void onMessage(MessageWrapper<SecurityLevelRefreshEvent> message) {
        SecurityLevelRefreshEvent event = message.getBody();
        log.info("[消费者] 收到 security_level 刷新事件: docId={}, newLevel={}, keys={}",
                event.getDocId(), event.getNewSecurityLevel(), message.getKeys());
        vectorStoreService.updateChunksMetadata(
                event.getCollectionName(),
                event.getDocId(),
                Map.of(VectorMetadataFields.SECURITY_LEVEL, event.getNewSecurityLevel())
        );
        log.info("[消费者] security_level 刷新完成: docId={}", event.getDocId());
    }
}
