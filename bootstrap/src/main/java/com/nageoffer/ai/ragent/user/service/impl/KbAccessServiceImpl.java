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
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.framework.security.port.KbAccessCacheAdmin;
import com.nageoffer.ai.ragent.framework.security.port.KbManageAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.framework.security.port.KbRoleBindingAdminPort;
import com.nageoffer.ai.ragent.framework.security.port.SuperAdminInvariantGuard;
import com.nageoffer.ai.ragent.framework.security.port.SuperAdminMutationIntent;
import com.nageoffer.ai.ragent.framework.security.port.UserAdminGuard;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbAccessServiceImpl implements KbAccessService,
        CurrentUserProbe, KbReadAccessPort, KbManageAccessPort,
        KbRoleBindingAdminPort, UserAdminGuard, SuperAdminInvariantGuard, KbAccessCacheAdmin {

    private static final String CACHE_PREFIX = "kb_access:";
    private static final String DEPT_ADMIN_CACHE_PREFIX = "kb_access:dept:";
    private static final String KB_SECURITY_LEVEL_CACHE_PREFIX = "kb_security_level:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final int BULK_CACHE_EVICT_THRESHOLD = 500;

    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final RedissonClient redissonClient;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final SysDeptMapper sysDeptMapper;
    private final KbMetadataReader kbMetadataReader;

    @Override
    public Set<String> getAccessibleKbIds(String userId, Permission minPermission) {
        // SUPER_ADMIN 全量，不走缓存
        if (isSuperAdmin()) {
            return kbMetadataReader.listAllKbIds();
        }

        // DEPT_ADMIN: RBAC 授权 KB ∪ 同部门 KB；READ 路径走缓存（kb_access:dept:{userId}）
        if (isDeptAdmin()) {
            boolean cacheable = minPermission == Permission.READ;
            String cacheKey = DEPT_ADMIN_CACHE_PREFIX + userId;
            if (cacheable) {
                RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
                Set<String> cached = bucket.get();
                if (cached != null) {
                    return cached;
                }
            }
            Set<String> result = computeDeptAdminAccessibleKbIds(userId, minPermission);
            if (cacheable) {
                redissonClient.getBucket(cacheKey).<Set<String>>set(result, CACHE_TTL);
            }
            return result;
        }

        // USER 走 PR1 原有缓存路径
        boolean cacheable = minPermission == Permission.READ;
        String cacheKey = CACHE_PREFIX + userId;
        if (cacheable) {
            RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
            Set<String> cached = bucket.get();
            if (cached != null) {
                return cached;
            }
        }
        Set<String> result = computeRbacKbIds(userId, minPermission);
        if (cacheable) {
            redissonClient.getBucket(cacheKey).<Set<String>>set(result, CACHE_TTL);
        }
        return result;
    }

    @Override
    public Set<String> getAccessibleKbIds(String userId) {
        // 消除 KbAccessService 和 KbReadAccessPort 两个父接口 default 方法的 diamond 冲突。
        // 保留旧语义（READ 权限），KbReadAccessPort 的 deprecated default 仅作为迁移期标记。
        return getAccessibleKbIds(userId, Permission.READ);
    }

    @Override
    public AccessScope getAccessScope(String userId, Permission minPermission) {
        if (isSuperAdmin()) {
            return AccessScope.all();
        }
        return AccessScope.ids(getAccessibleKbIds(userId, minPermission));
    }

    @Override
    public void checkReadAccess(String kbId) {
        checkAccess(kbId);
    }

    /** DEPT_ADMIN 的可见 KB = RBAC 授权 KB ∪ 本部门所有 KB */
    private Set<String> computeDeptAdminAccessibleKbIds(String userId, Permission minPermission) {
        Set<String> rbacKbs = computeRbacKbIds(userId, minPermission);
        LoginUser user = UserContext.get();
        String deptId = user.getDeptId();
        if (deptId == null) {
            log.warn("DEPT_ADMIN 用户未挂载部门, userId={}", userId);
            return rbacKbs;
        }
        rbacKbs.addAll(kbMetadataReader.listKbIdsByDeptId(deptId));
        return rbacKbs;
    }

    /** RBAC 路径：user → roles → kb_relations → filter permission */
    private Set<String> computeRbacKbIds(String userId, Permission minPermission) {
        return KbRbacAccessSupport.computeRbacKbIdsFor(
                userId, minPermission, userRoleMapper, roleKbRelationMapper, kbMetadataReader);
    }

    /** Null-safe dept equality. user==null or either deptId==null returns false. */
    private static boolean sameDept(LoginUser user, String otherDeptId) {
        return user != null && Objects.equals(user.getDeptId(), otherDeptId);
    }

    @Override
    public void checkAccess(String kbId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
            return;
        }
        if (isDeptAdmin()) {
            LoginUser user = UserContext.get();
            if (user.getDeptId() == null) {
                log.warn("DEPT_ADMIN 用户未挂载部门, userId={}", UserContext.getUserId());
            } else {
                String kbDeptId = kbMetadataReader.getKbDeptId(kbId);
                if (kbDeptId != null && sameDept(user, kbDeptId)) {
                    return;
                }
            }
        }
        Set<String> accessible = getAccessibleKbIds(UserContext.getUserId(), Permission.READ);
        if (!accessible.contains(kbId)) {
            log.warn("权限拒绝: userId={}, kbId={}, action=READ", UserContext.getUserId(), kbId);
            throw new ClientException("无权访问该知识库: " + kbId);
        }
    }

    @Override
    public void checkManageAccess(String kbId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
            return;
        }
        LoginUser user = UserContext.get();
        if (user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.DEPT_ADMIN)) {
            String kbDeptId = kbMetadataReader.getKbDeptId(kbId);
            // Schema: t_knowledge_base.dept_id NOT NULL → null 等于不存在
            if (kbDeptId == null) {
                throw new ClientException("知识库不存在: " + kbId);
            }
            if (sameDept(user, kbDeptId)) {
                return;
            }
            log.warn("权限拒绝: userId={}, kbId={}, action=MANAGE, reason=跨部门", UserContext.getUserId(), kbId);
            throw new ClientException("无权管理其他部门知识库: " + kbId);
        }
        log.warn("权限拒绝: userId={}, kbId={}, action=MANAGE, reason=非管理员", UserContext.getUserId(), kbId);
        throw new ClientException("无管理权限: " + kbId);
    }

    @Override
    public boolean isSuperAdmin() {
        if (!UserContext.hasUser()) {
            return false;
        }
        LoginUser user = UserContext.get();
        return user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.SUPER_ADMIN);
    }

    @Override
    public void evictCache(String userId) {
        redissonClient.getBucket(CACHE_PREFIX + userId).delete();
        redissonClient.getBucket(DEPT_ADMIN_CACHE_PREFIX + userId).delete();
        redissonClient.getBucket(KB_SECURITY_LEVEL_CACHE_PREFIX + userId).delete();
    }

    @Override
    public int unbindAllRolesFromKb(String kbId) {
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .select(RoleKbRelationDO::getRoleId)
                        .eq(RoleKbRelationDO::getKbId, kbId));
        if (relations.isEmpty()) {
            return 0;
        }

        Set<String> roleIds = relations.stream()
                .map(RoleKbRelationDO::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> userIds = Collections.emptySet();
        if (!roleIds.isEmpty()) {
            userIds = userRoleMapper.selectList(
                            Wrappers.lambdaQuery(UserRoleDO.class)
                                    .select(UserRoleDO::getUserId)
                                    .in(UserRoleDO::getRoleId, roleIds))
                    .stream()
                    .map(UserRoleDO::getUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        int affectedRows = roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .eq(RoleKbRelationDO::getKbId, kbId));

        if (userIds.isEmpty()) {
            return affectedRows;
        }
        if (userIds.size() > BULK_CACHE_EVICT_THRESHOLD) {
            RKeys keys = redissonClient.getKeys();
            keys.deleteByPattern(CACHE_PREFIX + "*");
            keys.deleteByPattern(DEPT_ADMIN_CACHE_PREFIX + "*");
            keys.deleteByPattern(KB_SECURITY_LEVEL_CACHE_PREFIX + "*");
            return affectedRows;
        }

        userIds.forEach(this::evictCache);
        return affectedRows;
    }

    @Override
    public Integer getMaxSecurityLevelForKb(String userId, String kbId) {
        if (userId == null || kbId == null) {
            return 0;
        }

        // SUPER_ADMIN: always max
        LoginUser current = UserContext.get();
        if (current != null && current.getRoleTypes() != null
                && current.getRoleTypes().contains(RoleType.SUPER_ADMIN)) {
            return 3;
        }

        // DEPT_ADMIN implicit access to same-dept KBs: use role ceiling
        if (current != null && current.getRoleTypes() != null
                && current.getRoleTypes().contains(RoleType.DEPT_ADMIN)) {
            String kbDeptId = kbMetadataReader.getKbDeptId(kbId);
            if (kbDeptId != null && sameDept(current, kbDeptId)) {
                return current.getMaxSecurityLevel();
            }
        }

        // Regular path: check Redis Hash cache
        String cacheKey = KB_SECURITY_LEVEL_CACHE_PREFIX + userId;
        RBucket<Map<String, Integer>> bucket = redissonClient.getBucket(cacheKey);
        Map<String, Integer> cached = bucket.get();
        if (cached != null && cached.containsKey(kbId)) {
            return cached.get(kbId);
        }

        // Compute from DB
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId));
        List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();

        int level = 0;
        if (!roleIds.isEmpty()) {
            List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                    Wrappers.lambdaQuery(RoleKbRelationDO.class)
                            .in(RoleKbRelationDO::getRoleId, roleIds)
                            .eq(RoleKbRelationDO::getKbId, kbId));
            for (RoleKbRelationDO rel : relations) {
                if (rel.getMaxSecurityLevel() != null && rel.getMaxSecurityLevel() > level) {
                    level = rel.getMaxSecurityLevel();
                }
            }
        }

        // Write to cache (hash entry)
        if (cached == null) {
            cached = new HashMap<>();
        }
        cached.put(kbId, level);
        bucket.set(cached, CACHE_TTL);

        return level;
    }

    @Override
    public Map<String, Integer> getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds) {
        if (userId == null || kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LoginUser current = UserContext.hasUser() ? UserContext.get() : null;

        // SUPER_ADMIN: every requested KB → max ceiling 3
        if (current != null && current.getRoleTypes() != null
                && current.getRoleTypes().contains(RoleType.SUPER_ADMIN)) {
            Map<String, Integer> result = new HashMap<>(kbIds.size() * 2);
            for (String kbId : kbIds) {
                if (kbId != null) result.put(kbId, 3);
            }
            return result;
        }

        Map<String, Integer> result = new HashMap<>();

        // RBAC bindings: 2 queries (user→roles, roles+kbs→relations) for the whole batch
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId));
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

        // DEPT_ADMIN implicit access to same-dept KBs overrides RBAC ceiling
        if (current != null && current.getRoleTypes() != null
                && current.getRoleTypes().contains(RoleType.DEPT_ADMIN)
                && current.getDeptId() != null) {
            int deptCeiling = current.getMaxSecurityLevel();
            Set<String> sameDeptKbIds = kbMetadataReader.filterKbIdsByDept(kbIds, current.getDeptId());
            for (String kbId : sameDeptKbIds) {
                result.merge(kbId, deptCeiling, Math::max);
            }
        }
        return result;
    }

    @Override
    public void checkAnyAdminAccess() {
        LoginUser current = UserContext.get();
        if (current == null) {
            throw new ClientException("未登录");
        }
        if (!isSuperAdmin() && !isDeptAdmin()) {
            throw new ClientException("需要管理员权限");
        }
    }

    @Override
    public boolean isDeptAdmin() {
        if (!UserContext.hasUser()) {
            return false;
        }
        LoginUser user = UserContext.get();
        return user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.DEPT_ADMIN);
    }

    @Override
    public void checkCreateUserAccess(String targetDeptId, List<String> roleIds) {
        if (!UserContext.hasUser()) {
            throw new ClientException("未登录用户不可创建用户");
        }
        if (isSuperAdmin()) {
            return;
        }
        LoginUser user = UserContext.get();
        if (!isDeptAdmin()) {
            log.warn("权限拒绝: userId={}, action=CREATE_USER, reason=非管理员", UserContext.getUserId());
            throw new ClientException("无权创建用户");
        }
        if (!sameDept(user, targetDeptId)) {
            log.warn("权限拒绝: userId={}, action=CREATE_USER, reason=跨部门, targetDept={}", UserContext.getUserId(), targetDeptId);
            throw new ClientException("DEPT_ADMIN 只能在本部门创建用户");
        }
        validateRoleAssignment(roleIds);
    }

    @Override
    public void checkUserManageAccess(String targetUserId) {
        if (!UserContext.hasUser()) {
            throw new ClientException("未登录用户不可管理用户");
        }
        if (isSuperAdmin()) {
            return;
        }
        if (!isDeptAdmin()) {
            log.warn("权限拒绝: userId={}, targetUserId={}, action=MANAGE_USER, reason=非管理员", UserContext.getUserId(), targetUserId);
            throw new ClientException("无权管理用户");
        }
        LoginUser current = UserContext.get();
        UserDO target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new ClientException("目标用户不存在");
        }
        if (!sameDept(current, target.getDeptId())) {
            log.warn("权限拒绝: userId={}, targetUserId={}, action=MANAGE_USER, reason=跨部门", UserContext.getUserId(), targetUserId);
            throw new ClientException("DEPT_ADMIN 只能管理本部门用户");
        }
    }

    @Override
    public void checkAssignRolesAccess(String targetUserId, List<String> newRoleIds) {
        // 先复用用户管理权校验
        checkUserManageAccess(targetUserId);
        if (isSuperAdmin()) {
            return; // SUPER_ADMIN 可分配任意角色
        }
        validateRoleAssignment(newRoleIds);
    }

    @Override
    public void checkRoleMutation(String roleDeptId) {
        if (!UserContext.hasUser()) {
            throw new ClientException("未登录用户不可管理角色");
        }
        if (isSuperAdmin()) {
            return;
        }
        if (!isDeptAdmin()) {
            log.warn("权限拒绝: userId={}, action=MUTATE_ROLE, reason=非管理员", UserContext.getUserId());
            throw new ClientException("无权管理角色");
        }
        if (roleDeptId == null || roleDeptId.isBlank()) {
            log.warn("权限拒绝: userId={}, action=MUTATE_ROLE, reason=角色未归属部门", UserContext.getUserId());
            throw new ClientException("角色未归属部门，无法操作");
        }
        if (SysDeptServiceImpl.GLOBAL_DEPT_ID.equals(roleDeptId)) {
            log.warn("权限拒绝: userId={}, action=MUTATE_ROLE, reason=GLOBAL角色", UserContext.getUserId());
            throw new ClientException("DEPT_ADMIN 不可管理 GLOBAL 角色");
        }
        LoginUser current = UserContext.get();
        if (!sameDept(current, roleDeptId)) {
            log.warn(
                    "权限拒绝: userId={}, action=MUTATE_ROLE, reason=跨部门, roleDept={}, selfDept={}",
                    UserContext.getUserId(),
                    roleDeptId,
                    current.getDeptId());
            throw new ClientException("DEPT_ADMIN 只能管理本部门角色");
        }
    }

    @Override
    public void validateRoleAssignment(List<String> roleIds) {
        if (isSuperAdmin() || roleIds == null || roleIds.isEmpty()) {
            return;
        }
        LoginUser current = UserContext.get();
        int currentCeiling = current.getMaxSecurityLevel();
        String selfDeptId = current.getDeptId();
        List<RoleDO> roles = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds));
        for (RoleDO role : roles) {
            if (RoleType.SUPER_ADMIN.name().equals(role.getRoleType())) {
                throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
            }
            if (RoleType.DEPT_ADMIN.name().equals(role.getRoleType())) {
                throw new ClientException("DEPT_ADMIN 不可分配 DEPT_ADMIN 角色");
            }
            if (role.getMaxSecurityLevel() != null && role.getMaxSecurityLevel() > currentCeiling) {
                throw new ClientException("不可分配超过自身安全等级上限的角色");
            }
            // P1.2 D11: DEPT_ADMIN 只能分配本部门或 GLOBAL 角色；跨部门 USER 角色 fail-closed。
            // 不对称规则的后端闸门 —— 前端 dropdown 收敛不算授权边界。
            String roleDeptId = role.getDeptId();
            boolean isGlobalRole = SysDeptServiceImpl.GLOBAL_DEPT_ID.equals(roleDeptId);
            boolean isSameDeptRole = roleDeptId != null && roleDeptId.equals(selfDeptId);
            if (!isGlobalRole && !isSameDeptRole) {
                log.warn(
                        "权限拒绝: userId={}, action=ASSIGN_ROLE, reason=跨部门, roleId={}, roleDept={}, selfDept={}",
                        UserContext.getUserId(),
                        role.getId(),
                        roleDeptId,
                        selfDeptId);
                throw new ClientException("DEPT_ADMIN 不可分配其他部门的角色");
            }
        }
    }

    @Override
    public String resolveCreateKbDeptId(String requestedDeptId) {
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            throw new ClientException("未登录用户不可创建知识库");
        }
        if (isSuperAdmin()) {
            String effectiveDeptId = (requestedDeptId == null || requestedDeptId.isBlank())
                    ? SysDeptServiceImpl.GLOBAL_DEPT_ID
                    : requestedDeptId;
            if (sysDeptMapper.selectById(effectiveDeptId) == null) {
                throw new ClientException("目标部门不存在: " + effectiveDeptId);
            }
            return effectiveDeptId;
        }
        if (!isDeptAdmin()) {
            throw new ClientException("无权创建知识库");
        }
        LoginUser user = UserContext.get();
        String selfDeptId = user.getDeptId();
        if (selfDeptId == null) {
            throw new ClientException("当前 DEPT_ADMIN 用户未挂载部门");
        }
        if (requestedDeptId != null && !requestedDeptId.isBlank()
                && !requestedDeptId.equals(selfDeptId)) {
            throw new ClientException("DEPT_ADMIN 只能在本部门创建知识库");
        }
        return selfDeptId;
    }

    @Override
    public void checkDocManageAccess(String docId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
            return;
        }
        String docKbId = kbMetadataReader.getKbIdOfDoc(docId);
        if (docKbId == null) {
            throw new ClientException("文档不存在: " + docId);
        }
        checkManageAccess(docKbId);
    }

    @Override
    public void checkDocSecurityLevelAccess(String docId, int newLevel) {
        checkDocManageAccess(docId);
    }

    // === Last SUPER_ADMIN 系统级硬不变量（Decision 3-M）===

    @Override
    public int countActiveSuperAdmins() {
        return countSuperAdminsExcluding(Set.of(), Set.of(), Map.of());
    }

    @Override
    public boolean isUserSuperAdmin(String userId) {
        if (userId == null) return false;
        List<RoleDO> superAdminRoles = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
        );
        if (superAdminRoles.isEmpty()) return false;
        Set<String> superAdminRoleIds = superAdminRoles.stream()
                .map(RoleDO::getId).collect(Collectors.toSet());
        Long count = userRoleMapper.selectCount(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId)
                        .in(UserRoleDO::getRoleId, superAdminRoleIds)
        );
        return count != null && count > 0;
    }

    @Override
    public int simulateActiveSuperAdminCountAfter(SuperAdminMutationIntent intent) {
        if (intent instanceof SuperAdminMutationIntent.DeleteUser du) {
            return countSuperAdminsExcluding(Set.of(du.userId()), Set.of(), Map.of());
        } else if (intent instanceof SuperAdminMutationIntent.ReplaceUserRoles rur) {
            return countSuperAdminsExcluding(
                    Set.of(), Set.of(),
                    Map.of(rur.userId(), new HashSet<>(rur.newRoleIds())));
        } else if (intent instanceof SuperAdminMutationIntent.ChangeRoleType crt) {
            if (!RoleType.SUPER_ADMIN.name().equals(crt.newRoleType())) {
                return countSuperAdminsExcluding(Set.of(), Set.of(crt.roleId()), Map.of());
            }
            return countActiveSuperAdmins();
        } else if (intent instanceof SuperAdminMutationIntent.DeleteRole dr) {
            return countSuperAdminsExcluding(Set.of(), Set.of(dr.roleId()), Map.of());
        }
        throw new IllegalArgumentException("Unknown SuperAdminMutationIntent type: " + intent.getClass());
    }

    @Override
    public void checkKbRoleBindingAccess(String kbId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
            return;
        }
        if (!isDeptAdmin()) {
            throw new ClientException("需要管理员权限");
        }
        String kbDeptId = kbMetadataReader.getKbDeptId(kbId);
        if (kbDeptId == null) {
            throw new ClientException("知识库不存在");
        }
        LoginUser current = UserContext.requireUser();
        if (!sameDept(current, kbDeptId)) {
            throw new ClientException("DEPT_ADMIN 只能管理本部门知识库的角色绑定");
        }
    }

    /**
     * 显式区分系统态与缺失登录态。
     * <ul>
     *   <li>UserContext.isSystem() == true → 返回 true，调用方 early return（MQ/定时任务）</li>
     *   <li>无 UserContext / userId 为空 → 抛 ClientException("missing user context")</li>
     *   <li>正常用户 → 返回 false，调用方继续走原决策路径</li>
     * </ul>
     * 系统态以 LoginUser.system == true 为唯一信号；缺失上下文不退化为放行。
     */
    private boolean bypassIfSystemOrAssertActor() {
        if (UserContext.isSystem()) {
            return true;
        }
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            throw new ClientException("missing user context");
        }
        return false;
    }

    /**
     * 核心聚合：基于当前 DB 快照计算"在给定的排除条件下"还有多少有效 SUPER_ADMIN 用户。
     *
     * @param excludedUserIds    视为已删除的用户 id
     * @param invalidatedRoleIds 视为"不再是 SUPER_ADMIN 来源"的 role id
     * @param userRoleOverrides  用户的模拟角色集覆盖（key=userId, value=新 roleIds）
     */
    private int countSuperAdminsExcluding(
            Set<String> excludedUserIds,
            Set<String> invalidatedRoleIds,
            Map<String, Set<String>> userRoleOverrides) {
        // 1. 有效 SUPER_ADMIN role id 集合（剔除 invalidatedRoleIds）
        List<RoleDO> superRoles = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class)
                        .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
        );
        Set<String> validSuperRoleIds = superRoles.stream()
                .map(RoleDO::getId)
                .filter(id -> !invalidatedRoleIds.contains(id))
                .collect(Collectors.toSet());
        if (validSuperRoleIds.isEmpty()) return 0;

        // 2. 对每个 user 判断其"模拟后角色集"是否与 validSuperRoleIds 有交集
        List<UserDO> allUsers = userMapper.selectList(
                Wrappers.lambdaQuery(UserDO.class).select(UserDO::getId)
        );
        int count = 0;
        for (UserDO user : allUsers) {
            if (excludedUserIds.contains(user.getId())) continue;
            Set<String> effectiveRoleIds;
            if (userRoleOverrides.containsKey(user.getId())) {
                effectiveRoleIds = userRoleOverrides.get(user.getId());
            } else {
                effectiveRoleIds = userRoleMapper.selectList(
                        Wrappers.lambdaQuery(UserRoleDO.class)
                                .eq(UserRoleDO::getUserId, user.getId())
                ).stream().map(UserRoleDO::getRoleId).collect(Collectors.toSet());
            }
            for (String rid : effectiveRoleIds) {
                if (validSuperRoleIds.contains(rid)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }
}
