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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import com.nageoffer.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceImplFindMetaTest {

    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        // CRITICAL: 不初始化 TableInfo, LambdaQueryWrapper 解析 `KnowledgeDocumentDO::getId` 列名时会 NPE —
        // 单测根本进不到 documentMapper mock, 就先在 MP 元信息阶段炸掉. 沿用 KnowledgeDocumentServiceImplTest
        // 的 initTableInfo helper 模式.
        initTableInfo(KnowledgeDocumentDO.class);

        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
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
        MessageQueueProducer messageQueueProducer = mock(MessageQueueProducer.class);
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
    }

    // Copy of the helper at KnowledgeDocumentServiceImplTest.java:142 — keeps this test self-contained.
    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    @Test
    void findMetaByIds_shouldReturnOnlyAvailableDocs() {
        KnowledgeDocumentDO doc1 = KnowledgeDocumentDO.builder()
                .id("doc_1").docName("手册 A.pdf").kbId("kb_x").build();
        KnowledgeDocumentDO doc2 = KnowledgeDocumentDO.builder()
                .id("doc_2").docName("考勤办法.docx").kbId("kb_x").build();

        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc1, doc2));

        List<DocumentMetaSnapshot> snapshots =
                service.findMetaByIds(List.of("doc_1", "doc_2", "doc_missing"));

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).extracting(DocumentMetaSnapshot::docId)
                .containsExactlyInAnyOrder("doc_1", "doc_2");
        assertThat(snapshots).extracting(DocumentMetaSnapshot::docName)
                .containsExactlyInAnyOrder("手册 A.pdf", "考勤办法.docx");
    }

    @Test
    void findMetaByIds_shouldReturnEmpty_forEmptyInput() {
        List<DocumentMetaSnapshot> snapshots = service.findMetaByIds(Set.of());
        assertThat(snapshots).isEmpty();
        verifyNoInteractions(documentMapper);
    }

    @Test
    void findMetaByIds_shouldReturnEmpty_forNullInput() {
        List<DocumentMetaSnapshot> snapshots = service.findMetaByIds(null);
        assertThat(snapshots).isEmpty();
        verifyNoInteractions(documentMapper);
    }

    @Test
    void findMetaByIds_shouldQueryMapperOnce() {
        KnowledgeDocumentDO live = KnowledgeDocumentDO.builder()
                .id("doc_1").docName("活").kbId("kb_x").build();
        when(documentMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(live));

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeDocumentDO>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);

        service.findMetaByIds(List.of("doc_1"));

        verify(documentMapper).selectList(captor.capture());
        // @TableLogic 由 MP 自动追加 deleted=0, 不需要显式断言 (CLAUDE.md Gotcha).
        assertThat(captor.getValue()).isNotNull();
    }
}
