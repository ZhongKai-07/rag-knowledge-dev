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

package com.nageoffer.ai.ragent.user.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbAccessCalculatorTest {

    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleKbRelationMapper roleKbRelationMapper;
    @Mock private KbMetadataReader kbMetadataReader;

    @InjectMocks
    private KbAccessCalculator calculator;

    private static UserRoleDO ur(String roleId) {
        UserRoleDO r = new UserRoleDO();
        r.setRoleId(roleId);
        return r;
    }

    private static RoleKbRelationDO rel(String roleId, String kbId, Permission p, int level) {
        RoleKbRelationDO r = new RoleKbRelationDO();
        r.setRoleId(roleId);
        r.setKbId(kbId);
        r.setPermission(p.name());
        r.setMaxSecurityLevel(level);
        return r;
    }

    @Test
    void computeAccessibleKbIds_superAdmin_returnsAllKbIds() {
        KbAccessSubject s = new KbAccessSubject(
                "super1", "ANY", Set.of(RoleType.SUPER_ADMIN), 3);
        when(kbMetadataReader.listAllKbIds()).thenReturn(Set.of("KB1", "KB2", "KB3"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactlyInAnyOrder("KB1", "KB2", "KB3");
    }

    @Test
    void computeAccessibleKbIds_regularUser_returnsRbacOnly() {
        KbAccessSubject s = new KbAccessSubject(
                "u1", "DEPT_A", Set.of(RoleType.USER), 1);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("ROLE_A")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("ROLE_A", "KB1", Permission.READ, 1)));
        when(kbMetadataReader.filterExistingKbIds(any())).thenReturn(Set.of("KB1"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactly("KB1");
    }

    @Test
    void computeAccessibleKbIds_deptAdminSameDept_unionsRbacAndDeptKbs() {
        KbAccessSubject s = new KbAccessSubject(
                "admin1", "DEPT_OPS", Set.of(RoleType.DEPT_ADMIN), 2);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("OPS_ADMIN")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("OPS_ADMIN", "RBAC_KB", Permission.READ, 2)));
        when(kbMetadataReader.filterExistingKbIds(any())).thenReturn(Set.of("RBAC_KB"));
        when(kbMetadataReader.listKbIdsByDeptId("DEPT_OPS"))
                .thenReturn(Set.of("OPS_DEPT_KB"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactlyInAnyOrder("RBAC_KB", "OPS_DEPT_KB");
    }

    @Test
    void computeAccessibleKbIds_deptAdminMissingDept_returnsRbacOnly() {
        KbAccessSubject s = new KbAccessSubject(
                "admin1", null, Set.of(RoleType.DEPT_ADMIN), 2);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("OPS_ADMIN")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("OPS_ADMIN", "RBAC_KB", Permission.READ, 1)));
        when(kbMetadataReader.filterExistingKbIds(any())).thenReturn(Set.of("RBAC_KB"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactly("RBAC_KB");
    }

    @Test
    void computeMaxSecurityLevels_superAdmin_returnsThreeForAllRequestedKbs() {
        KbAccessSubject s = new KbAccessSubject(
                "super1", "ANY", Set.of(RoleType.SUPER_ADMIN), 3);

        Map<String, Integer> result = calculator.computeMaxSecurityLevels(
                s, Set.of("KB1", "KB2"));

        assertThat(result).containsEntry("KB1", 3).containsEntry("KB2", 3);
    }

    @Test
    void computeMaxSecurityLevels_regularUser_returnsRbacCeilingPerKb() {
        KbAccessSubject s = new KbAccessSubject(
                "u1", "DEPT_A", Set.of(RoleType.USER), 1);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("ROLE_A")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        rel("ROLE_A", "KB1", Permission.READ, 1),
                        rel("ROLE_A", "KB2", Permission.READ, 0)));

        Map<String, Integer> result = calculator.computeMaxSecurityLevels(
                s, Set.of("KB1", "KB2"));

        assertThat(result).containsEntry("KB1", 1).containsEntry("KB2", 0);
    }

    @Test
    void computeMaxSecurityLevels_deptAdmin_unionsCeilingWithSameDeptKbs() {
        KbAccessSubject s = new KbAccessSubject(
                "admin1", "DEPT_OPS", Set.of(RoleType.DEPT_ADMIN), 2);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("OPS_ADMIN")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("OPS_ADMIN", "RBAC_KB", Permission.READ, 1)));
        when(kbMetadataReader.filterKbIdsByDept(any(), eq("DEPT_OPS")))
                .thenReturn(Set.of("OPS_DEPT_KB"));

        Map<String, Integer> result = calculator.computeMaxSecurityLevels(
                s, Set.of("RBAC_KB", "OPS_DEPT_KB"));

        // RBAC ceiling 1 on RBAC_KB; dept admin ceiling 2 on OPS_DEPT_KB; takes Math::max where both apply
        assertThat(result).containsEntry("RBAC_KB", 1).containsEntry("OPS_DEPT_KB", 2);
    }
}
