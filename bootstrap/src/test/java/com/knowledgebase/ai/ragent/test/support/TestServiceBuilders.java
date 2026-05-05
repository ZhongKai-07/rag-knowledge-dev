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

package com.knowledgebase.ai.ragent.test.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.knowledgebase.ai.ragent.core.parser.DocumentParserSelector;
import com.knowledgebase.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.knowledgebase.ai.ragent.framework.security.port.CurrentUserProbe;
import com.knowledgebase.ai.ragent.framework.security.port.KbAccessCacheAdmin;
import com.knowledgebase.ai.ragent.framework.security.port.KbManageAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbRoleBindingAdminPort;
import com.knowledgebase.ai.ragent.framework.security.port.SuperAdminInvariantGuard;
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingService;
import com.knowledgebase.ai.ragent.infra.token.TokenCounterService;
import com.knowledgebase.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.knowledgebase.ai.ragent.ingestion.engine.IngestionEngine;
import com.knowledgebase.ai.ragent.ingestion.service.IngestionPipelineService;
import com.knowledgebase.ai.ragent.knowledge.config.KnowledgeScheduleProperties;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.knowledgebase.ai.ragent.knowledge.handler.RemoteFileFetcher;
import com.knowledgebase.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.knowledgebase.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeChunkServiceImpl;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorStoreService;
import com.knowledgebase.ai.ragent.rag.service.FileStorageService;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.knowledgebase.ai.ragent.user.service.impl.RoleServiceImpl;
import org.springframework.transaction.support.TransactionOperations;

import static org.mockito.Mockito.mock;

/**
 * Shared factory methods for constructing service impls with mocked dependencies in auth-boundary
 * tests. Centralises mock-construction so PR2+ tests (KbScopeResolver / new ports) can reuse the
 * same wiring without duplicating the constructor argument shape.
 *
 * <p>IMPORTANT: constructor argument order and mock dependency list are intentionally kept
 * identical to the inlined originals — do NOT reorder or trim mocks.
 */
public final class TestServiceBuilders {

    private TestServiceBuilders() {}

    public static KnowledgeBaseServiceImpl knowledgeBaseService(
            KbReadAccessPort kbReadAccess,
            KbManageAccessPort kbManageAccess,
            KbRoleBindingAdminPort kbRoleBindingAdmin) {
        return knowledgeBaseService(
                kbReadAccess,
                kbManageAccess,
                kbRoleBindingAdmin,
                mock(KnowledgeBaseMapper.class));
    }

    public static KnowledgeBaseServiceImpl knowledgeBaseService(
            KbReadAccessPort kbReadAccess,
            KbManageAccessPort kbManageAccess,
            KbRoleBindingAdminPort kbRoleBindingAdmin,
            KnowledgeBaseMapper knowledgeBaseMapper) {
        return new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                mock(KnowledgeDocumentMapper.class),
                mock(VectorStoreAdmin.class),
                mock(FileStorageService.class),
                kbReadAccess,
                kbManageAccess,
                kbRoleBindingAdmin,
                mock(SysDeptMapper.class));
    }

    public static KnowledgeChunkServiceImpl knowledgeChunkService(
            KbReadAccessPort kbReadAccess,
            KbManageAccessPort kbManageAccess,
            KbMetadataReader kbMetadataReader) {
        return new KnowledgeChunkServiceImpl(
                mock(KnowledgeChunkMapper.class),
                mock(KnowledgeDocumentMapper.class),
                mock(KnowledgeBaseMapper.class),
                mock(EmbeddingService.class),
                mock(TokenCounterService.class),
                mock(VectorStoreService.class),
                mock(TransactionOperations.class),
                kbReadAccess,
                kbManageAccess,
                kbMetadataReader);
    }

    public static KnowledgeDocumentServiceImpl knowledgeDocumentService(
            KbReadAccessPort kbReadAccess,
            KbManageAccessPort kbManageAccess,
            KnowledgeDocumentMapper documentMapper) {
        return new KnowledgeDocumentServiceImpl(
                mock(KnowledgeBaseMapper.class),
                documentMapper,
                kbReadAccess,
                kbManageAccess,
                mock(DocumentParserSelector.class),
                mock(ChunkingStrategyFactory.class),
                mock(FileStorageService.class),
                mock(VectorStoreService.class),
                mock(VectorStoreAdmin.class),
                mock(KnowledgeChunkService.class),
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

    public static RoleServiceImpl roleService(KbManageAccessPort kbManageAccess) {
        return roleService(
                mock(SuperAdminInvariantGuard.class),
                mock(KbAccessCacheAdmin.class),
                kbManageAccess,
                mock(CurrentUserProbe.class));
    }

    public static RoleServiceImpl roleService(
            SuperAdminInvariantGuard superAdminGuard,
            KbAccessCacheAdmin cacheAdmin,
            KbManageAccessPort kbManageAccess,
            CurrentUserProbe currentUser) {
        return new RoleServiceImpl(
                mock(RoleMapper.class),
                mock(RoleKbRelationMapper.class),
                mock(UserRoleMapper.class),
                mock(UserMapper.class),
                mock(SysDeptMapper.class),
                mock(KnowledgeBaseMapper.class),
                superAdminGuard,
                cacheAdmin,
                kbManageAccess,
                currentUser);
    }

}
