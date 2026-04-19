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

import com.nageoffer.ai.ragent.user.controller.vo.AccessRoleVO;
import com.nageoffer.ai.ragent.user.controller.vo.UserKbGrantVO;

import java.util.List;

/**
 * P1.3: 权限中心新页面专用的只读查询服务。
 * <p>
 * 设计文档 §六 P1 接口清单。所有方法 fail-closed：未登录用户一律 {@code ClientException}。
 */
public interface AccessService {

    /**
     * P1.3a: 按部门筛选可见角色。
     *
     * @param deptId         可选。null 表示不按部门过滤。
     * @param includeGlobal  是否在结果中额外包含 GLOBAL（dept_id='1'）角色。默认 true。
     *                       当 {@code deptId == '1'} 时此参数被忽略（避免重复）。
     */
    List<AccessRoleVO> listRoles(String deptId, boolean includeGlobal);

    /**
     * P1.3b: 目标用户对每个可访问 KB 的有效权限记录。
     * <p>
     * 四步算法（D13 / review v3 #2）：
     * <ol>
     *   <li>{@code getAccessibleKbIds(userId, READ)} 锁真相范围</li>
     *   <li>范围内 LEFT JOIN {@code t_user_role + t_role_kb_relation} → 每 KB 得 explicit
     *       权限 + sourceRoleIds（可为空）</li>
     *   <li>独立判 {@code implicit = isDeptAdmin(user) && kb.deptId == user.deptId}</li>
     *   <li>{@code effectivePermission = implicit ? MANAGE : explicit}；SUPER_ADMIN 一律
     *       MANAGE（与 {@code checkManageAccess} 对齐）</li>
     * </ol>
     */
    List<UserKbGrantVO> listUserKbGrants(String userId);
}
