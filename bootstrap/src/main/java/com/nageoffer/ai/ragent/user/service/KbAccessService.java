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

    /**
     * 清除指定用户的权限缓存
     */
    void evictCache(String userId);
}
