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

package com.nageoffer.ai.ragent.user.controller;

import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0.1: `GET /role` 对 DEPT_ADMIN 过滤掉 SUPER_ADMIN 角色的信息泄露修复。
 * 设计依据：docs/dev/design/2026-04-19-access-center-redesign.md §八 P0 切片
 */
class RoleControllerListFilterTest {

    private RoleService roleService;
    private KbAccessService kbAccessService;
    private RoleController controller;

    @BeforeEach
    void setUp() {
        roleService = mock(RoleService.class);
        kbAccessService = mock(KbAccessService.class);
        controller = new RoleController(roleService, kbAccessService);

        doNothing().when(kbAccessService).checkAnyAdminAccess();
        when(roleService.listRoles()).thenReturn(List.of(
                role("1", "超级管理员", RoleType.SUPER_ADMIN),
                role("2", "OPS admin", RoleType.DEPT_ADMIN),
                role("3", "普通用户", RoleType.USER),
                role("4", "FICC User", RoleType.USER)));
    }

    @Test
    void superAdmin_sees_all_roles_including_super_admin() {
        when(kbAccessService.isSuperAdmin()).thenReturn(true);

        Result<List<RoleDO>> result = controller.listRoles();

        assertEquals(4, result.getData().size());
        assertTrue(result.getData().stream()
                .anyMatch(r -> RoleType.SUPER_ADMIN.name().equals(r.getRoleType())),
                "SUPER_ADMIN must see the super_admin role entry");
    }

    @Test
    void deptAdmin_does_not_see_super_admin_role() {
        when(kbAccessService.isSuperAdmin()).thenReturn(false);

        Result<List<RoleDO>> result = controller.listRoles();

        assertEquals(3, result.getData().size());
        assertFalse(result.getData().stream()
                .anyMatch(r -> RoleType.SUPER_ADMIN.name().equals(r.getRoleType())),
                "DEPT_ADMIN must not see SUPER_ADMIN role entries (information disclosure fix)");
        assertTrue(result.getData().stream()
                .anyMatch(r -> RoleType.DEPT_ADMIN.name().equals(r.getRoleType())),
                "DEPT_ADMIN roles should remain visible");
        assertTrue(result.getData().stream()
                .anyMatch(r -> RoleType.USER.name().equals(r.getRoleType())),
                "USER roles should remain visible");
    }

    private static RoleDO role(String id, String name, RoleType type) {
        RoleDO r = new RoleDO();
        r.setId(id);
        r.setName(name);
        r.setRoleType(type.name());
        return r;
    }
}
