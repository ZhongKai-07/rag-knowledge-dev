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

package com.knowledgebase.ai.ragent.user.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KB 访问权限计算器 — 纯函数；输入 {@link KbAccessSubject},输出可访问 KB 集 / 各 KB 密级上限。
 *
 * <p>PR3 不变量 PR3-1：本类不得 import {@code UserContext} / {@code LoginUser} /
 * {@code UserProfileLoader}（ArchUnit 守门）。所有主体信息通过 {@link KbAccessSubject}
 * 由调用方传入。
 *
 * <p>取代 PR2 的静态 RBAC 工具 + legacy access-service implementation
 * 的 {@code computeDeptAdminAccessibleKbIds} 私有 + {@code getMaxSecurityLevelsForKbs}
 * 偷读 ThreadLocal 的实现段（spec §0.1）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbAccessCalculator {

    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final KbMetadataReader kbMetadataReader;

    /** SUPER_ADMIN 视角下"全集"的密级上限；与 legacy access-service 原口径保持一致。*/
    private static final int SUPER_ADMIN_LEVEL_CEILING = 3;

    /**
     * 计算 subject 在 {@code minPermission} 下可访问的 KB ID 集合。
     *
     * <p>SUPER_ADMIN → {@code kbMetadataReader.listAllKbIds()}（物化全集，
     * 供 admin-views-target 报表用；port 出口由 legacy access-service
     * 包装为 {@code AccessScope.all()} sentinel）。
     *
     * <p>DEPT_ADMIN → RBAC ∪ 同部门 KB（subject.deptId 为 null 时 fallback RBAC-only
     * + WARN 日志）。
     *
     * <p>USER → RBAC only。
     */
    public Set<String> computeAccessibleKbIds(KbAccessSubject subject, Permission minPermission) {
        if (subject.isSuperAdmin()) {
            return kbMetadataReader.listAllKbIds();
        }
        Set<String> rbacKbs = computeRbacKbIds(subject.userId(), minPermission);
        if (subject.isDeptAdmin()) {
            String deptId = subject.deptId();
            if (deptId == null) {
                log.warn("DEPT_ADMIN 用户未挂载部门, userId={}", subject.userId());
                return rbacKbs;
            }
            rbacKbs.addAll(kbMetadataReader.listKbIdsByDeptId(deptId));
        }
        return rbacKbs;
    }

    /**
     * 计算 subject 在指定 KB 集合上的最高密级上限映射。
     *
     * <p>SUPER_ADMIN → 每个 KB 上限 {@value #SUPER_ADMIN_LEVEL_CEILING}（与原 access-service 一致）。
     * <p>USER → RBAC 关系上的 max(level)。
     * <p>DEPT_ADMIN → RBAC ∪ 本部门 KB（本部门 KB 上限取 subject.maxSecurityLevel,
     * RBAC 重叠取 {@code Math::max}）。
     */
    public Map<String, Integer> computeMaxSecurityLevels(
            KbAccessSubject subject, Collection<String> kbIds) {
        if (subject == null || kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyMap();
        }
        if (subject.isSuperAdmin()) {
            Map<String, Integer> result = new HashMap<>(kbIds.size() * 2);
            for (String kbId : kbIds) {
                if (kbId != null) {
                    result.put(kbId, SUPER_ADMIN_LEVEL_CEILING);
                }
            }
            return result;
        }

        Map<String, Integer> result = new HashMap<>();

        // RBAC: 2 queries
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, subject.userId()));
        if (!userRoles.isEmpty()) {
            List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();
            List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                    Wrappers.lambdaQuery(RoleKbRelationDO.class)
                            .in(RoleKbRelationDO::getRoleId, roleIds)
                            .in(RoleKbRelationDO::getKbId, kbIds));
            for (RoleKbRelationDO rel : relations) {
                Integer level = rel.getMaxSecurityLevel() == null ? 0 : rel.getMaxSecurityLevel();
                result.merge(rel.getKbId(), level, Math::max);
            }
        }

        // DEPT_ADMIN: 同部门 KB 上限 = subject.maxSecurityLevel,与 RBAC merge 取大
        if (subject.isDeptAdmin() && subject.deptId() != null) {
            int deptCeiling = subject.maxSecurityLevel();
            Set<String> sameDeptKbIds = kbMetadataReader.filterKbIdsByDept(kbIds, subject.deptId());
            for (String kbId : sameDeptKbIds) {
                result.merge(kbId, deptCeiling, Math::max);
            }
        }
        return result;
    }

    /** RBAC 路径：userId → roles → kb_relations → 过滤 permission ≥ minPermission → 过滤已删除 KB */
    private Set<String> computeRbacKbIds(String userId, Permission minPermission) {
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId));
        if (userRoles.isEmpty()) {
            return new HashSet<>();
        }
        List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();

        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .in(RoleKbRelationDO::getRoleId, roleIds));
        Set<String> kbIds = relations.stream()
                .filter(r -> permissionSatisfies(r.getPermission(), minPermission))
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toCollection(HashSet::new));

        if (!kbIds.isEmpty()) {
            kbIds = new HashSet<>(kbMetadataReader.filterExistingKbIds(kbIds));
        }
        return kbIds;
    }

    private static boolean permissionSatisfies(String actual, Permission required) {
        if (actual == null) return false;
        try {
            return Permission.valueOf(actual).ordinal() >= required.ordinal();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown permission value in DB: {}", actual);
            return false;
        }
    }
}
