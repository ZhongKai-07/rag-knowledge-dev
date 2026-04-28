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
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.user.controller.vo.AccessRoleVO;
import com.knowledgebase.ai.ragent.user.controller.vo.RoleUsageVO;
import com.knowledgebase.ai.ragent.user.controller.vo.SysDeptVO;
import com.knowledgebase.ai.ragent.user.controller.vo.UserKbGrantVO;
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
import com.knowledgebase.ai.ragent.user.service.impl.AccessServiceImpl;
import com.knowledgebase.ai.ragent.user.service.support.KbAccessCalculator;
import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubject;
import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubjectFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessServiceImplTest {

    private RoleMapper roleMapper;
    private SysDeptMapper sysDeptMapper;
    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RoleKbRelationMapper roleKbRelationMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KbAccessSubjectFactory subjectFactory;
    private KbAccessCalculator calculator;
    private SysDeptService sysDeptService;
    private AccessServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(RoleDO.class);
        initTableInfo(SysDeptDO.class);
        initTableInfo(UserRoleDO.class);
        initTableInfo(RoleKbRelationDO.class);
        initTableInfo(KnowledgeBaseDO.class);
        roleMapper = mock(RoleMapper.class);
        sysDeptMapper = mock(SysDeptMapper.class);
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleKbRelationMapper = mock(RoleKbRelationMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        subjectFactory = mock(KbAccessSubjectFactory.class);
        calculator = mock(KbAccessCalculator.class);
        sysDeptService = mock(SysDeptService.class);
        service = new AccessServiceImpl(
                roleMapper,
                sysDeptMapper,
                userMapper,
                userRoleMapper,
                roleKbRelationMapper,
                knowledgeBaseMapper,
                subjectFactory,
                calculator,
                sysDeptService);
    }

    @Test
    void listRoles_deptIdAndGlobal_returnsBoth() {
        RoleDO opsAdmin = RoleDO.builder().id("r1").name("OPS admin").roleType("DEPT_ADMIN").deptId("dept-ops").build();
        RoleDO common = RoleDO.builder().id("r2").name("Regular user").roleType("USER").deptId("1").build();
        when(roleMapper.selectList(any())).thenReturn(List.of(opsAdmin, common));
        when(sysDeptMapper.selectList(any())).thenReturn(List.of(
                SysDeptDO.builder().id("dept-ops").deptName("Operation").build(),
                SysDeptDO.builder().id("1").deptName("Global").build()));

        List<AccessRoleVO> out = service.listRoles("dept-ops", true);

        assertEquals(2, out.size());
        AccessRoleVO vo1 = out.stream().filter(v -> "r1".equals(v.getId())).findFirst().orElseThrow();
        assertEquals("Operation", vo1.getDeptName());
        AccessRoleVO vo2 = out.stream().filter(v -> "r2".equals(v.getId())).findFirst().orElseThrow();
        assertEquals("Global", vo2.getDeptName());
    }

    @Test
    void listRoles_excludeGlobal_onlyDeptScope() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                RoleDO.builder().id("rX").name("pwm-user").roleType("USER").deptId("dept-pwm").build()));
        when(sysDeptMapper.selectList(any())).thenReturn(List.of(
                SysDeptDO.builder().id("dept-pwm").deptName("PWM").build()));

        List<AccessRoleVO> out = service.listRoles("dept-pwm", false);

        assertEquals(1, out.size());
        assertEquals("dept-pwm", out.get(0).getDeptId());
        assertEquals("PWM", out.get(0).getDeptName());
    }

    @Test
    void listRoles_noMatch_returnsEmpty() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        List<AccessRoleVO> out = service.listRoles("dept-x", true);

        assertTrue(out.isEmpty());
    }

    @Test
    void listRoles_noDeptFilter_returnsAll() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                RoleDO.builder().id("r1").name("OPS admin").roleType("DEPT_ADMIN").deptId("dept-ops").build(),
                RoleDO.builder().id("r2").name("Regular user").roleType("USER").deptId("1").build()));
        when(sysDeptMapper.selectList(any())).thenReturn(List.of(
                SysDeptDO.builder().id("dept-ops").deptName("Operation").build(),
                SysDeptDO.builder().id("1").deptName("Global").build()));

        List<AccessRoleVO> out = service.listRoles(null, true);

        assertEquals(2, out.size());
        List<String> ids = out.stream().map(AccessRoleVO::getId).collect(Collectors.toList());
        assertTrue(ids.contains("r1"));
        assertTrue(ids.contains("r2"));
    }

    @Test
    void listUserKbGrants_deptAdmin_implicitUpgradesExplicit() {
        String userId = "u-ops-admin";
        String roleId = "role-ops-admin";
        String kbId = "kb-ops-1";
        String missingLevelKbId = "kb-ops-2";
        String unloadedAccessibleKbId = "kb-ops-unloaded";
        String deptId = "dept-ops";
        KbAccessSubject target = stubTargetAccess(
                userId,
                deptId,
                Set.of(RoleType.DEPT_ADMIN),
                3,
                Set.of(kbId, missingLevelKbId, unloadedAccessibleKbId),
                Map.of(kbId, 3));

        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId(roleId).build()));
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId(roleId).kbId(kbId).permission("READ").build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("OPS-COB").deptId(deptId).build(),
                KnowledgeBaseDO.builder().id(missingLevelKbId).name("OPS-Missing-Level").deptId(deptId).build()));

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(2, out.size());
        UserKbGrantVO g = out.stream().filter(grant -> kbId.equals(grant.getKbId())).findFirst().orElseThrow();
        assertEquals("MANAGE", g.getPermission());
        assertEquals("READ", g.getExplicitPermission());
        assertTrue(g.isImplicit());
        assertEquals(List.of(roleId), g.getSourceRoleIds());
        assertEquals(3, g.getSecurityLevel());
        UserKbGrantVO missing = out.stream().filter(grant -> missingLevelKbId.equals(grant.getKbId())).findFirst().orElseThrow();
        assertEquals(0, missing.getSecurityLevel());
        verify(calculator, times(1)).computeMaxSecurityLevels(eq(target),
                argThat(ids -> new HashSet<>((Collection<String>) ids)
                        .equals(Set.of(kbId, missingLevelKbId, unloadedAccessibleKbId))));
    }

    @Test
    void listUserKbGrants_user_explicitOnly() {
        String userId = "u-pwm";
        String roleId = "role-pwm-user";
        String kbId = "kb-ops-1";
        stubTargetAccess(
                userId,
                "dept-pwm",
                Set.of(RoleType.USER),
                1,
                Set.of(kbId),
                Map.of(kbId, 1));

        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId(roleId).build()));
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId(roleId).kbId(kbId).permission("READ").build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("OPS-COB").deptId("dept-ops").build()));

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals("READ", out.get(0).getPermission());
        assertEquals("READ", out.get(0).getExplicitPermission());
        assertFalse(out.get(0).isImplicit());
    }

    @Test
    void listUserKbGrants_superAdmin_allManage() {
        String userId = "u-super";
        String kbId = "kb-pwm";
        stubTargetAccess(
                userId,
                "1",
                Set.of(RoleType.SUPER_ADMIN),
                3,
                Set.of(kbId),
                Map.of(kbId, 3));

        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId("role-super").build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("PWM-KB").deptId("dept-pwm").build()));

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals("MANAGE", out.get(0).getPermission());
        assertNull(out.get(0).getExplicitPermission());
        assertFalse(out.get(0).isImplicit());
    }

    @Test
    void listUserKbGrants_multipleRoles_explicitTakesMax() {
        String userId = "u-multi";
        String kbId = "kb-1";
        stubTargetAccess(
                userId,
                "dept-other",
                Set.of(RoleType.USER),
                2,
                Set.of(kbId),
                Map.of(kbId, 2));

        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId("r-read").build(),
                UserRoleDO.builder().userId(userId).roleId("r-manage").build()));
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId("r-read").kbId(kbId).permission("READ").build(),
                RoleKbRelationDO.builder().roleId("r-manage").kbId(kbId).permission("MANAGE").build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("KB-1").deptId("dept-ops").build()));

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals("MANAGE", out.get(0).getPermission());
        assertEquals("MANAGE", out.get(0).getExplicitPermission());
        assertEquals(2, out.get(0).getSourceRoleIds().size());
    }

    @Test
    void listUserKbGrants_skipsRelationWithNullPermission() {
        String userId = "u-null-perm";
        stubTargetAccess(userId, "dept-ops", Set.of(RoleType.USER), 0, Set.of(), Map.of());

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertTrue(out.isEmpty());
    }

    @Test
    void listUserKbGrants_skipsRelationWithUnknownPermission() {
        String userId = "u-unknown-perm";
        stubTargetAccess(userId, "dept-ops", Set.of(RoleType.USER), 0, Set.of(), Map.of());

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertTrue(out.isEmpty());
    }

    @Test
    void listUserKbGrants_skipsSoftDeletedKb() {
        String userId = "u-deleted-kb";
        stubTargetAccess(userId, "dept-ops", Set.of(RoleType.USER), 0, Set.of(), Map.of());

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertTrue(out.isEmpty());
    }

    @Test
    void listUserKbGrants_superAdminViewingFiccUserUsesTargetCeiling() {
        String userId = "u-ficc";
        String kbId = "OPS_KB";
        stubTargetAccess(userId, "FICC_DEPT", Set.of(RoleType.USER), 1, Set.of(kbId), Map.of(kbId, 1));

        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("OPS").deptId("OPS_DEPT").build()));

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals(1, out.get(0).getSecurityLevel());
    }

    @Test
    void listUserKbGrants_opsAdminViewingFiccUserDoesNotApplyCallerImplicitOrCeiling() {
        String userId = "u-ficc";
        String kbId = "FICC_KB";
        stubTargetAccess(userId, "FICC_DEPT", Set.of(RoleType.USER), 0, Set.of(kbId), Map.of(kbId, 0));

        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("FICC").deptId("FICC_DEPT").build()));

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals(0, out.get(0).getSecurityLevel());
        assertFalse(out.get(0).isImplicit());
    }

    @Test
    void listUserKbGrants_missingUserThrowsBeforeSubjectLookup() {
        when(userMapper.selectById("missing")).thenReturn(null);

        assertThrows(ClientException.class, () -> service.listUserKbGrants("missing"));
    }

    @Test
    void getRoleUsage_returnsUsersAndKbsWithDeptNames() {
        String roleId = "r1";
        when(roleMapper.selectById(roleId)).thenReturn(
                RoleDO.builder().id(roleId).name("OPS admin").roleType("DEPT_ADMIN").deptId("dept-ops").build());
        when(sysDeptMapper.selectById("dept-ops")).thenReturn(
                SysDeptDO.builder().id("dept-ops").deptName("Operation").build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId("u1").roleId(roleId).build()));
        when(userMapper.selectList(any())).thenReturn(List.of(
                UserDO.builder().id("u1").username("opsadmin").deptId("dept-ops").build()));
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId(roleId).kbId("kb1").permission("MANAGE").maxSecurityLevel(3).build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id("kb1").name("OPS-COB").deptId("dept-ops").build()));
        when(sysDeptMapper.selectList(any())).thenReturn(List.of(
                SysDeptDO.builder().id("dept-ops").deptName("Operation").build()));

        RoleUsageVO out = service.getRoleUsage(roleId);

        assertEquals("OPS admin", out.getRoleName());
        assertEquals("Operation", out.getDeptName());
        assertEquals(1, out.getUsers().size());
        assertEquals("opsadmin", out.getUsers().get(0).getUsername());
        assertEquals("Operation", out.getUsers().get(0).getDeptName());
        assertEquals(1, out.getKbs().size());
        assertEquals("MANAGE", out.getKbs().get(0).getPermission());
        assertEquals(3, out.getKbs().get(0).getMaxSecurityLevel());
    }

    @Test
    void getRoleUsage_missingRoleThrows() {
        when(roleMapper.selectById("nope")).thenReturn(null);
        assertThrows(ClientException.class, () -> service.getRoleUsage("nope"));
    }

    @Test
    void listDepartmentsTree_globalFirstThenByName() {
        SysDeptVO ops = new SysDeptVO("2", "OPS", "Operation", 3, 2, 3, null, null, false);
        SysDeptVO global = new SysDeptVO("1", "GLOBAL", "Global", 1, 1, 2, null, null, true);
        SysDeptVO ficc = new SysDeptVO("3", "FICC", "FICC", 1, 0, 1, null, null, false);
        when(sysDeptService.list(null)).thenReturn(List.of(ops, global, ficc));

        List<SysDeptVO> out = service.listDepartmentsTree();

        assertEquals(3, out.size());
        assertEquals("1", out.get(0).getId());
        assertEquals("FICC", out.get(1).getDeptName());
        assertEquals("Operation", out.get(2).getDeptName());
    }

    private KbAccessSubject stubTargetAccess(
            String userId,
            String deptId,
            Set<RoleType> roleTypes,
            int maxSecurityLevel,
            Set<String> readableKbIds,
            Map<String, Integer> levels) {
        UserDO user = UserDO.builder().id(userId).deptId(deptId).build();
        KbAccessSubject targetSubject = new KbAccessSubject(userId, deptId, roleTypes, maxSecurityLevel);
        when(userMapper.selectById(userId)).thenReturn(user);
        when(subjectFactory.forTargetUser(userId)).thenReturn(targetSubject);
        when(calculator.computeAccessibleKbIds(eq(targetSubject), eq(Permission.READ))).thenReturn(readableKbIds);
        when(calculator.computeMaxSecurityLevels(eq(targetSubject), any())).thenReturn(levels);
        return targetSubject;
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
