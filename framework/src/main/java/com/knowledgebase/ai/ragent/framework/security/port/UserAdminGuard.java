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

package com.knowledgebase.ai.ragent.framework.security.port;

import java.util.List;

/**
 * 用户与角色管理操作的授权守卫。
 * user/role/dept controller 的授权入口。
 */
public interface UserAdminGuard {

    /** 校验当前用户是 SUPER_ADMIN 或 DEPT_ADMIN，否则抛异常。*/
    void checkAnyAdminAccess();

    /**
     * 创建用户授权。
     * SUPER_ADMIN 任何 deptId；DEPT_ADMIN 仅 targetDeptId == self.deptId 且不含 SUPER_ADMIN 角色。
     */
    void checkCreateUserAccess(String targetDeptId, List<String> roleIds);

    /** 改/删用户授权。DEPT_ADMIN 仅当 target.deptId == self.deptId。*/
    void checkUserManageAccess(String targetUserId);

    /**
     * 角色 CRUD / delete-preview 授权。
     * SUPER_ADMIN 可操作任意角色;DEPT_ADMIN 仅可操作本部门且非 GLOBAL 的角色。
     */
    void checkRoleMutation(String roleDeptId);

    /**
     * 分配角色授权。
     * SUPER_ADMIN 任何；DEPT_ADMIN 仅当 target.deptId == self.deptId 且 newRoleIds 无 SUPER_ADMIN 角色。
     */
    void checkAssignRolesAccess(String targetUserId, List<String> newRoleIds);

    /** 校验 DEPT_ADMIN 分配角色的合法性（不可分配 SUPER_ADMIN/DEPT_ADMIN 角色，不可超自身天花板）。*/
    void validateRoleAssignment(List<String> roleIds);
}
