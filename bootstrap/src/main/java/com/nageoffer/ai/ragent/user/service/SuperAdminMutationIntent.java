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

import java.util.List;

/**
 * Last SUPER_ADMIN invariant 模拟器的输入语义（Decision 3-M）。
 * 4 种 mutation 各对应一个 record。
 */
public sealed interface SuperAdminMutationIntent
        permits SuperAdminMutationIntent.DeleteUser,
                SuperAdminMutationIntent.ReplaceUserRoles,
                SuperAdminMutationIntent.ChangeRoleType,
                SuperAdminMutationIntent.DeleteRole {

    /** 删除某个用户，该用户的所有 user-role 关联作废 */
    record DeleteUser(String userId) implements SuperAdminMutationIntent {}

    /** 用 newRoleIds 替换 userId 的角色集（对应 setUserRoles） */
    record ReplaceUserRoles(String userId, List<String> newRoleIds) implements SuperAdminMutationIntent {}

    /** 改变某角色的 role_type */
    record ChangeRoleType(String roleId, String newRoleType) implements SuperAdminMutationIntent {}

    /** 删除某角色，所有用到它的 user-role 关联作废 */
    record DeleteRole(String roleId) implements SuperAdminMutationIntent {}
}
