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

package com.nageoffer.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final UserRoleMapper userRoleMapper;
    private final KbAccessService kbAccessService;

    @Override
    public String createRole(String name, String description) {
        RoleDO role = new RoleDO();
        role.setName(name);
        role.setDescription(description);
        roleMapper.insert(role);
        return role.getId();
    }

    @Override
    public void updateRole(String roleId, String name, String description) {
        RoleDO role = new RoleDO();
        role.setId(roleId);
        role.setName(name);
        role.setDescription(description);
        roleMapper.updateById(role);
    }

    @Override
    @Transactional
    public void deleteRole(String roleId) {
        evictCacheForRole(roleId);
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));
        userRoleMapper.delete(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getRoleId, roleId));
        roleMapper.deleteById(roleId);
    }

    @Override
    public List<RoleDO> listRoles() {
        return roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .orderByDesc(RoleDO::getCreateTime));
    }

    @Override
    @Transactional
    public void setRoleKnowledgeBases(String roleId, List<String> kbIds) {
        // Logical delete existing relations
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .eq(RoleKbRelationDO::getRoleId, roleId));

        // Insert new relations
        for (String kbId : kbIds) {
            RoleKbRelationDO relation = new RoleKbRelationDO();
            relation.setRoleId(roleId);
            relation.setKbId(kbId);
            roleKbRelationMapper.insert(relation);
        }

        // Evict cache for all affected users
        evictCacheForRole(roleId);
    }

    @Override
    public List<String> getRoleKnowledgeBaseIds(String roleId) {
        return roleKbRelationMapper.selectList(
                        Wrappers.lambdaQuery(RoleKbRelationDO.class)
                                .eq(RoleKbRelationDO::getRoleId, roleId))
                .stream()
                .map(RoleKbRelationDO::getKbId)
                .toList();
    }

    @Override
    @Transactional
    public void setUserRoles(String userId, List<String> roleIds) {
        userRoleMapper.delete(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId));

        for (String roleId : roleIds) {
            UserRoleDO userRole = new UserRoleDO();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }

        kbAccessService.evictCache(userId);
    }

    @Override
    public List<RoleDO> getUserRoles(String userId) {
        List<String> roleIds = userRoleMapper.selectList(
                        Wrappers.lambdaQuery(UserRoleDO.class)
                                .eq(UserRoleDO::getUserId, userId))
                .stream()
                .map(UserRoleDO::getRoleId)
                .toList();

        if (roleIds.isEmpty()) {
            return List.of();
        }

        return roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .in(RoleDO::getId, roleIds));
    }

    private void evictCacheForRole(String roleId) {
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getRoleId, roleId));
        for (UserRoleDO ur : userRoles) {
            kbAccessService.evictCache(ur.getUserId());
        }
    }
}
