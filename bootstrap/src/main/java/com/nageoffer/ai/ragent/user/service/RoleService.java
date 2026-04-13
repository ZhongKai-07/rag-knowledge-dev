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

import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.user.controller.RoleController.RoleKbBindingRequest;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import java.util.List;

public interface RoleService {

    /** 创建角色（含 roleType 和 maxSecurityLevel） */
    String createRole(String name, String description, String roleType, Integer maxSecurityLevel);

    void updateRole(String roleId, String name, String description);

    /** PR3：支持同时更新 roleType 和 maxSecurityLevel（含 Last-SUPER_ADMIN 前置校验） */
    void updateRole(String roleId, String name, String description, String roleType, Integer maxSecurityLevel);

    void deleteRole(String roleId);

    List<RoleDO> listRoles();

    /** 设置角色关联的知识库列表（含权限级别，全量替换） */
    void setRoleKnowledgeBases(String roleId, List<RoleKbBindingRequest> bindings);

    /** 获取角色关联的知识库绑定列表（含权限级别） */
    List<RoleKbBindingRequest> getRoleKnowledgeBases(String roleId);

    /** 为用户分配角色列表（全量替换） */
    void setUserRoles(String userId, List<String> roleIds);

    /** 获取用户的角色列表 */
    List<RoleDO> getUserRoles(String userId);

    /** 获取指定 KB 的所有角色绑定 */
    List<KnowledgeBaseController.KbRoleBindingVO> getKbRoleBindings(String kbId);

    /** 全量覆盖指定 KB 的角色绑定（仅影响此 KB，不影响其他 KB） */
    void setKbRoleBindings(String kbId, List<KnowledgeBaseController.KbRoleBindingRequest> bindings);
}
