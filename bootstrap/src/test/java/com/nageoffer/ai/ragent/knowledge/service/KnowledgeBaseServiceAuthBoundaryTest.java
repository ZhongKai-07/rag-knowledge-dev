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

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.port.KbManageAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbRoleBindingAdminPort;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.test.support.TestServiceBuilders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 服务层授权边界测试：验证 KnowledgeBaseService 的 3 个授权入口
 * 在小权限 Port 抛出 ClientException 时如实向上传播。
 *
 * <p>注：UserContext 设置为普通用户以避开 commit 3 引入的 system/no-user 严格守卫；
 * 该守卫由 KbAccessServiceSystemActorTest 覆盖（T2a/T3）。
 */
class KnowledgeBaseServiceAuthBoundaryTest {

    private KbReadAccessPort kbReadAccess;
    private KbManageAccessPort kbManageAccess;
    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        kbReadAccess = mock(KbReadAccessPort.class);
        kbManageAccess = mock(KbManageAccessPort.class);
        service = TestServiceBuilders.knowledgeBaseService(
                kbReadAccess,
                kbManageAccess,
                mock(KbRoleBindingAdminPort.class));
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void rename_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkManageAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.rename("kb-1", new KnowledgeBaseUpdateRequest()));
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
        verify(kbManageAccess).checkManageAccess("kb-1");
    }

    @Test
    void delete_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkManageAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.delete("kb-1"));
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
        verify(kbManageAccess).checkManageAccess("kb-1");
    }

    @Test
    void queryById_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbReadAccess).checkReadAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.queryById("kb-1"));
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
        verify(kbReadAccess).checkReadAccess("kb-1");
    }

}
