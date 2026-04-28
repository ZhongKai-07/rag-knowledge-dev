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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.KbManageAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbRoleBindingAdminPort;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.knowledgebase.ai.ragent.rag.service.FileStorageService;
import com.knowledgebase.ai.ragent.user.dao.mapper.SysDeptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceImplDeleteTest {

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KnowledgeDocumentMapper knowledgeDocumentMapper;
    private VectorStoreAdmin vectorStoreAdmin;
    private FileStorageService fileStorageService;
    private KbReadAccessPort kbReadAccess;
    private KbManageAccessPort kbManageAccess;
    private KbRoleBindingAdminPort kbRoleBindingAdmin;
    private SysDeptMapper sysDeptMapper;
    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(KnowledgeDocumentDO.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
        vectorStoreAdmin = mock(VectorStoreAdmin.class);
        fileStorageService = mock(FileStorageService.class);
        kbReadAccess = mock(KbReadAccessPort.class);
        kbManageAccess = mock(KbManageAccessPort.class);
        kbRoleBindingAdmin = mock(KbRoleBindingAdminPort.class);
        sysDeptMapper = mock(SysDeptMapper.class);
        service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper,
                knowledgeDocumentMapper,
                vectorStoreAdmin,
                fileStorageService,
                kbReadAccess,
                kbManageAccess,
                kbRoleBindingAdmin,
                sysDeptMapper
        );
        UserContext.set(LoginUser.builder().username("tester").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void delete_throwsWhenKbMissing() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(null);

        ClientException ex = assertThrows(ClientException.class, () -> service.delete("kb-1"));

        assertEquals("知识库不存在", ex.getMessage());
        verify(knowledgeDocumentMapper, never()).selectCount(any());
        verify(kbRoleBindingAdmin, never()).unbindAllRolesFromKb(any());
        verify(vectorStoreAdmin, never()).dropVectorSpace(any());
        verify(fileStorageService, never()).deleteBucket(any());
    }

    @Test
    void delete_throwsWhenKbAlreadyDeleted() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .deleted(1)
                .build());

        ClientException ex = assertThrows(ClientException.class, () -> service.delete("kb-1"));

        assertEquals("知识库不存在", ex.getMessage());
        verify(knowledgeDocumentMapper, never()).selectCount(any());
        verify(kbRoleBindingAdmin, never()).unbindAllRolesFromKb(any());
        verify(vectorStoreAdmin, never()).dropVectorSpace(any());
        verify(fileStorageService, never()).deleteBucket(any());
    }

    @Test
    void delete_throwsWhenUndeletedDocumentsRemain() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .collectionName("collection-1")
                .deleted(0)
                .build());
        when(knowledgeDocumentMapper.selectCount(any())).thenReturn(1L);

        ClientException ex = assertThrows(ClientException.class, () -> service.delete("kb-1"));

        assertEquals("当前知识库下还有文档，请删除文档", ex.getMessage());
        verify(knowledgeBaseMapper, never()).deleteById(any(KnowledgeBaseDO.class));
        verify(kbRoleBindingAdmin, never()).unbindAllRolesFromKb(any());
        verify(vectorStoreAdmin, never()).dropVectorSpace(any());
        verify(fileStorageService, never()).deleteBucket(any());
    }

    @Test
    void delete_removesKbBindingsAndExternalResources() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .collectionName("collection-1")
                .deleted(0)
                .build());
        when(knowledgeDocumentMapper.selectCount(any())).thenReturn(0L);
        when(kbRoleBindingAdmin.unbindAllRolesFromKb("kb-1")).thenReturn(3);

        service.delete("kb-1");

        verify(knowledgeBaseMapper).deleteById(any(KnowledgeBaseDO.class));
        verify(kbRoleBindingAdmin).unbindAllRolesFromKb("kb-1");
        verify(vectorStoreAdmin).dropVectorSpace(argThat(spaceId ->
                "collection-1".equals(spaceId.getLogicalName())));
        verify(fileStorageService).deleteBucket("collection-1");
    }

    @Test
    void delete_continuesWhenDropVectorSpaceFails() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .collectionName("collection-1")
                .deleted(0)
                .build());
        when(knowledgeDocumentMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RuntimeException("vector delete failed")).when(vectorStoreAdmin).dropVectorSpace(any());

        assertDoesNotThrow(() -> service.delete("kb-1"));

        verify(vectorStoreAdmin).dropVectorSpace(any());
        verify(fileStorageService).deleteBucket("collection-1");
    }

    @Test
    void delete_continuesWhenDeleteBucketFails() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(KnowledgeBaseDO.builder()
                .id("kb-1")
                .collectionName("collection-1")
                .deleted(0)
                .build());
        when(knowledgeDocumentMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RuntimeException("bucket delete failed")).when(fileStorageService).deleteBucket("collection-1");

        assertDoesNotThrow(() -> service.delete("kb-1"));

        verify(vectorStoreAdmin).dropVectorSpace(any());
        verify(fileStorageService).deleteBucket("collection-1");
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
