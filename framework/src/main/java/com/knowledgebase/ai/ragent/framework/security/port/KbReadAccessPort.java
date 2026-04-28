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

import com.knowledgebase.ai.ragent.framework.context.Permission;

import java.util.Collection;
import java.util.Map;

/**
 * 知识库读取侧权限端口（PR3 起 current-user only）。
 * RAG 检索路径和知识库列表查询的授权入口；admin-views-target 路径不走此 port。
 */
public interface KbReadAccessPort {

    /**
     * 获取**当前登录用户**的访问范围。
     * SUPER_ADMIN 返回 {@link AccessScope.All}；其他角色返回 {@link AccessScope.Ids}。
     * 调用方应在调用前自行守 {@code UserContext.hasUser()},否则实现层抛 ClientException。
     */
    AccessScope getAccessScope(Permission minPermission);

    /**
     * 校验当前用户对指定 KB 的 READ 权限,无权抛 ClientException。
     * SUPER_ADMIN 和系统态直接放行。
     */
    void checkReadAccess(String kbId);

    /**
     * 批量解析**当前登录用户**对一组 KB 的最高安全等级。
     * 返回 map 仅包含 kbIds 中用户实际拥有访问权的 KB。
     */
    Map<String, Integer> getMaxSecurityLevelsForKbs(Collection<String> kbIds);
}
