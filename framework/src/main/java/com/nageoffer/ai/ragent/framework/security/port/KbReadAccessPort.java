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

package com.nageoffer.ai.ragent.framework.security.port;

import com.nageoffer.ai.ragent.framework.context.Permission;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 知识库读取侧权限端口。
 * RAG 检索路径和知识库列表查询的授权入口。
 */
public interface KbReadAccessPort {

    /**
     * 获取当前用户的访问范围。
     * SUPER_ADMIN 返回 {@link AccessScope.All}；其他角色返回 {@link AccessScope.Ids}。
     * 未登录时调用方应传入 empty Ids（不调用此方法）。
     *
     * @param userId        当前用户 ID
     * @param minPermission 最低所需权限
     */
    AccessScope getAccessScope(String userId, Permission minPermission);

    /**
     * 校验当前用户对指定 KB 的 READ 权限，无权抛 ClientException。
     * SUPER_ADMIN 和系统态直接放行。
     */
    void checkReadAccess(String kbId);

    /**
     * 批量解析当前用户对一组 KB 的最高安全等级。
     * 返回 map 仅包含 kbIds 中用户实际拥有访问权的 KB。
     *
     * @param userId 当前用户 ID
     * @param kbIds  待解析的 KB ID 集合
     */
    Map<String, Integer> getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds);

    /**
     * @deprecated 迁移过渡用，新代码改用 {@link #getAccessScope}
     */
    @Deprecated
    default Set<String> getAccessibleKbIds(String userId) {
        throw new UnsupportedOperationException("use getAccessScope()");
    }
}
