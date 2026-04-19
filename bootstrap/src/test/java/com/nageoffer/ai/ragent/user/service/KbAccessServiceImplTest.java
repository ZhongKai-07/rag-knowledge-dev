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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbAccessServiceImplTest {

    private RoleKbRelationMapper roleKbRelationMapper;
    private UserRoleMapper userRoleMapper;
    private RedissonClient redissonClient;
    private KbAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(RoleKbRelationDO.class);
        initTableInfo(UserRoleDO.class);
        roleKbRelationMapper = mock(RoleKbRelationMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        redissonClient = mock(RedissonClient.class);
        UserMapper userMapper = mock(UserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        SysDeptMapper sysDeptMapper = mock(SysDeptMapper.class);
        KbMetadataReader kbMetadataReader = mock(KbMetadataReader.class);
        service = new KbAccessServiceImpl(
                userRoleMapper,
                roleKbRelationMapper,
                redissonClient,
                userMapper,
                roleMapper,
                sysDeptMapper,
                kbMetadataReader
        );
    }

    @Test
    void unbindAllRolesFromKb_returnsZeroWhenNoBindings() {
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of());

        int affected = service.unbindAllRolesFromKb("kb-1");

        assertEquals(0, affected);
        verify(roleKbRelationMapper, never()).delete(any());
        verify(userRoleMapper, never()).selectList(any());
        verify(redissonClient, never()).getBucket(anyString());
    }

    @Test
    void unbindAllRolesFromKb_skipsCacheEvictionWhenNoUsersHoldRoles() {
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId("role-1").build(),
                RoleKbRelationDO.builder().roleId("role-2").build()
        ));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(roleKbRelationMapper.delete(any())).thenReturn(2);

        int affected = service.unbindAllRolesFromKb("kb-1");

        assertEquals(2, affected);
        verify(roleKbRelationMapper).delete(any());
        verify(redissonClient, never()).getBucket(anyString());
        verify(redissonClient, never()).getKeys();
    }

    @Test
    void unbindAllRolesFromKb_evictsAffectedUserCaches() {
        @SuppressWarnings("unchecked")
        RBucket<Object> user1AccessBucket = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> user1DeptBucket = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> user1SecurityBucket = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> user2AccessBucket = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> user2DeptBucket = mock(RBucket.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> user2SecurityBucket = mock(RBucket.class);

        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId("role-1").build()
        ));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId("user-1").roleId("role-1").build(),
                UserRoleDO.builder().userId("user-2").roleId("role-1").build()
        ));
        when(roleKbRelationMapper.delete(any())).thenReturn(1);
        when(redissonClient.getBucket("kb_access:user-1")).thenReturn(user1AccessBucket);
        when(redissonClient.getBucket("kb_access:dept:user-1")).thenReturn(user1DeptBucket);
        when(redissonClient.getBucket("kb_security_level:user-1")).thenReturn(user1SecurityBucket);
        when(redissonClient.getBucket("kb_access:user-2")).thenReturn(user2AccessBucket);
        when(redissonClient.getBucket("kb_access:dept:user-2")).thenReturn(user2DeptBucket);
        when(redissonClient.getBucket("kb_security_level:user-2")).thenReturn(user2SecurityBucket);

        int affected = service.unbindAllRolesFromKb("kb-1");

        assertEquals(1, affected);
        verify(user1AccessBucket).delete();
        verify(user1DeptBucket).delete();
        verify(user1SecurityBucket).delete();
        verify(user2AccessBucket).delete();
        verify(user2DeptBucket).delete();
        verify(user2SecurityBucket).delete();
    }

    @Test
    void unbindAllRolesFromKb_usesBulkPatternEvictAboveThreshold() {
        RKeys keys = mock(RKeys.class);
        List<UserRoleDO> manyUsers = new ArrayList<>();
        IntStream.rangeClosed(1, 501)
                .forEach(i -> manyUsers.add(UserRoleDO.builder().userId("user-" + i).roleId("role-1").build()));

        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId("role-1").build()
        ));
        when(userRoleMapper.selectList(any())).thenReturn(manyUsers);
        when(roleKbRelationMapper.delete(any())).thenReturn(1);
        when(redissonClient.getKeys()).thenReturn(keys);

        int affected = service.unbindAllRolesFromKb("kb-1");

        assertEquals(1, affected);
        verify(keys).deleteByPattern("kb_access:*");
        verify(keys).deleteByPattern("kb_access:dept:*");
        verify(keys).deleteByPattern("kb_security_level:*");
        verify(redissonClient, never()).getBucket(anyString());
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
