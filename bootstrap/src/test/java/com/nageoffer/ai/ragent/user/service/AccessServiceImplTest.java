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
import com.nageoffer.ai.ragent.user.controller.vo.AccessRoleVO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.service.impl.AccessServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P1.3a: GET /access/roles 单测 */
class AccessServiceImplTest {

    private RoleMapper roleMapper;
    private SysDeptMapper sysDeptMapper;
    private AccessServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(RoleDO.class);
        initTableInfo(SysDeptDO.class);
        roleMapper = mock(RoleMapper.class);
        sysDeptMapper = mock(SysDeptMapper.class);
        service = new AccessServiceImpl(roleMapper, sysDeptMapper);
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

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
