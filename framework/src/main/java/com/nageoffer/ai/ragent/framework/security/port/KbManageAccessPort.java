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

/**
 * 知识库写/管理侧权限端口。
 * knowledge 域 controller 和 service 的授权入口。
 */
public interface KbManageAccessPort {

    /** 校验当前用户对指定 KB 的 MANAGE 权限，无权抛 ClientException。*/
    void checkManageAccess(String kbId);

    /** 文档级管理权：doc → kb → checkManageAccess(kb.id)。*/
    void checkDocManageAccess(String docId);

    /**
     * 文档 security_level 修改专用。
     * 目前等同 checkDocManageAccess，保留独立方法以便未来加 level-specific 规则。
     */
    void checkDocSecurityLevelAccess(String docId, int newLevel);

    /** 校验当前用户是否有权管理指定 KB 的角色绑定。*/
    void checkKbRoleBindingAccess(String kbId);

    /**
     * 创建 KB 时的 deptId 解析器。
     * SUPER_ADMIN 返回 requestedDeptId（空则 fallback GLOBAL_DEPT_ID）；
     * DEPT_ADMIN 强制 self.deptId；USER 和未登录 → 抛 ClientException。
     */
    String resolveCreateKbDeptId(String requestedDeptId);
}
