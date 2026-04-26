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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.impl.KbAccessServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class KbAccessServiceSystemActorTest {

    private KbMetadataReader kbMetadataReader;
    private UserRoleMapper userRoleMapper;
    private RoleKbRelationMapper roleKbRelationMapper;
    private KbAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(RoleKbRelationDO.class);
        initTableInfo(UserRoleDO.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleKbRelationMapper = mock(RoleKbRelationMapper.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        UserMapper userMapper = mock(UserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        SysDeptMapper sysDeptMapper = mock(SysDeptMapper.class);
        kbMetadataReader = mock(KbMetadataReader.class);
        service = new KbAccessServiceImpl(
                userRoleMapper, roleKbRelationMapper, redissonClient,
                userMapper, roleMapper, sysDeptMapper, kbMetadataReader);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    // ===== T2a — system bypass returns early =====

    @Test
    void checkAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkAccess("kb-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkManageAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkManageAccess("kb-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkDocManageAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkDocManageAccess("doc-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkDocSecurityLevelAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkDocSecurityLevelAccess("doc-x", 2));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkKbRoleBindingAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkKbRoleBindingAccess("kb-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    // ===== T3 — missing user context throws =====

    @Test
    void checkAccess_throws_when_no_user_context() {
        UserContext.clear();
        ClientException ex = assertThrows(ClientException.class,
                () -> service.checkAccess("kb-x"));
        assertContainsMissingUserContext(ex);
    }

    @Test
    void checkManageAccess_throws_when_no_user_context() {
        UserContext.clear();
        ClientException ex = assertThrows(ClientException.class,
                () -> service.checkManageAccess("kb-x"));
        assertContainsMissingUserContext(ex);
    }

    @Test
    void checkDocManageAccess_throws_when_no_user_context() {
        UserContext.clear();
        ClientException ex = assertThrows(ClientException.class,
                () -> service.checkDocManageAccess("doc-x"));
        assertContainsMissingUserContext(ex);
    }

    @Test
    void checkDocSecurityLevelAccess_throws_when_no_user_context() {
        UserContext.clear();
        ClientException ex = assertThrows(ClientException.class,
                () -> service.checkDocSecurityLevelAccess("doc-x", 1));
        assertContainsMissingUserContext(ex);
    }

    @Test
    void checkKbRoleBindingAccess_throws_when_no_user_context() {
        UserContext.clear();
        ClientException ex = assertThrows(ClientException.class,
                () -> service.checkKbRoleBindingAccess("kb-x"));
        assertContainsMissingUserContext(ex);
    }

    private static void assertContainsMissingUserContext(ClientException ex) {
        if (ex.getMessage() == null || !ex.getMessage().contains("missing user context")) {
            throw new AssertionError(
                    "Expected exception message to contain 'missing user context' but was: "
                            + ex.getMessage());
        }
    }

    private static void initTableInfo(Class<?> clazz) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), clazz);
    }
}
