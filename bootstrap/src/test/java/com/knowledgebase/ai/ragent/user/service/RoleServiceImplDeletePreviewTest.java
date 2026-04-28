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
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.CurrentUserProbe;
import com.knowledgebase.ai.ragent.framework.security.port.KbAccessCacheAdmin;
import com.knowledgebase.ai.ragent.framework.security.port.KbManageAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.SuperAdminInvariantGuard;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.user.controller.RoleController;
import com.knowledgebase.ai.ragent.user.controller.RoleController.RoleDeletePreviewVO;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.SysDeptDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserDO;
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

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0.2: GET /role/{roleId}/delete-preview 后端单测。
 * 设计依据：docs/dev/design/2026-04-19-access-center-redesign.md §六 P0 后端清单
 */
class RoleServiceImplDeletePreviewTest {

    private RoleMapper roleMapper;
    private UserRoleMapper userRoleMapper;
    private RoleKbRelationMapper roleKbRelationMapper;
    private UserMapper userMapper;
    private SysDeptMapper sysDeptMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        // MyBatis Plus lambda query 需要预先注册表元信息
        initTableInfo(UserRoleDO.class);
        initTableInfo(RoleKbRelationDO.class);

        roleMapper = mock(RoleMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleKbRelationMapper = mock(RoleKbRelationMapper.class);
        userMapper = mock(UserMapper.class);
        sysDeptMapper = mock(SysDeptMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);

        service = new RoleServiceImpl(
                roleMapper,
                roleKbRelationMapper,
                userRoleMapper,
                userMapper,
                sysDeptMapper,
                knowledgeBaseMapper,
                mock(SuperAdminInvariantGuard.class),
                mock(KbAccessCacheAdmin.class),
                mock(KbManageAccessPort.class),
                mock(CurrentUserProbe.class));
    }

    @Test
    void preview_throws_when_role_not_found() {
        when(roleMapper.selectById("missing")).thenReturn(null);
        assertThrows(ClientException.class, () -> service.getRoleDeletePreview("missing"));
    }

    @Test
    void preview_empty_when_role_has_no_users_and_no_kbs() {
        RoleDO role = role("role-1", "OPS admin");
        when(roleMapper.selectById("role-1")).thenReturn(role);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of());

        RoleDeletePreviewVO preview = service.getRoleDeletePreview("role-1");

        assertNotNull(preview);
        assertEquals("role-1", preview.getRoleId());
        assertEquals("OPS admin", preview.getRoleName());
        assertTrue(preview.getAffectedUsers().isEmpty());
        assertTrue(preview.getAffectedKbs().isEmpty());
        assertTrue(preview.getUserKbDiff().isEmpty());
    }

    @Test
    void preview_user_loses_all_kbs_when_no_other_role_grants_them() {
        RoleDO role = role("role-1", "OPS admin");
        when(roleMapper.selectById("role-1")).thenReturn(role);

        // 一个用户拥有该角色，无其他角色
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(userRole("alice", "role-1")))   // 第一次：找该角色的用户
                .thenReturn(List.of());                              // 第二次：找其他角色（无）
        when(roleKbRelationMapper.selectList(any()))
                .thenReturn(List.of(
                        roleKb("role-1", "kb-a"),
                        roleKb("role-1", "kb-b")));

        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("alice", "OPS")));
        when(knowledgeBaseMapper.selectBatchIds(any())).thenReturn(List.of(
                kb("kb-a", "OPS-COB", "OPS"),
                kb("kb-b", "OPS-Reports", "OPS")));
        when(sysDeptMapper.selectBatchIds(any())).thenReturn(List.of(dept("OPS", "Operation")));

        RoleDeletePreviewVO preview = service.getRoleDeletePreview("role-1");

        assertEquals(1, preview.getAffectedUsers().size());
        assertEquals("alice", preview.getAffectedUsers().get(0).getUsername());
        assertEquals("Operation", preview.getAffectedUsers().get(0).getDeptName());

        assertEquals(2, preview.getAffectedKbs().size());

        assertEquals(1, preview.getUserKbDiff().size());
        RoleController.UserKbDiff diff = preview.getUserKbDiff().get(0);
        assertEquals("alice", diff.getUsername());
        assertEquals(2, diff.getLostKbIds().size(),
                "no other role grants any kb → user loses both kbs");
        assertTrue(diff.getLostKbIds().containsAll(List.of("kb-a", "kb-b")));
        assertTrue(diff.getLostKbNames().containsAll(List.of("OPS-COB", "OPS-Reports")));
    }

    @Test
    void preview_user_only_loses_unique_kbs_when_other_role_overlaps() {
        RoleDO role = role("role-1", "OPS admin");
        when(roleMapper.selectById("role-1")).thenReturn(role);

        // alice 有 role-1（被删）和 role-2
        // role-2 也挂载了 kb-a，因此 alice 只会失去 kb-b
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(userRole("alice", "role-1")))   // 第一次：被删角色的用户
                .thenReturn(List.of(userRole("alice", "role-2")));  // 第二次：其他角色（不含 role-1）
        when(roleKbRelationMapper.selectList(any()))
                .thenReturn(List.of(
                        roleKb("role-1", "kb-a"),
                        roleKb("role-1", "kb-b")))                  // 第一次：被删角色的 KB
                .thenReturn(List.of(
                        roleKb("role-2", "kb-a")));                 // 第二次：其他角色挂载的 KB

        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("alice", "OPS")));
        when(knowledgeBaseMapper.selectBatchIds(any())).thenReturn(List.of(
                kb("kb-a", "OPS-COB", "OPS"),
                kb("kb-b", "OPS-Reports", "OPS")));
        when(sysDeptMapper.selectBatchIds(any())).thenReturn(List.of(dept("OPS", "Operation")));

        RoleDeletePreviewVO preview = service.getRoleDeletePreview("role-1");

        assertEquals(1, preview.getUserKbDiff().size());
        RoleController.UserKbDiff diff = preview.getUserKbDiff().get(0);
        assertEquals(1, diff.getLostKbIds().size(),
                "kb-a is also granted by role-2 → only kb-b is lost");
        assertEquals("kb-b", diff.getLostKbIds().get(0));
        assertEquals("OPS-Reports", diff.getLostKbNames().get(0));
    }

    // ========== fixtures ==========

    private static RoleDO role(String id, String name) {
        RoleDO r = new RoleDO();
        r.setId(id);
        r.setName(name);
        return r;
    }

    private static UserRoleDO userRole(String userId, String roleId) {
        return UserRoleDO.builder().userId(userId).roleId(roleId).build();
    }

    private static RoleKbRelationDO roleKb(String roleId, String kbId) {
        return RoleKbRelationDO.builder().roleId(roleId).kbId(kbId).build();
    }

    private static UserDO user(String id, String deptId) {
        return UserDO.builder().id(id).username(id).deptId(deptId).build();
    }

    private static SysDeptDO dept(String id, String name) {
        SysDeptDO d = new SysDeptDO();
        d.setId(id);
        d.setDeptName(name);
        return d;
    }

    private static KnowledgeBaseDO kb(String id, String name, String deptId) {
        return KnowledgeBaseDO.builder().id(id).name(name).deptId(deptId).build();
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
