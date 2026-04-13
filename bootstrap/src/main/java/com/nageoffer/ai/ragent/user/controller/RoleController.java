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
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @GetMapping("/role")
    public Result<List<RoleDO>> listRoles() {
        kbAccessService.checkAnyAdminAccess();
        return Results.success(roleService.listRoles());
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
        // PR3 新增
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
}
