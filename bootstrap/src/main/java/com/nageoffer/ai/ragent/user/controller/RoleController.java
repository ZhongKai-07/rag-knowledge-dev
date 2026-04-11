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
import com.nageoffer.ai.ragent.user.service.RoleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @SaCheckRole("SUPER_ADMIN")
    @PostMapping("/role")
    public Result<String> createRole(@RequestBody RoleCreateRequest request) {
        String id = roleService.createRole(request.getName(), request.getDescription());
        return Results.success(id);
    }

    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/role/{roleId}")
    public Result<Void> updateRole(@PathVariable String roleId, @RequestBody RoleCreateRequest request) {
        roleService.updateRole(roleId, request.getName(), request.getDescription());
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @DeleteMapping("/role/{roleId}")
    public Result<Void> deleteRole(@PathVariable String roleId) {
        roleService.deleteRole(roleId);
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @GetMapping("/role")
    public Result<List<RoleDO>> listRoles() {
        return Results.success(roleService.listRoles());
    }

    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/role/{roleId}/knowledge-bases")
    public Result<Void> setRoleKnowledgeBases(@PathVariable String roleId,
                                               @RequestBody List<String> kbIds) {
        roleService.setRoleKnowledgeBases(roleId, kbIds);
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @GetMapping("/role/{roleId}/knowledge-bases")
    public Result<List<String>> getRoleKnowledgeBases(@PathVariable String roleId) {
        return Results.success(roleService.getRoleKnowledgeBaseIds(roleId));
    }

    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/user/{userId}/roles")
    public Result<Void> setUserRoles(@PathVariable String userId, @RequestBody List<String> roleIds) {
        roleService.setUserRoles(userId, roleIds);
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @GetMapping("/user/{userId}/roles")
    public Result<List<RoleDO>> getUserRoles(@PathVariable String userId) {
        return Results.success(roleService.getUserRoles(userId));
    }

    @Data
    public static class RoleCreateRequest {
        private String name;
        private String description;
    }
}
