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

package com.knowledgebase.ai.ragent.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.knowledgebase.ai.ragent.core.parser.DocumentParserSelector;
import com.knowledgebase.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.knowledgebase.ai.ragent.ingestion.engine.IngestionEngine;
import com.knowledgebase.ai.ragent.ingestion.service.IngestionPipelineService;
import com.knowledgebase.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.knowledgebase.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.knowledgebase.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorStoreService;
import com.knowledgebase.ai.ragent.rag.service.FileStorageService;
import com.knowledgebase.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbManageAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbRoleBindingAdminPort;
import com.knowledgebase.ai.ragent.user.dao.mapper.SysDeptMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class PageQueryFailClosedTest {

    @Test
    void pageQuery_returns_empty_page_when_scope_ids_is_empty() {
        KnowledgeBaseServiceImpl service = buildKbService(
                mock(KbReadAccessPort.class),
                mock(KbManageAccessPort.class),
                mock(KbRoleBindingAdminPort.class));

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setCurrent(1L);
        request.setSize(10L);

        IPage<KnowledgeBaseVO> page = service.pageQuery(request, AccessScope.empty());

        assertEquals(0, page.getTotal(), "empty AccessScope.Ids must see empty page");
        assertEquals(0, page.getRecords().size(), "records must be empty");
    }

    @Test
    void documentSearch_returns_empty_list_when_scope_ids_is_empty() {
        KnowledgeDocumentServiceImpl service = buildDocService(
                mock(KbReadAccessPort.class),
                mock(KbManageAccessPort.class));

        // keyword must be non-blank — service short-circuits to empty for blank keyword
        // BEFORE the empty-scope check fires.
        List<KnowledgeDocumentSearchVO> result = service.search("anything", 8, AccessScope.empty());

        assertEquals(0, result.size(),
                "empty AccessScope.Ids must see empty search list");
    }

    private static KnowledgeBaseServiceImpl buildKbService(
            KbReadAccessPort kbReadAccess,
            KbManageAccessPort kbManageAccess,
            KbRoleBindingAdminPort kbRoleBindingAdmin) {
        return new KnowledgeBaseServiceImpl(
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeDocumentMapper.class),
                mock(VectorStoreAdmin.class),
                mock(FileStorageService.class),
                kbReadAccess,
                kbManageAccess,
                kbRoleBindingAdmin,
                mock(SysDeptMapper.class));
    }

    private static KnowledgeDocumentServiceImpl buildDocService(
            KbReadAccessPort kbReadAccess,
            KbManageAccessPort kbManageAccess) {
        return new KnowledgeDocumentServiceImpl(
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeDocumentMapper.class),
                kbReadAccess,
                kbManageAccess,
                mock(DocumentParserSelector.class),
                mock(ChunkingStrategyFactory.class),
                mock(FileStorageService.class),
                mock(VectorStoreService.class),
                mock(VectorStoreAdmin.class),
                mock(KnowledgeChunkService.class),
                mock(com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper.class),
                new com.knowledgebase.ai.ragent.knowledge.service.support.KnowledgeChunkLayoutMapper(
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                mock(ObjectMapper.class),
                mock(KnowledgeDocumentScheduleService.class),
                mock(IngestionPipelineService.class),
                mock(IngestionPipelineMapper.class),
                mock(IngestionEngine.class),
                mock(ChunkEmbeddingService.class),
                mock(KnowledgeDocumentChunkLogMapper.class),
                mock(TransactionOperations.class),
                mock(MessageQueueProducer.class),
                mock(KnowledgeScheduleProperties.class),
                mock(RemoteFileFetcher.class),
                new com.knowledgebase.ai.ragent.knowledge.service.support.ParseModePolicy(),
                new com.knowledgebase.ai.ragent.knowledge.service.support.ParseModeRouter());
    }
}
