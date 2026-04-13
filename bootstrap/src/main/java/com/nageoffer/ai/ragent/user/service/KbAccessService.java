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

import com.nageoffer.ai.ragent.framework.context.Permission;

import java.util.Set;

/**
 * 知识库访问权限服务。
 *
 * <p>统一承载三种判断：
 * <ul>
 *     <li>"当前用户能访问哪些知识库"（{@link #getAccessibleKbIds}）</li>
 *     <li>"当前用户对某 KB 有读权限吗"（{@link #checkAccess}）</li>
 *     <li>"当前用户对某 KB 有管理权吗"（{@link #checkManageAccess}）</li>
 * </ul>
 *
 * <p>所有方法都从 {@code UserContext} 读取当前用户。{@link com.nageoffer.ai.ragent.framework.context.RoleType#SUPER_ADMIN}
 * 在实现里统一放行，调用方不用再手动判 {@code "admin".equals(...)}。
 */
public interface KbAccessService {

    /**
     * 获取当前用户对指定最低权限可访问的所有知识库 ID。
     * SUPER_ADMIN 返回全量可见 KB。
     */
    Set<String> getAccessibleKbIds(String userId, Permission minPermission);

    /**
     * 等价于 {@code getAccessibleKbIds(userId, READ)}，保持旧调用点兼容。
     */
    default Set<String> getAccessibleKbIds(String userId) {
        return getAccessibleKbIds(userId, Permission.READ);
    }

    /**
     * 校验当前用户对指定知识库的 READ 权限，无权抛 {@code ClientException}。
     * SUPER_ADMIN 直接放行。系统态（无登录态）也直接放行。
     */
    void checkAccess(String kbId);

    /**
     * 校验当前用户对指定知识库的 MANAGE 权限（写/删/授权）。
     * SUPER_ADMIN 放行；DEPT_ADMIN 仅当 {@code kb.dept_id == user.dept_id} 放行；其他抛 {@code ClientException}。
     */
    void checkManageAccess(String kbId);

    /**
     * 当前上下文是否是 SUPER_ADMIN。展示层用（例如列表是否全量）。
     */
    boolean isSuperAdmin();

    /** 校验当前用户是 SUPER_ADMIN 或 DEPT_ADMIN，否则抛异常 */
    void checkAnyAdminAccess();

    /**
     * 清除指定用户的权限缓存
     */
    void evictCache(String userId);

    /**
     * 获取用户对指定 KB 的最高安全等级。
     * <ul>
     *   <li>SUPER_ADMIN → 3</li>
     *   <li>DEPT_ADMIN 且 kb.dept_id == self.dept_id → MAX(自身角色天花板)</li>
     *   <li>其他 → MAX(t_role_kb_relation.max_security_level) through user's roles</li>
     *   <li>无权限 → 0（防御性兜底，不应到达检索阶段）</li>
     * </ul>
     */
    Integer getMaxSecurityLevelForKb(String userId, String kbId);

    // === PR3 新增：用户管理授权 ===

    /**
     * 创建用户授权。SUPER_ADMIN 任何 deptId；
     * DEPT_ADMIN 仅 targetDeptId == self.deptId 且 roleIds 中不含 role_type=SUPER_ADMIN 的角色。
     */
    void checkCreateUserAccess(String targetDeptId, java.util.List<String> roleIds);

    /**
     * 改/删用户授权。SUPER_ADMIN 任何 target；
     * DEPT_ADMIN 仅当 target.deptId == self.deptId。
     */
    void checkUserManageAccess(String targetUserId);

    /**
     * 分配角色授权。
     * SUPER_ADMIN 任何；DEPT_ADMIN 仅当 target.deptId == self.deptId
     * 且 newRoleIds 中不含 role_type=SUPER_ADMIN 的角色。
     */
    void checkAssignRolesAccess(String targetUserId, java.util.List<String> newRoleIds);

    /**
     * 校验 DEPT_ADMIN 分配角色的合法性：
     * - 不可分配 SUPER_ADMIN 角色
     * - 不可分配 DEPT_ADMIN 角色
     * - 不可分配 maxSecurityLevel > 自身天花板的角色
     * SUPER_ADMIN 不受限制。
     */
    void validateRoleAssignment(java.util.List<String> roleIds);

    /**
     * 当前是否是 DEPT_ADMIN（任一部门）。
     */
    boolean isDeptAdmin();

    // === PR3 新增：KB / 文档管理授权 ===

    /**
     * 创建 KB 时的权限解析器（Decision 3-H）。
     * - 未登录：抛 ClientException("未登录用户不可创建知识库")；无 GLOBAL fallback
     * - SUPER_ADMIN：返回 requestedDeptId，空则 fallback GLOBAL_DEPT_ID ("1")
     * - DEPT_ADMIN：强制 self.deptId；若 requestedDeptId 非空且 != self.deptId 抛 403
     * - USER：抛 403
     */
    String resolveCreateKbDeptId(String requestedDeptId);

    /**
     * 文档级管理权：doc → kb → checkManageAccess(kb.id)。
     */
    void checkDocManageAccess(String docId);

    /**
     * 文档 security_level 修改专用（目前等同 checkDocManageAccess，保留独立方法以便未来加 level-specific 规则）。
     */
    void checkDocSecurityLevelAccess(String docId, int newLevel);

    // === Last SUPER_ADMIN 系统级硬不变量（Decision 3-M）===

    /**
     * 当前系统内有效 SUPER_ADMIN 用户数量。
     */
    int countActiveSuperAdmins();

    /**
     * 判断某用户当前是否有任一有效 SUPER_ADMIN 角色。
     */
    boolean isUserSuperAdmin(String userId);

    /**
     * Post-mutation 模拟器：返回 mutation 执行后剩余的有效 SUPER_ADMIN 用户数量。
     * 调用方用 {@code < 1} 判断是否拒绝。
     */
    int simulateActiveSuperAdminCountAfter(SuperAdminMutationIntent intent);

    /** 校验当前用户是否有权管理指定 KB 的角色绑定。SUPER_ADMIN 任意 KB；DEPT_ADMIN 仅 kb.dept_id == self.dept_id。 */
    void checkKbRoleBindingAccess(String kbId);
}
