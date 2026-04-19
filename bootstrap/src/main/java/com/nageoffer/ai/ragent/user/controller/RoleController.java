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

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final KbAccessService kbAccessService;

    @SaCheckRole("SUPER_ADMIN")
    @PostMapping("/role")
    public Result<String> createRole(@RequestBody RoleCreateRequest request) {
        String id = roleService.createRole(
                request.getName(),
                request.getDescription(),
                request.getRoleType(),
                request.getMaxSecurityLevel());
        return Results.success(id);
    }

    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/role/{roleId}")
    public Result<Void> updateRole(@PathVariable("roleId") String roleId, @RequestBody RoleCreateRequest request) {
        roleService.updateRole(
                roleId,
                request.getName(),
                request.getDescription(),
                request.getRoleType(),
                request.getMaxSecurityLevel());
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @DeleteMapping("/role/{roleId}")
    public Result<Void> deleteRole(@PathVariable("roleId") String roleId) {
        roleService.deleteRole(roleId);
        return Results.success();
    }

    /**
     * P0.2: 删除角色前的影响面预览。
     * <p>
     * 设计依据：docs/dev/design/2026-04-19-access-center-redesign.md §六 P0 后端清单
     * 局限：lostKbIds 仅基于显式 role 链推导。如果删除某 DEPT_ADMIN 类型角色导致用户失去
     * DEPT_ADMIN 身份（同部门隐式 MANAGE 权限随之消失），不会反映在本预览。
     * P1 的 GET /access/users/{userId}/kb-grants 提供完整算法（D13）。
     */
    @SaCheckRole("SUPER_ADMIN")
    @GetMapping("/role/{roleId}/delete-preview")
    public Result<RoleDeletePreviewVO> getRoleDeletePreview(@PathVariable("roleId") String roleId) {
        return Results.success(roleService.getRoleDeletePreview(roleId));
    }

    @GetMapping("/role")
    public Result<List<RoleDO>> listRoles() {
        kbAccessService.checkAnyAdminAccess();
        List<RoleDO> roles = roleService.listRoles();
        // P0.1: DEPT_ADMIN must not see SUPER_ADMIN roles (information disclosure fix).
        // Per design doc §6 P0 scope: role_type blacklist only; dept-based filtering deferred to P1
        // when t_role.dept_id exists (see docs/dev/design/2026-04-19-access-center-redesign.md §一·六).
        if (!kbAccessService.isSuperAdmin()) {
            roles = roles.stream()
                    .filter(r -> !RoleType.SUPER_ADMIN.name().equals(r.getRoleType()))
                    .collect(Collectors.toList());
        }
        return Results.success(roles);
    }

    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/role/{roleId}/knowledge-bases")
    public Result<Void> setRoleKnowledgeBases(
            @PathVariable("roleId") String roleId, @RequestBody List<RoleKbBindingRequest> bindings) {
        roleService.setRoleKnowledgeBases(roleId, bindings);
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @GetMapping("/role/{roleId}/knowledge-bases")
    public Result<List<RoleKbBindingRequest>> getRoleKnowledgeBases(@PathVariable("roleId") String roleId) {
        return Results.success(roleService.getRoleKnowledgeBases(roleId));
    }

    @PutMapping("/user/{userId}/roles")
    public Result<Void> setUserRoles(
            @PathVariable("userId") String userId, @RequestBody List<String> roleIds) {
        kbAccessService.checkAssignRolesAccess(userId, roleIds);
        roleService.setUserRoles(userId, roleIds);
        return Results.success();
    }

    @GetMapping("/user/{userId}/roles")
    public Result<List<RoleDO>> getUserRoles(@PathVariable("userId") String userId) {
        kbAccessService.checkAnyAdminAccess();
        kbAccessService.checkUserManageAccess(userId);
        return Results.success(roleService.getUserRoles(userId));
    }

    @Data
    public static class RoleCreateRequest {
        private String name;
        private String description;
        private String roleType;
        private Integer maxSecurityLevel;
    }

    @Data
    public static class RoleKbBindingRequest {
        private String kbId;
        /** 权限级别：READ / WRITE / MANAGE */
        private String permission;
        /** 该角色对该 KB 的最高安全等级（0-3），可选，默认取 role.maxSecurityLevel */
        private Integer maxSecurityLevel;
    }

    /** P0.2 删除角色影响面预览返回体 */
    @Data
    public static class RoleDeletePreviewVO {
        private String roleId;
        private String roleName;
        private List<AffectedUser> affectedUsers;
        private List<AffectedKb> affectedKbs;
        private List<UserKbDiff> userKbDiff;
    }

    @Data
    public static class AffectedUser {
        private String userId;
        private String username;
        private String deptId;
        private String deptName;
    }

    @Data
    public static class AffectedKb {
        private String kbId;
        private String kbName;
        private String deptId;
        private String deptName;
    }

    /** 删除该角色后，某用户将失去访问的 KB 列表（仅基于显式 role 链推导） */
    @Data
    public static class UserKbDiff {
        private String userId;
        private String username;
        private List<String> lostKbIds;
        private List<String> lostKbNames;
    }
}
