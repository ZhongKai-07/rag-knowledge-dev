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

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class KnowledgeDocumentChunkConsumerSystemActorTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void onMessage_sets_system_actor_during_invocation_and_clears_after() {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        AtomicBoolean wasSystem = new AtomicBoolean(false);

        doAnswer((InvocationOnMock invocation) -> {
            wasSystem.set(UserContext.isSystem());
            return null;
        }).when(documentService).executeChunk("doc-001");

        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId("doc-001")
                .operator("op-x")
                .build();
        MessageWrapper<KnowledgeDocumentChunkEvent> wrapper =
                MessageWrapper.<KnowledgeDocumentChunkEvent>builder()
                        .keys("k-1")
                        .body(event)
                        .build();

        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(documentService);
        consumer.onMessage(wrapper);

        assertTrue(wasSystem.get(), "UserContext.isSystem() must be true inside service call");
        assertFalse(UserContext.hasUser(), "UserContext must be cleared after onMessage returns");
        verify(documentService).executeChunk("doc-001");
    }
}
