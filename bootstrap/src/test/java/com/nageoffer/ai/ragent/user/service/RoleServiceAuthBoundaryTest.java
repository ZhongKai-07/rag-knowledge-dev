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
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
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
 * 在 KbAccessService 抛出 ClientException 时如实向上传播。
 */
class RoleServiceAuthBoundaryTest {

    private KbAccessService kbAccessService;
    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        kbAccessService = mock(KbAccessService.class);
        service = buildServiceWithMockedAccess(kbAccessService);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void getKbRoleBindings_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkKbRoleBindingAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class, () -> service.getKbRoleBindings("kb-1"));
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
        verify(kbAccessService).checkKbRoleBindingAccess("kb-1");
    }

    @Test
    void setKbRoleBindings_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkKbRoleBindingAccess("kb-1");

        ClientException ex = assertThrows(ClientException.class,
                () -> service.setKbRoleBindings("kb-1", List.of()));
        if (!"denied".equals(ex.getMessage())) {
            throw new AssertionError("Expected propagated denied message, got: " + ex.getMessage());
        }
        verify(kbAccessService).checkKbRoleBindingAccess("kb-1");
    }

    private static RoleServiceImpl buildServiceWithMockedAccess(KbAccessService kbAccessService) {
        return new RoleServiceImpl(
                mock(RoleMapper.class),
                mock(RoleKbRelationMapper.class),
                mock(UserRoleMapper.class),
                mock(UserMapper.class),
                mock(SysDeptMapper.class),
                mock(KnowledgeBaseMapper.class),
                kbAccessService);
    }
}
