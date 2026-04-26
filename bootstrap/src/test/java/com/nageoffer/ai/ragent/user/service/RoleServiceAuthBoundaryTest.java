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

package com.nageoffer.ai.ragent.user.service;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.port.KbManageAccessPort;
import com.nageoffer.ai.ragent.test.support.TestServiceBuilders;
import com.nageoffer.ai.ragent.user.service.impl.RoleServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 服务层授权边界测试：验证 RoleService 的 2 个 KB-role-binding 入口
 * 在 KbManageAccessPort 抛出 ClientException 时如实向上传播。
 */
class RoleServiceAuthBoundaryTest {

    private KbAccessService kbAccessService;
    private KbManageAccessPort kbManageAccess;
    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        kbAccessService = mock(KbAccessService.class);
        kbManageAccess = mock(KbManageAccessPort.class);
        service = TestServiceBuilders.roleService(kbAccessService, kbManageAccess);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void getKbRoleBindings_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkKbRoleBindingAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.getKbRoleBindings("kb-1"));
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
        verify(kbManageAccess).checkKbRoleBindingAccess("kb-1");
    }

    @Test
    void setKbRoleBindings_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbManageAccess).checkKbRoleBindingAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.setKbRoleBindings("kb-1", List.of()));
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
        verify(kbManageAccess).checkKbRoleBindingAccess("kb-1");
    }

}
