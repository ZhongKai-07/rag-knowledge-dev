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
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.nageoffer.ai.ragent.user.controller.RoleController.RoleKbBindingRequest;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import com.nageoffer.ai.ragent.user.service.SuperAdminMutationIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final UserRoleMapper userRoleMapper;
    private final KbAccessService kbAccessService;

    @Override
    public String createRole(String name, String description, String roleType, Integer maxSecurityLevel) {
        RoleDO role = new RoleDO();
        role.setName(name);
        role.setDescription(description);
        role.setRoleType(roleType != null ? roleType : RoleType.USER.name());
        role.setMaxSecurityLevel(maxSecurityLevel != null ? maxSecurityLevel : 0);
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
    public void updateRole(
            String roleId,
            String name,
            String description,
            String roleType,
            Integer maxSecurityLevel) {
        RoleDO existing = roleMapper.selectById(roleId);
        if (existing == null) {
            throw new ClientException("角色不存在");
        }

        // Last SUPER_ADMIN pre-check: guard against demoting the last super-admin role
        if (RoleType.SUPER_ADMIN.name().equals(existing.getRoleType())
                && roleType != null
                && !RoleType.SUPER_ADMIN.name().equals(roleType)) {
            int after = kbAccessService.simulateActiveSuperAdminCountAfter(
                    new SuperAdminMutationIntent.ChangeRoleType(roleId, roleType));
            if (after < 1) {
                throw new ClientException("不能降级该角色：此操作会使系统失去最后一个 SUPER_ADMIN");
            }
        }

        RoleDO update = new RoleDO();
        update.setId(roleId);
        update.setName(name);
        update.setDescription(description);
        if (roleType != null) {
            update.setRoleType(roleType);
        }
        if (maxSecurityLevel != null) {
            update.setMaxSecurityLevel(maxSecurityLevel);
        }
        roleMapper.updateById(update);

        // Evict cache for all users that hold this role (role_type affects accessible KBs)
        evictCacheForRole(roleId);
    }

    @Override
    @Transactional
    public void deleteRole(String roleId) {
        RoleDO role = roleMapper.selectById(roleId);
        if (role != null && RoleType.SUPER_ADMIN.name().equals(role.getRoleType())) {
            int after = kbAccessService.simulateActiveSuperAdminCountAfter(
                    new SuperAdminMutationIntent.DeleteRole(roleId));
            if (after < 1) {
                throw new ClientException("不能删除该角色：此操作会使系统失去最后一个 SUPER_ADMIN");
            }
        }
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
    @Transactional(rollbackFor = Exception.class)
    public void setRoleKnowledgeBases(String roleId, List<RoleKbBindingRequest> bindings) {
        // 删除旧的关联
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));

        if (bindings != null && !bindings.isEmpty()) {
            // 加载角色天花板用于默认值和上界校验
            RoleDO role = roleMapper.selectById(roleId);
            int roleCeiling = (role != null && role.getMaxSecurityLevel() != null)
                    ? role.getMaxSecurityLevel() : 0;

            for (RoleKbBindingRequest binding : bindings) {
                int level = (binding.getMaxSecurityLevel() != null)
                        ? binding.getMaxSecurityLevel()
                        : roleCeiling;
                // 上界校验：不超角色天花板
                if (level > roleCeiling) {
                    level = roleCeiling;
                }

                RoleKbRelationDO relation = RoleKbRelationDO.builder()
                        .roleId(roleId)
                        .kbId(binding.getKbId())
                        .permission(binding.getPermission() != null ? binding.getPermission() : "MANAGE")
                        .maxSecurityLevel(level)
                        .build();
                roleKbRelationMapper.insert(relation);
            }
        }

        // 清除所有持有该角色的用户缓存
        evictCacheForRole(roleId);
    }

    @Override
    public List<RoleKbBindingRequest> getRoleKnowledgeBases(String roleId) {
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));
        return relations.stream().map(r -> {
            RoleKbBindingRequest req = new RoleKbBindingRequest();
            req.setKbId(r.getKbId());
            req.setPermission(r.getPermission() != null ? r.getPermission() : "MANAGE");
            req.setMaxSecurityLevel(r.getMaxSecurityLevel());
            return req;
        }).toList();
    }

    @Override
    @Transactional
    public void setUserRoles(String userId, List<String> roleIds) {
        int after = kbAccessService.simulateActiveSuperAdminCountAfter(
                new SuperAdminMutationIntent.ReplaceUserRoles(userId, roleIds));
        if (after < 1) {
            throw new ClientException("不能修改该用户角色：此操作会使系统失去最后一个 SUPER_ADMIN");
        }

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

    @Override
    public List<KnowledgeBaseController.KbRoleBindingVO> getKbRoleBindings(String kbId) {
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getKbId, kbId));
        if (relations.isEmpty()) {
            return List.of();
        }
        List<String> roleIds = relations.stream().map(RoleKbRelationDO::getRoleId).toList();
        Map<String, RoleDO> roleMap = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds))
                .stream().collect(Collectors.toMap(RoleDO::getId, r -> r));

        return relations.stream().map(rel -> {
            KnowledgeBaseController.KbRoleBindingVO vo = new KnowledgeBaseController.KbRoleBindingVO();
            vo.setRoleId(rel.getRoleId());
            vo.setPermission(rel.getPermission());
            vo.setMaxSecurityLevel(rel.getMaxSecurityLevel());
            RoleDO role = roleMap.get(rel.getRoleId());
            if (role != null) {
                vo.setRoleName(role.getName());
                vo.setRoleType(role.getRoleType());
            }
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setKbRoleBindings(String kbId,
                                  List<KnowledgeBaseController.KbRoleBindingRequest> bindings) {
        Set<String> affectedUserIds = new HashSet<>();

        // ① 删除前先收集旧绑定涉及的用户
        List<RoleKbRelationDO> oldRelations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getKbId, kbId));
        Set<String> oldRoleIds = oldRelations.stream()
                .map(RoleKbRelationDO::getRoleId).collect(Collectors.toSet());
        if (!oldRoleIds.isEmpty()) {
            userRoleMapper.selectList(
                    Wrappers.lambdaQuery(UserRoleDO.class).in(UserRoleDO::getRoleId, oldRoleIds))
                    .forEach(ur -> affectedUserIds.add(ur.getUserId()));
        }

        // ② 删除此 KB 的所有绑定
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getKbId, kbId));

        // ③ 写入新绑定
        if (bindings != null && !bindings.isEmpty()) {
            for (KnowledgeBaseController.KbRoleBindingRequest binding : bindings) {
                RoleDO role = roleMapper.selectById(binding.getRoleId());
                if (role == null) continue;

                int roleCeiling = (role.getMaxSecurityLevel() != null) ? role.getMaxSecurityLevel() : 0;
                int level = (binding.getMaxSecurityLevel() != null)
                        ? Math.min(binding.getMaxSecurityLevel(), roleCeiling)
                        : roleCeiling;

                // DEPT_ADMIN 额外校验：不超自身天花板
                if (!kbAccessService.isSuperAdmin()) {
                    int selfCeiling = UserContext.get().getMaxSecurityLevel();
                    if (level > selfCeiling) {
                        throw new ClientException("不可设置超过自身安全等级上限的绑定");
                    }
                }

                RoleKbRelationDO relation = RoleKbRelationDO.builder()
                        .roleId(binding.getRoleId())
                        .kbId(kbId)
                        .permission(binding.getPermission() != null ? binding.getPermission() : "READ")
                        .maxSecurityLevel(level)
                        .build();
                roleKbRelationMapper.insert(relation);

                // 收集新绑定涉及的用户
                userRoleMapper.selectList(
                        Wrappers.lambdaQuery(UserRoleDO.class)
                                .eq(UserRoleDO::getRoleId, binding.getRoleId()))
                        .forEach(ur -> affectedUserIds.add(ur.getUserId()));
            }
        }

        // ④ 统一驱逐缓存
        affectedUserIds.forEach(kbAccessService::evictCache);
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
