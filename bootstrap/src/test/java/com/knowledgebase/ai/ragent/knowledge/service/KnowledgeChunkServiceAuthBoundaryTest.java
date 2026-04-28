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
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeChunkServiceImpl;
import com.knowledgebase.ai.ragent.test.support.TestServiceBuilders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务层授权边界测试：验证 KnowledgeChunkService 的 6 个授权入口
 * 在小权限 Port 抛出 ClientException 时如实向上传播。
 *
 * <p>额外覆盖 pageQuery 在 docId→kbId 解析失败时抛出 ClientException 的场景。
 */
class KnowledgeChunkServiceAuthBoundaryTest {

    private KbReadAccessPort kbReadAccess;
    private KbManageAccessPort kbManageAccess;
    private KbMetadataReader kbMetadataReader;
    private KnowledgeChunkServiceImpl service;

    @BeforeEach
    void setUp() {
        kbReadAccess = mock(KbReadAccessPort.class);
        kbManageAccess = mock(KbManageAccessPort.class);
        kbMetadataReader = mock(KbMetadataReader.class);
        service = TestServiceBuilders.knowledgeChunkService(kbReadAccess, kbManageAccess, kbMetadataReader);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void pageQuery_propagates_check_failure() {
        when(kbMetadataReader.getKbIdOfDoc("doc-1")).thenReturn("kb-1");
        doThrow(new ClientException("denied")).when(kbReadAccess).checkReadAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.pageQuery("doc-1", new KnowledgeChunkPageRequest()));
        assertDeniedMessage(ex);
        verify(kbReadAccess).checkReadAccess("kb-1");
    }

    @Test
    void pageQuery_throws_when_doc_not_found() {
        when(kbMetadataReader.getKbIdOfDoc("doc-1")).thenReturn(null);

        ClientException ex = assertThrows(ClientException.class,
                () -> service.pageQuery("doc-1", new KnowledgeChunkPageRequest()));
        if (ex.getMessage() == null || !ex.getMessage().contains("文档不存在")) {
            throw new AssertionError("Expected 文档不存在 message, got: " + ex.getMessage());
        }
    }

    @Test
    void create_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.create("doc-1", new KnowledgeChunkCreateRequest()));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void update_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.update("doc-1", "chunk-1", new KnowledgeChunkUpdateRequest()));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void delete_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.delete("doc-1", "chunk-1"));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void enableChunk_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.enableChunk("doc-1", "chunk-1", true));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    @Test
    void batchToggleEnabled_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkDocManageAccess("doc-1");
        KnowledgeChunkBatchRequest req = new KnowledgeChunkBatchRequest();
        req.setChunkIds(List.of("chunk-1"));

        ClientException ex = assertThrows(ClientException.class,
                () -> service.batchToggleEnabled("doc-1", req, true));
        assertDeniedMessage(ex);
        verify(kbManageAccess).checkDocManageAccess("doc-1");
    }

    private static void assertDeniedMessage(ClientException ex) {
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
    }

}
