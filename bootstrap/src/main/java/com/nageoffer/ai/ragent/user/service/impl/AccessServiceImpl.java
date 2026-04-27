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
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.controller.vo.AccessRoleVO;
import com.nageoffer.ai.ragent.user.controller.vo.RoleUsageVO;
import com.nageoffer.ai.ragent.user.controller.vo.SysDeptVO;
import com.nageoffer.ai.ragent.user.controller.vo.UserKbGrantVO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.AccessService;
import com.nageoffer.ai.ragent.user.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
public class AccessServiceImpl implements AccessService {

    private final RoleMapper roleMapper;
    private final SysDeptMapper sysDeptMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbReadAccessPort kbReadAccess;
    private final SysDeptService sysDeptService;
    private final KbMetadataReader kbMetadataReader;

    @Override
    public List<AccessRoleVO> listRoles(String deptId, boolean includeGlobal) {
        Set<String> deptIds = new HashSet<>();
        if (deptId != null && !deptId.isBlank()) {
            deptIds.add(deptId);
        }
        if (includeGlobal) {
            deptIds.add(SysDeptServiceImpl.GLOBAL_DEPT_ID);
        }

        List<RoleDO> roles;
        if (deptIds.isEmpty()) {
            // 无过滤条件 = 返回全部
            roles = roleMapper.selectList(Wrappers.lambdaQuery(RoleDO.class));
        } else {
            roles = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getDeptId, deptIds));
        }

        if (roles.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> referencedDeptIds = roles.stream()
                .map(RoleDO::getDeptId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> deptNameById = referencedDeptIds.isEmpty()
                ? Collections.emptyMap()
                : sysDeptMapper.selectList(
                        Wrappers.lambdaQuery(SysDeptDO.class).in(SysDeptDO::getId, referencedDeptIds))
                        .stream().collect(Collectors.toMap(SysDeptDO::getId, SysDeptDO::getDeptName));

        return roles.stream()
                .map(r -> AccessRoleVO.builder()
                        .id(r.getId())
                        .name(r.getName())
                        .description(r.getDescription())
                        .roleType(r.getRoleType())
                        .maxSecurityLevel(r.getMaxSecurityLevel())
                        .deptId(r.getDeptId())
                        .deptName(deptNameById.get(r.getDeptId()))
                        .build())
                .toList();
    }

    @Override
    public List<UserKbGrantVO> listUserKbGrants(String userId) {
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new ClientException("目标用户不存在");
        }

        // Step 2 依赖：先拿目标用户的所有角色 + 角色→类型映射
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId));
        Set<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).collect(Collectors.toSet());
        List<RoleDO> targetRoles = roleIds.isEmpty()
                ? Collections.emptyList()
                : roleMapper.selectList(Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds));
        boolean targetIsSuper = targetRoles.stream()
                .anyMatch(r -> RoleType.SUPER_ADMIN.name().equals(r.getRoleType()));
        boolean targetIsDeptAdmin = targetRoles.stream()
                .anyMatch(r -> RoleType.DEPT_ADMIN.name().equals(r.getRoleType()));

        // Step 1: 真相范围 —— 直接按目标用户的身份计算，不能用 getAccessibleKbIds
        // （该方法依赖 CALLER context 的 isSuperAdmin 判断，在 admin 查其他人时会漏算成"全量"）
        Set<String> targetReadableKbIds = computeTargetUserAccessibleKbIds(
                userId, user.getDeptId(), targetIsSuper, targetIsDeptAdmin);
        if (targetReadableKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBaseDO> kbs = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class).in(KnowledgeBaseDO::getId, targetReadableKbIds));
        Map<String, Integer> levels = kbReadAccess.getMaxSecurityLevelsForKbs(userId, targetReadableKbIds);

        // Step 2: 显式 role 链（同时拿权限和 sourceRoleIds）
        Map<String, String> explicitPermByKb = new HashMap<>();
        Map<String, List<String>> sourceRoleIdsByKb = new HashMap<>();
        if (!roleIds.isEmpty()) {
            List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                    Wrappers.lambdaQuery(RoleKbRelationDO.class)
                            .in(RoleKbRelationDO::getRoleId, roleIds)
                            .in(RoleKbRelationDO::getKbId, targetReadableKbIds));
            for (RoleKbRelationDO rel : relations) {
                explicitPermByKb.merge(rel.getKbId(), rel.getPermission(),
                        (a, b) -> maxPermission(a, b));
                sourceRoleIdsByKb.computeIfAbsent(rel.getKbId(), k -> new ArrayList<>()).add(rel.getRoleId());
            }
        }

        // Step 3: implicit 独立判（与显式不互斥）
        String userDeptId = user.getDeptId();

        // Step 5: 取密级
        List<UserKbGrantVO> out = new ArrayList<>(kbs.size());
        for (KnowledgeBaseDO kb : kbs) {
            String explicitPerm = explicitPermByKb.get(kb.getId());
            List<String> sourceIds = sourceRoleIdsByKb.getOrDefault(kb.getId(), Collections.emptyList());
            boolean implicit = targetIsDeptAdmin
                    && userDeptId != null
                    && userDeptId.equals(kb.getDeptId());
            // Step 4: effective
            // SUPER_ADMIN 默认 MANAGE（与 getAccessibleKbIds 保持口径一致；explicit 列表通常为空）
            String effectivePerm;
            if (targetIsSuper) {
                effectivePerm = Permission.MANAGE.name();
            } else if (implicit) {
                effectivePerm = Permission.MANAGE.name();
            } else {
                effectivePerm = explicitPerm; // 可能为 null — 理论不应出现，因为范围已被锁
            }

            Integer securityLevel = levels.getOrDefault(kb.getId(), 0);

            out.add(UserKbGrantVO.builder()
                    .kbId(kb.getId())
                    .kbName(kb.getName())
                    .deptId(kb.getDeptId())
                    .permission(effectivePerm)
                    .explicitPermission(explicitPerm)
                    .securityLevel(securityLevel)
                    .sourceRoleIds(sourceIds)
                    .implicit(implicit)
                    .build());
        }
        return out;
    }

    @Override
    public RoleUsageVO getRoleUsage(String roleId) {
        RoleDO role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new ClientException("角色不存在");
        }

        String roleDeptName = null;
        if (role.getDeptId() != null && !role.getDeptId().isBlank()) {
            SysDeptDO dept = sysDeptMapper.selectById(role.getDeptId());
            if (dept != null) roleDeptName = dept.getDeptName();
        }

        // 持有该角色的用户
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getRoleId, roleId));
        Set<String> userIds = userRoles.stream().map(UserRoleDO::getUserId).collect(Collectors.toSet());
        List<UserDO> users = userIds.isEmpty()
                ? Collections.emptyList()
                : userMapper.selectList(Wrappers.lambdaQuery(UserDO.class).in(UserDO::getId, userIds));

        Set<String> userDeptIds = users.stream()
                .map(UserDO::getDeptId).filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        // 共享到的 KB
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));
        Set<String> kbIds = relations.stream().map(RoleKbRelationDO::getKbId).collect(Collectors.toSet());
        List<KnowledgeBaseDO> kbs = kbIds.isEmpty()
                ? Collections.emptyList()
                : knowledgeBaseMapper.selectList(Wrappers.lambdaQuery(KnowledgeBaseDO.class).in(KnowledgeBaseDO::getId, kbIds));
        Set<String> kbDeptIds = kbs.stream()
                .map(KnowledgeBaseDO::getDeptId).filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        // 一次性拿所有相关部门名
        Set<String> allDeptIds = new HashSet<>();
        allDeptIds.addAll(userDeptIds);
        allDeptIds.addAll(kbDeptIds);
        Map<String, String> deptNameById = allDeptIds.isEmpty()
                ? Collections.emptyMap()
                : sysDeptMapper.selectList(Wrappers.lambdaQuery(SysDeptDO.class).in(SysDeptDO::getId, allDeptIds))
                        .stream().collect(Collectors.toMap(SysDeptDO::getId, SysDeptDO::getDeptName));

        List<RoleUsageVO.UserRef> userRefs = users.stream()
                .map(u -> RoleUsageVO.UserRef.builder()
                        .userId(u.getId())
                        .username(u.getUsername())
                        .deptId(u.getDeptId())
                        .deptName(deptNameById.get(u.getDeptId()))
                        .build())
                .toList();

        Map<String, RoleKbRelationDO> relationByKb = relations.stream()
                .collect(Collectors.toMap(RoleKbRelationDO::getKbId, r -> r));
        List<RoleUsageVO.KbRef> kbRefs = kbs.stream()
                .map(kb -> {
                    RoleKbRelationDO rel = relationByKb.get(kb.getId());
                    return RoleUsageVO.KbRef.builder()
                            .kbId(kb.getId())
                            .kbName(kb.getName())
                            .deptId(kb.getDeptId())
                            .deptName(deptNameById.get(kb.getDeptId()))
                            .permission(rel != null ? rel.getPermission() : null)
                            .maxSecurityLevel(rel != null ? rel.getMaxSecurityLevel() : null)
                            .build();
                })
                .toList();

        return RoleUsageVO.builder()
                .roleId(role.getId())
                .roleName(role.getName())
                .roleType(role.getRoleType())
                .deptId(role.getDeptId())
                .deptName(roleDeptName)
                .users(userRefs)
                .kbs(kbRefs)
                .build();
    }

    @Override
    public List<SysDeptVO> listDepartmentsTree() {
        List<SysDeptVO> depts = sysDeptService.list(null);
        // GLOBAL（id='1'）排第一，其余按名称稳定排序
        return depts.stream()
                .sorted((a, b) -> {
                    if (SysDeptServiceImpl.GLOBAL_DEPT_ID.equals(a.getId())) return -1;
                    if (SysDeptServiceImpl.GLOBAL_DEPT_ID.equals(b.getId())) return 1;
                    String an = a.getDeptName() == null ? "" : a.getDeptName();
                    String bn = b.getDeptName() == null ? "" : b.getDeptName();
                    return an.compareTo(bn);
                })
                .toList();
    }

    /** READ &lt; WRITE &lt; MANAGE — 取更高权限（null 兜底成 READ） */
    private static String maxPermission(String a, String b) {
        Permission pa = parsePermission(a);
        Permission pb = parsePermission(b);
        return pa.ordinal() >= pb.ordinal() ? pa.name() : pb.name();
    }

    private static Permission parsePermission(String p) {
        if (p == null) return Permission.READ;
        try {
            return Permission.valueOf(p);
        } catch (IllegalArgumentException e) {
            return Permission.READ;
        }
    }

    /**
     * 按"目标用户"身份计算可访问 KB ID 集合。
     * <p>
     * 不直接复用 {@code getAccessibleKbIds}，因为它读 CALLER 的 UserContext。
     * 这里改为复用同包共享的 RBAC helper，避免漂移出两套算法。
     */
    private Set<String> computeTargetUserAccessibleKbIds(
            String userId,
            String userDeptId,
            boolean targetIsSuper,
            boolean targetIsDeptAdmin) {
        if (targetIsSuper) {
            return kbMetadataReader.listAllKbIds();
        }
        Set<String> result = KbRbacAccessSupport.computeRbacKbIdsFor(
                userId,
                Permission.READ,
                userRoleMapper,
                roleKbRelationMapper,
                kbMetadataReader);
        if (targetIsDeptAdmin && userDeptId != null && !userDeptId.isBlank()) {
            result.addAll(kbMetadataReader.listKbIdsByDeptId(userDeptId));
        }
        return result;
    }
}
