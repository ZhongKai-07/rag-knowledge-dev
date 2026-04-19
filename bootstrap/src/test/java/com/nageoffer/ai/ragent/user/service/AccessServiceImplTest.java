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
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.vo.AccessRoleVO;
import com.nageoffer.ai.ragent.user.controller.vo.RoleUsageVO;
import com.nageoffer.ai.ragent.user.controller.vo.UserKbGrantVO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.impl.AccessServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P1.3a: GET /access/roles 单测 */
class AccessServiceImplTest {

    private RoleMapper roleMapper;
    private SysDeptMapper sysDeptMapper;
    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RoleKbRelationMapper roleKbRelationMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private KbAccessService kbAccessService;
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
        kbAccessService = mock(KbAccessService.class);
        service = new AccessServiceImpl(
                roleMapper,
                sysDeptMapper,
                userMapper,
                userRoleMapper,
                roleKbRelationMapper,
                knowledgeBaseMapper,
                kbAccessService);
    }

    /** deptId + includeGlobal 组合：返回本部门角色 + GLOBAL 角色，且 deptName 回填 */
    @Test
    void listRoles_deptIdAndGlobal_returnsBoth() {
        RoleDO opsAdmin = RoleDO.builder().id("r1").name("OPS admin").roleType("DEPT_ADMIN").deptId("dept-ops").build();
        RoleDO common = RoleDO.builder().id("r2").name("普通用户").roleType("USER").deptId("1").build();
        when(roleMapper.selectList(any())).thenReturn(List.of(opsAdmin, common));
        when(sysDeptMapper.selectList(any())).thenReturn(List.of(
                SysDeptDO.builder().id("dept-ops").deptName("Operation").build(),
                SysDeptDO.builder().id("1").deptName("全局部门").build()));

        List<AccessRoleVO> out = service.listRoles("dept-ops", true);

        assertEquals(2, out.size());
        AccessRoleVO vo1 = out.stream().filter(v -> "r1".equals(v.getId())).findFirst().orElseThrow();
        assertEquals("Operation", vo1.getDeptName());
        AccessRoleVO vo2 = out.stream().filter(v -> "r2".equals(v.getId())).findFirst().orElseThrow();
        assertEquals("全局部门", vo2.getDeptName());
    }

    /** includeGlobal=false 不包含 GLOBAL 角色（mapper 只被查 dept-pwm，测的是参数组装） */
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

    /** 空结果稳健返回空列表 */
    @Test
    void listRoles_noMatch_returnsEmpty() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        List<AccessRoleVO> out = service.listRoles("dept-x", true);

        assertTrue(out.isEmpty());
    }

    /** deptId=null + includeGlobal=true → 返回全部（不按部门过滤）*/
    @Test
    void listRoles_noDeptFilter_returnsAll() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                RoleDO.builder().id("r1").name("OPS admin").roleType("DEPT_ADMIN").deptId("dept-ops").build(),
                RoleDO.builder().id("r2").name("普通用户").roleType("USER").deptId("1").build()));
        when(sysDeptMapper.selectList(any())).thenReturn(List.of(
                SysDeptDO.builder().id("dept-ops").deptName("Operation").build(),
                SysDeptDO.builder().id("1").deptName("全局部门").build()));

        List<AccessRoleVO> out = service.listRoles(null, true);

        assertEquals(2, out.size());
        List<String> ids = out.stream().map(AccessRoleVO::getId).collect(Collectors.toList());
        assertTrue(ids.contains("r1"));
        assertTrue(ids.contains("r2"));
    }

    // ---------- P1.3b: listUserKbGrants D13 四步算法覆盖 ----------

    /**
     * DEPT_ADMIN 对本部门 KB：显式绑定 READ + implicit=true → effective=MANAGE（implicit 强升权）。
     * 同时验证 explicitPermission=READ 仍被保留（便于审计）。
     */
    @Test
    void listUserKbGrants_deptAdmin_implicitUpgradesExplicit() {
        String userId = "u-ops-admin";
        String roleId = "role-ops-admin";
        String kbId = "kb-ops-1";
        String deptId = "dept-ops";

        when(userMapper.selectById(userId)).thenReturn(
                UserDO.builder().id(userId).deptId(deptId).build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId(roleId).build()));
        when(roleMapper.selectList(any())).thenReturn(List.of(
                RoleDO.builder().id(roleId).roleType("DEPT_ADMIN").build()));
        // 所有 RoleKbRelation 查询都返回同一显式绑定
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId(roleId).kbId(kbId).permission("READ").build()));
        // 所有 KnowledgeBase 查询都返回本 KB（同部门 + 范围内 enrichment）
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("OPS-COB").deptId(deptId).build()));
        when(kbAccessService.getMaxSecurityLevelForKb(userId, kbId)).thenReturn(3);

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        UserKbGrantVO g = out.get(0);
        assertEquals("MANAGE", g.getPermission());
        assertEquals("READ", g.getExplicitPermission()); // 审计字段保留显式来源
        assertTrue(g.isImplicit());
        assertEquals(List.of(roleId), g.getSourceRoleIds());
        assertEquals(3, g.getSecurityLevel());
    }

    /** 普通 USER 对有显式绑定的 KB：implicit=false，effective=explicit */
    @Test
    void listUserKbGrants_user_explicitOnly() {
        String userId = "u-pwm";
        String roleId = "role-pwm-user";
        String kbId = "kb-ops-1";

        when(userMapper.selectById(userId)).thenReturn(
                UserDO.builder().id(userId).deptId("dept-pwm").build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId(roleId).build()));
        when(roleMapper.selectList(any())).thenReturn(List.of(
                RoleDO.builder().id(roleId).roleType("USER").build()));
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId(roleId).kbId(kbId).permission("READ").build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("OPS-COB").deptId("dept-ops").build()));
        when(kbAccessService.getMaxSecurityLevelForKb(userId, kbId)).thenReturn(1);

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals("READ", out.get(0).getPermission());
        assertEquals("READ", out.get(0).getExplicitPermission());
        assertFalse(out.get(0).isImplicit());
    }

    /** SUPER_ADMIN 对任意 KB：返回 MANAGE（与 checkManageAccess 对齐），explicit 可能为 null */
    @Test
    void listUserKbGrants_superAdmin_allManage() {
        String userId = "u-super";
        String kbId = "kb-pwm";

        when(userMapper.selectById(userId)).thenReturn(
                UserDO.builder().id(userId).deptId("1").build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId("role-super").build()));
        when(roleMapper.selectList(any())).thenReturn(List.of(
                RoleDO.builder().id("role-super").roleType("SUPER_ADMIN").build()));
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of());
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("PWM-KB").deptId("dept-pwm").build()));
        when(kbAccessService.getMaxSecurityLevelForKb(userId, kbId)).thenReturn(3);

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals("MANAGE", out.get(0).getPermission());
        assertNull(out.get(0).getExplicitPermission()); // 无显式绑定但仍是 MANAGE
        assertFalse(out.get(0).isImplicit()); // implicit 仅对 DEPT_ADMIN 同部门生效
    }

    /** 多角色命中同一 KB：explicit 取最高（READ + MANAGE → MANAGE）*/
    @Test
    void listUserKbGrants_multipleRoles_explicitTakesMax() {
        String userId = "u-multi";
        String kbId = "kb-1";

        when(userMapper.selectById(userId)).thenReturn(
                UserDO.builder().id(userId).deptId("dept-other").build());
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRoleDO.builder().userId(userId).roleId("r-read").build(),
                UserRoleDO.builder().userId(userId).roleId("r-manage").build()));
        when(roleMapper.selectList(any())).thenReturn(List.of(
                RoleDO.builder().id("r-read").roleType("USER").build(),
                RoleDO.builder().id("r-manage").roleType("USER").build()));
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of(
                RoleKbRelationDO.builder().roleId("r-read").kbId(kbId).permission("READ").build(),
                RoleKbRelationDO.builder().roleId("r-manage").kbId(kbId).permission("MANAGE").build()));
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id(kbId).name("KB-1").deptId("dept-ops").build()));
        when(kbAccessService.getMaxSecurityLevelForKb(userId, kbId)).thenReturn(2);

        List<UserKbGrantVO> out = service.listUserKbGrants(userId);

        assertEquals(1, out.size());
        assertEquals("MANAGE", out.get(0).getPermission());
        assertEquals("MANAGE", out.get(0).getExplicitPermission());
        assertEquals(2, out.get(0).getSourceRoleIds().size());
    }

    // ---------- P1.3c: getRoleUsage ----------

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

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
