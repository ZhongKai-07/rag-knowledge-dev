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

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.KbManageAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.knowledgebase.ai.ragent.test.support.TestServiceBuilders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务层授权边界测试：验证 KnowledgeDocumentService 的 9 个授权入口
 * 在小权限 Port 抛出 ClientException 时如实向上传播。
 */
class KnowledgeDocumentServiceAuthBoundaryTest {

    private KbReadAccessPort kbReadAccess;
    private KbManageAccessPort kbManageAccess;
    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        kbReadAccess = mock(KbReadAccessPort.class);
        kbManageAccess = mock(KbManageAccessPort.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        service = TestServiceBuilders.knowledgeDocumentService(kbReadAccess, kbManageAccess, documentMapper);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void upload_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkManageAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.upload("kb-1", new KnowledgeDocumentUploadRequest(), null));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkManageAccess("kb-1");
    }

    @Test
    void startChunk_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.startChunk("doc-1"));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void delete_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.delete("doc-1"));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void get_propagates_check_failure_when_doc_present() {
        KnowledgeDocumentDO doc = new KnowledgeDocumentDO();
        doc.setId("doc-1");
        doc.setKbId("kb-1");
        when(documentMapper.selectById("doc-1")).thenReturn(doc);
        doThrow(new ClientException("denied")).when(kbReadAccess).checkReadAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.get("doc-1"));
        assertDeniedMessage(ex);
        verify(kbReadAccess).checkReadAccess("kb-1");
    }

    @Test
    void update_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.update("doc-1", new KnowledgeDocumentUpdateRequest()));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void page_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbReadAccess).checkReadAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.page("kb-1", new KnowledgeDocumentPageRequest()));
        assertDeniedMessage(ex);
        verify(kbReadAccess).checkReadAccess("kb-1");
    }

    @Test
    void enable_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.enable("doc-1", true));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void updateSecurityLevel_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocSecurityLevelAccess("doc-1", 2);

        ClientException ex = assertThrows(ClientException.class,
                () -> service.updateSecurityLevel("doc-1", 2));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocSecurityLevelAccess("doc-1", 2);
    }

    @Test
    void getChunkLogs_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.getChunkLogs("doc-1", null));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    private static void assertDeniedMessage(ClientException ex) {
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
    }
}
