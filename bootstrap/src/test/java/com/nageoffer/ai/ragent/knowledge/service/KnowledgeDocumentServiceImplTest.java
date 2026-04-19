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

package com.nageoffer.ai.ragent.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.mq.SecurityLevelRefreshEvent;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceImplTest {

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeDocumentMapper documentMapper;
    private MessageQueueProducer messageQueueProducer;
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(KnowledgeDocumentDO.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        DocumentParserSelector parserSelector = mock(DocumentParserSelector.class);
        ChunkingStrategyFactory chunkingStrategyFactory = mock(ChunkingStrategyFactory.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        KnowledgeChunkService knowledgeChunkService = mock(KnowledgeChunkService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        KnowledgeDocumentScheduleService scheduleService = mock(KnowledgeDocumentScheduleService.class);
        IngestionPipelineService ingestionPipelineService = mock(IngestionPipelineService.class);
        IngestionPipelineMapper ingestionPipelineMapper = mock(IngestionPipelineMapper.class);
        IngestionEngine ingestionEngine = mock(IngestionEngine.class);
        ChunkEmbeddingService chunkEmbeddingService = mock(ChunkEmbeddingService.class);
        KnowledgeDocumentChunkLogMapper chunkLogMapper = mock(KnowledgeDocumentChunkLogMapper.class);
        TransactionOperations transactionOperations = mock(TransactionOperations.class);
        messageQueueProducer = mock(MessageQueueProducer.class);
        KnowledgeScheduleProperties scheduleProperties = mock(KnowledgeScheduleProperties.class);
        RemoteFileFetcher remoteFileFetcher = mock(RemoteFileFetcher.class);

        service = new KnowledgeDocumentServiceImpl(
                knowledgeBaseMapper,
                documentMapper,
                parserSelector,
                chunkingStrategyFactory,
                fileStorageService,
                vectorStoreService,
                vectorStoreAdmin,
                knowledgeChunkService,
                objectMapper,
                scheduleService,
                ingestionPipelineService,
                ingestionPipelineMapper,
                ingestionEngine,
                chunkEmbeddingService,
                chunkLogMapper,
                transactionOperations,
                messageQueueProducer,
                scheduleProperties,
                remoteFileFetcher
        );
        ReflectionTestUtils.setField(service, "securityLevelRefreshTopic", "knowledge-document-security-level_topic");
    }

    @Test
    void updateSecurityLevel_usesResolvedTopicForRefreshMessage() {
        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .securityLevel(0)
                .build();
        KnowledgeBaseDO knowledgeBase = KnowledgeBaseDO.builder()
                .id("kb-1")
                .collectionName("kb-collection")
                .build();
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(documentMapper.update(any())).thenReturn(1);
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(knowledgeBase);

        service.updateSecurityLevel("doc-1", 3);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SecurityLevelRefreshEvent> eventCaptor = ArgumentCaptor.forClass(SecurityLevelRefreshEvent.class);
        verify(messageQueueProducer).send(
                topicCaptor.capture(),
                eq("doc-1"),
                eq("security_level 刷新"),
                eventCaptor.capture()
        );
        assertTrue(topicCaptor.getValue().contains("knowledge-document-security-level_topic"));
        assertFalse(topicCaptor.getValue().contains("${"));
        assertEquals(3, eventCaptor.getValue().getNewSecurityLevel());
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
