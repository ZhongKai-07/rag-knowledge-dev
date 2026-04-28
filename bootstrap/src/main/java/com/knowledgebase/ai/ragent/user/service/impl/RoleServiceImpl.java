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

package com.knowledgebase.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.knowledge.controller.KnowledgeBaseController;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.user.controller.RoleController;
import com.knowledgebase.ai.ragent.user.controller.RoleController.RoleKbBindingRequest;
import com.knowledgebase.ai.ragent.user.controller.RoleController.RoleDeletePreviewVO;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.SysDeptDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.knowledgebase.ai.ragent.framework.security.port.CurrentUserProbe;
import com.knowledgebase.ai.ragent.framework.security.port.KbAccessCacheAdmin;
import com.knowledgebase.ai.ragent.framework.security.port.KbManageAccessPort;
import com.knowledgebase.ai.ragent.framework.security.port.SuperAdminInvariantGuard;
import com.knowledgebase.ai.ragent.framework.security.port.SuperAdminMutationIntent;
import com.knowledgebase.ai.ragent.user.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private final UserMapper userMapper;
    private final SysDeptMapper sysDeptMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final SuperAdminInvariantGuard superAdminGuard;
    private final KbAccessCacheAdmin cacheAdmin;
    private final KbManageAccessPort kbManageAccess;
    private final CurrentUserProbe currentUser;

    @Override
    public String createRole(
            String name,
            String description,
            String roleType,
            Integer maxSecurityLevel,
            String deptId) {
        RoleDO role = new RoleDO();
        role.setName(name);
        role.setDescription(description);
        role.setRoleType(roleType != null ? roleType : RoleType.USER.name());
        role.setMaxSecurityLevel(maxSecurityLevel != null ? maxSecurityLevel : 0);
        role.setDeptId(deptId);
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
            Integer maxSecurityLevel,
            String deptId) {
        RoleDO existing = roleMapper.selectById(roleId);
        if (existing == null) {
            throw new ClientException("角色不存在");
        }

        // Last SUPER_ADMIN pre-check: guard against demoting the last super-admin role
        if (RoleType.SUPER_ADMIN.name().equals(existing.getRoleType())
                && roleType != null
                && !RoleType.SUPER_ADMIN.name().equals(roleType)) {
            int after = superAdminGuard.simulateActiveSuperAdminCountAfter(
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
        if (deptId != null) {
            update.setDeptId(deptId);
        }
        roleMapper.updateById(update);

        // Evict cache for all users that hold this role (role_type affects accessible KBs)
        evictCacheForRole(roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(String roleId) {
        RoleDO role = roleMapper.selectById(roleId);
        if (role != null && RoleType.SUPER_ADMIN.name().equals(role.getRoleType())) {
            int after = superAdminGuard.simulateActiveSuperAdminCountAfter(
                    new SuperAdminMutationIntent.DeleteRole(roleId));
            if (after < 1) {
                throw new ClientException("不能删除该角色：此操作会使系统失去最后一个 SUPER_ADMIN");
            }
        }

        // P1.4: 事务前收集受影响用户 —— 必须在删 t_user_role 之前取，否则删完就查不到了。
        Set<String> affectedUserIds = userRoleMapper.selectList(
                        Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getRoleId, roleId))
                .stream()
                .map(UserRoleDO::getUserId)
                .collect(Collectors.toSet());

        // 级联删
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));
        userRoleMapper.delete(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getRoleId, roleId));
        roleMapper.deleteById(roleId);

        // P1.4: 失效所有相关用户的 kb_access / kb_access:dept / kb_security_level 三条缓存。
        // 放在 DB 级联之后：DB 失败时缓存不被误清；缓存失败会让事务回滚（保守但安全）。
        affectedUserIds.forEach(cacheAdmin::evictCache);
    }


    @Override
    public RoleDeletePreviewVO getRoleDeletePreview(String roleId) {
        RoleDO role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ClientException("角色不存在: " + roleId);
        }

        // 1. 收集受影响用户
        List<UserRoleDO> affectedUserRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getRoleId, roleId));
        List<String> affectedUserIds = affectedUserRoles.stream()
                .map(UserRoleDO::getUserId)
                .distinct()
                .collect(Collectors.toList());

        // 2. 收集该角色挂载的 KB
        List<RoleKbRelationDO> roleKbRelations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));
        Set<String> roleKbIds = roleKbRelations.stream()
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toSet());

        // 3. 批量查 user / dept / kb 元信息
        Map<String, UserDO> userById = affectedUserIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(affectedUserIds).stream()
                        .collect(Collectors.toMap(UserDO::getId, u -> u));
        Map<String, KnowledgeBaseDO> kbById = roleKbIds.isEmpty()
                ? Collections.emptyMap()
                : knowledgeBaseMapper.selectBatchIds(roleKbIds).stream()
                        .collect(Collectors.toMap(KnowledgeBaseDO::getId, k -> k));

        Set<String> deptIds = new HashSet<>();
        userById.values().forEach(u -> { if (u.getDeptId() != null) deptIds.add(u.getDeptId()); });
        kbById.values().forEach(k -> { if (k.getDeptId() != null) deptIds.add(k.getDeptId()); });
        Map<String, SysDeptDO> deptById = deptIds.isEmpty()
                ? Collections.emptyMap()
                : sysDeptMapper.selectBatchIds(deptIds).stream()
                        .collect(Collectors.toMap(SysDeptDO::getId, d -> d));

        // 4. 计算每个用户的 lostKbIds（仅显式 role 链）
        // 拉取所有受影响用户其他角色挂载的 KB（一次查询批量解决 N+1）
        Map<String, Set<String>> userToOtherKbs = computeUserToOtherKbs(affectedUserIds, roleId);

        // 5. 组装 VO
        RoleDeletePreviewVO vo = new RoleDeletePreviewVO();
        vo.setRoleId(roleId);
        vo.setRoleName(role.getName());

        vo.setAffectedUsers(affectedUserIds.stream()
                .map(uid -> {
                    UserDO u = userById.get(uid);
                    if (u == null) return null;
                    RoleController.AffectedUser a = new RoleController.AffectedUser();
                    a.setUserId(u.getId());
                    a.setUsername(u.getUsername());
                    a.setDeptId(u.getDeptId());
                    SysDeptDO d = deptById.get(u.getDeptId());
                    a.setDeptName(d != null ? d.getDeptName() : null);
                    return a;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList()));

        vo.setAffectedKbs(roleKbIds.stream()
                .map(kid -> {
                    KnowledgeBaseDO k = kbById.get(kid);
                    if (k == null) return null;
                    RoleController.AffectedKb a = new RoleController.AffectedKb();
                    a.setKbId(k.getId());
                    a.setKbName(k.getName());
                    a.setDeptId(k.getDeptId());
                    SysDeptDO d = deptById.get(k.getDeptId());
                    a.setDeptName(d != null ? d.getDeptName() : null);
                    return a;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList()));

        vo.setUserKbDiff(affectedUserIds.stream()
                .map(uid -> {
                    UserDO u = userById.get(uid);
                    if (u == null) return null;
                    Set<String> otherKbs = userToOtherKbs.getOrDefault(uid, Collections.emptySet());
                    List<String> lostKbIds = roleKbIds.stream()
                            .filter(kbId -> !otherKbs.contains(kbId))
                            .collect(Collectors.toList());
                    if (lostKbIds.isEmpty()) return null;
                    RoleController.UserKbDiff diff = new RoleController.UserKbDiff();
                    diff.setUserId(u.getId());
                    diff.setUsername(u.getUsername());
                    diff.setLostKbIds(lostKbIds);
                    diff.setLostKbNames(lostKbIds.stream()
                            .map(kbId -> {
                                KnowledgeBaseDO k = kbById.get(kbId);
                                return k != null ? k.getName() : kbId;
                            })
                            .collect(Collectors.toList()));
                    return diff;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList()));

        return vo;
    }

    /**
     * 对受影响用户批量计算"通过其他角色仍能访问的 KB 集合"，避免 N+1 查询。
     */
    private Map<String, Set<String>> computeUserToOtherKbs(List<String> affectedUserIds, String excludedRoleId) {
        if (affectedUserIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 1) 拉这些用户的所有其他角色绑定
        List<UserRoleDO> allUserRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .in(UserRoleDO::getUserId, affectedUserIds)
                        .ne(UserRoleDO::getRoleId, excludedRoleId));
        if (allUserRoles.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> otherRoleIds = allUserRoles.stream()
                .map(UserRoleDO::getRoleId).collect(Collectors.toSet());

        // 2) 拉这些其他角色挂载的所有 KB
        List<RoleKbRelationDO> rels = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).in(RoleKbRelationDO::getRoleId, otherRoleIds));
        Map<String, Set<String>> roleIdToKbs = new HashMap<>();
        for (RoleKbRelationDO r : rels) {
            roleIdToKbs.computeIfAbsent(r.getRoleId(), k -> new HashSet<>()).add(r.getKbId());
        }

        // 3) 反向聚合：userId -> union of kb 集合
        Map<String, Set<String>> result = new HashMap<>();
        for (UserRoleDO ur : allUserRoles) {
            Set<String> kbs = roleIdToKbs.get(ur.getRoleId());
            if (kbs == null || kbs.isEmpty()) continue;
            result.computeIfAbsent(ur.getUserId(), k -> new HashSet<>()).addAll(kbs);
        }
        return result;
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
        int after = superAdminGuard.simulateActiveSuperAdminCountAfter(
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

        cacheAdmin.evictCache(userId);
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
    public RoleDO getRoleById(String roleId) {
        return roleMapper.selectById(roleId);
    }

    @Override
    public List<KnowledgeBaseController.KbRoleBindingVO> getKbRoleBindings(String kbId) {
        kbManageAccess.checkKbRoleBindingAccess(kbId);
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getKbId, kbId));
        if (relations.isEmpty()) {
            return List.of();
        }
        List<String> roleIds = relations.stream().map(RoleKbRelationDO::getRoleId).toList();
        Map<String, RoleDO> roleMap = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds))
                .stream().collect(Collectors.toMap(RoleDO::getId, r -> r));

        // P1.1: resolve dept names for the roles (for cross-dept badge in P1.5).
        Set<String> deptIds = roleMap.values().stream()
                .map(RoleDO::getDeptId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());
        Map<String, String> deptNameById = deptIds.isEmpty()
                ? Map.of()
                : sysDeptMapper.selectList(
                        Wrappers.lambdaQuery(SysDeptDO.class).in(SysDeptDO::getId, deptIds))
                        .stream().collect(Collectors.toMap(SysDeptDO::getId, SysDeptDO::getDeptName));

        return relations.stream().map(rel -> {
            KnowledgeBaseController.KbRoleBindingVO vo = new KnowledgeBaseController.KbRoleBindingVO();
            vo.setRoleId(rel.getRoleId());
            vo.setPermission(rel.getPermission());
            vo.setMaxSecurityLevel(rel.getMaxSecurityLevel());
            RoleDO role = roleMap.get(rel.getRoleId());
            if (role != null) {
                vo.setRoleName(role.getName());
                vo.setRoleType(role.getRoleType());
                vo.setDeptId(role.getDeptId());
                vo.setDeptName(deptNameById.get(role.getDeptId()));
            }
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setKbRoleBindings(String kbId,
                                  List<KnowledgeBaseController.KbRoleBindingRequest> bindings) {
        kbManageAccess.checkKbRoleBindingAccess(kbId);
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
                if (!currentUser.isSuperAdmin()) {
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
        affectedUserIds.forEach(cacheAdmin::evictCache);
    }

    private void evictCacheForRole(String roleId) {
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getRoleId, roleId));
        for (UserRoleDO ur : userRoles) {
            cacheAdmin.evictCache(ur.getUserId());
        }
    }
}
