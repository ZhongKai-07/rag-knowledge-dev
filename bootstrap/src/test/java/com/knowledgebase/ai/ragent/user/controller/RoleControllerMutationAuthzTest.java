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

package com.knowledgebase.ai.ragent.user.controller;

import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.CurrentUserProbe;
import com.knowledgebase.ai.ragent.framework.security.port.UserAdminGuard;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import com.knowledgebase.ai.ragent.user.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleControllerMutationAuthzTest {

    private RoleService roleService;
    private CurrentUserProbe currentUser;
    private UserAdminGuard userAdminGuard;
    private RoleController controller;

    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        currentUser = mock(CurrentUserProbe.class);
        userAdminGuard = mock(UserAdminGuard.class);
        controller = new RoleController(roleService, currentUser, userAdminGuard);
    }

    @Test
    void createRole_checksDeptScopeBeforeCallingService() {
        RoleController.RoleCreateRequest request = new RoleController.RoleCreateRequest();
        request.setName("OPS User");
        request.setRoleType("USER");
        request.setMaxSecurityLevel(1);
        request.setDeptId("dept-ops");

        controller.createRole(request);

        verify(userAdminGuard).checkRoleMutation("dept-ops");
        verify(roleService).createRole("OPS User", null, "USER", 1, "dept-ops");
    }

    @Test
    void createRole_deptAdminCannotCreateSuperAdminRole() {
        RoleController.RoleCreateRequest request = new RoleController.RoleCreateRequest();
        request.setName("Escalation");
        request.setRoleType("SUPER_ADMIN");
        request.setDeptId("dept-ops");
        when(currentUser.isSuperAdmin()).thenReturn(false);

        ClientException ex = assertThrows(ClientException.class, () -> controller.createRole(request));

        assertEquals("DEPT_ADMIN 不可创建 SUPER_ADMIN 角色", ex.getMessage());
        verify(roleService, never()).createRole(
                request.getName(),
                request.getDescription(),
                request.getRoleType(),
                request.getMaxSecurityLevel(),
                request.getDeptId());
    }

    @Test
    void updateRole_usesPersistedRoleDeptForMutationCheck() {
        RoleController.RoleCreateRequest request = new RoleController.RoleCreateRequest();
        request.setName("OPS User v2");
        request.setRoleType("USER");
        request.setDeptId("dept-pwm");
        when(roleService.getRoleById("role-1")).thenReturn(
                RoleDO.builder().id("role-1").deptId("dept-ops").roleType("USER").build());

        controller.updateRole("role-1", request);

        verify(userAdminGuard).checkRoleMutation("dept-ops");
        verify(roleService).updateRole("role-1", "OPS User v2", null, "USER", null, "dept-pwm");
    }

    @Test
    void deleteRole_deptAdminCannotDeleteSuperAdminRole() {
        when(roleService.getRoleById("role-super")).thenReturn(
                RoleDO.builder().id("role-super").deptId("dept-ops").roleType("SUPER_ADMIN").build());
        when(currentUser.isSuperAdmin()).thenReturn(false);

        ClientException ex = assertThrows(ClientException.class, () -> controller.deleteRole("role-super"));

        assertEquals("DEPT_ADMIN 不可删除 SUPER_ADMIN 角色", ex.getMessage());
        verify(roleService, never()).deleteRole("role-super");
    }
}
