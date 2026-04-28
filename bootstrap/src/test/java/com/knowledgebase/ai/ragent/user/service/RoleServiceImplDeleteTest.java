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

package com.knowledgebase.ai.ragent.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.CurrentUserProbe;
import com.knowledgebase.ai.ragent.framework.security.port.KbAccessCacheAdmin;
import com.knowledgebase.ai.ragent.framework.security.port.KbManageAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.SuperAdminInvariantGuard;
import com.knowledgebase.ai.ragent.framework.security.port.SuperAdminMutationIntent;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.knowledgebase.ai.ragent.user.service.impl.RoleServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.4: DELETE /role/{roleId} 事务级联 + 缓存失效单测。
 */
class RoleServiceImplDeleteTest {

    private RoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private RoleKbRelationMapper roleKbRelationMapper;
    private SuperAdminInvariantGuard superAdminGuard;
    private KbAccessCacheAdmin cacheAdmin;
    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(UserRoleDO.class);
        initTableInfo(RoleKbRelationDO.class);
        initTableInfo(RoleDO.class);

        roleMapper = mock(RoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleKbRelationMapper = mock(RoleKbRelationMapper.class);
        superAdminGuard = mock(SuperAdminInvariantGuard.class);
        cacheAdmin = mock(KbAccessCacheAdmin.class);
        UserMapper userMapper = mock(UserMapper.class);
        SysDeptMapper sysDeptMapper = mock(SysDeptMapper.class);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);

        service = new RoleServiceImpl(
                roleMapper,
                roleKbRelationMapper,
                userRoleMapper,
                userMapper,
                sysDeptMapper,
                knowledgeBaseMapper,
                superAdminGuard,
                cacheAdmin,
                mock(KbManageAccessPort.class),
                mock(CurrentUserProbe.class));
    }

    /** 级联删 + 按受影响用户清缓存（去重）*/
    @Test
    void deleteRole_cascadesAndEvictsCachePerAffectedUser() {
        String roleId = "r-target";
        when(roleMapper.selectById(roleId)).thenReturn(
                RoleDO.builder().id(roleId).roleType("USER").name("ops-contractor").build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId("u-1").roleId(roleId).build(),
                UserRoleDO.builder().userId("u-2").roleId(roleId).build(),
                UserRoleDO.builder().userId("u-1").roleId(roleId).build())); // 重复
        when(roleKbRelationMapper.delete(any())).thenReturn(2);
        when(userRoleMapper.delete(any())).thenReturn(3);
        when(roleMapper.deleteById(roleId)).thenReturn(1);

        service.deleteRole(roleId);

        verify(roleKbRelationMapper).delete(any());
        verify(userRoleMapper).delete(any());
        verify(roleMapper).deleteById(roleId);
        // u-1 去重成一次；u-2 一次
        verify(cacheAdmin).evictCache("u-1");
        verify(cacheAdmin).evictCache("u-2");
        verify(cacheAdmin, times(2)).evictCache(any());
    }

    /** 无挂载用户时 evictCache 不触发 */
    @Test
    void deleteRole_noUsers_noCacheEvict() {
        String roleId = "r-empty";
        when(roleMapper.selectById(roleId)).thenReturn(
                RoleDO.builder().id(roleId).roleType("USER").name("unused").build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.deleteRole(roleId);

        verify(roleKbRelationMapper).delete(any());
        verify(userRoleMapper).delete(any());
        verify(roleMapper).deleteById(roleId);
        verify(cacheAdmin, never()).evictCache(any());
    }

    /** last SUPER_ADMIN 保护：删除导致 SUPER 归零 → ClientException，DB 无任何改动 */
    @Test
    void deleteRole_lastSuperAdmin_rejectedWithoutDbChanges() {
        String roleId = "r-super";
        when(roleMapper.selectById(roleId)).thenReturn(
                RoleDO.builder().id(roleId).roleType(RoleType.SUPER_ADMIN.name()).name("超级管理员").build());
        when(superAdminGuard.simulateActiveSuperAdminCountAfter(any(SuperAdminMutationIntent.DeleteRole.class)))
                .thenReturn(0);

        ClientException ex = assertThrows(ClientException.class, () -> service.deleteRole(roleId));
        assertEquals("不能删除该角色：此操作会使系统失去最后一个 SUPER_ADMIN", ex.getMessage());

        verify(roleKbRelationMapper, never()).delete(any());
        verify(userRoleMapper, never()).delete(any());
        verify(roleMapper, never()).deleteById(eq(roleId));
        verify(cacheAdmin, never()).evictCache(any());
    }

    /** SUPER_ADMIN 还有后备 → 允许删除 */
    @Test
    void deleteRole_superAdminWithBackup_allowed() {
        String roleId = "r-super-secondary";
        when(roleMapper.selectById(roleId)).thenReturn(
                RoleDO.builder().id(roleId).roleType(RoleType.SUPER_ADMIN.name()).name("备用超管").build());
        when(superAdminGuard.simulateActiveSuperAdminCountAfter(any(SuperAdminMutationIntent.DeleteRole.class)))
                .thenReturn(1);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId("u-9").roleId(roleId).build()));

        service.deleteRole(roleId);

        verify(roleMapper).deleteById(roleId);
        verify(cacheAdmin).evictCache("u-9");
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
