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
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KbAccessServiceImpl implements KbAccessService {

    private static final String CACHE_PREFIX = "kb_access:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RedissonClient redissonClient;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;

    @Override
    public Set<String> getAccessibleKbIds(String userId, Permission minPermission) {
        // SUPER_ADMIN 全量放行
        if (isSuperAdmin()) {
            return knowledgeBaseMapper.selectList(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .select(KnowledgeBaseDO::getId)
            ).stream().map(KnowledgeBaseDO::getId).collect(Collectors.toSet());
        }

        // Cache 仅对默认 READ 级别生效，更高级别的查询不走缓存（避免 key 爆炸）
        boolean cacheable = minPermission == Permission.READ;
        String cacheKey = CACHE_PREFIX + userId;
        if (cacheable) {
            RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
            Set<String> cached = bucket.get();
            if (cached != null) {
                return cached;
            }
        }

        // user → roles
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId));
        if (userRoles.isEmpty()) {
            if (cacheable) {
                redissonClient.getBucket(cacheKey).<Set<String>>set(Set.of(), CACHE_TTL);
            }
            return Set.of();
        }

        List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();

        // roles → kb_relations 过滤 permission >= minPermission
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .in(RoleKbRelationDO::getRoleId, roleIds));
        Set<String> kbIds = relations.stream()
                .filter(r -> permissionSatisfies(r.getPermission(), minPermission))
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toSet());

        // 过滤已删除的 KB
        if (!kbIds.isEmpty()) {
            List<KnowledgeBaseDO> validKbs = knowledgeBaseMapper.selectList(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .in(KnowledgeBaseDO::getId, kbIds)
                            .select(KnowledgeBaseDO::getId));
            kbIds = validKbs.stream().map(KnowledgeBaseDO::getId).collect(Collectors.toSet());
        }

        if (cacheable) {
            redissonClient.getBucket(cacheKey).<Set<String>>set(kbIds, CACHE_TTL);
        }
        return kbIds;
    }

    private boolean permissionSatisfies(String actual, Permission required) {
        if (actual == null) return false;
        try {
            return Permission.valueOf(actual).ordinal() >= required.ordinal();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown permission value in DB: {}", actual);
            return false;
        }
    }

    @Override
    public void checkAccess(String kbId) {
        // 系统态（MQ 消费者、定时任务）—— 没有登录态，直接放行
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            return;
        }
        // SUPER_ADMIN 直接放行
        if (isSuperAdmin()) {
            return;
        }
        // 普通用户
        Set<String> accessible = getAccessibleKbIds(UserContext.getUserId(), Permission.READ);
        if (!accessible.contains(kbId)) {
            throw new ClientException("无权访问该知识库: " + kbId);
        }
    }

    @Override
    public void checkManageAccess(String kbId) {
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            // 系统态允许通过（DEPT_ADMIN 不会在 MQ 消费者里触发写接口）
            return;
        }
        if (isSuperAdmin()) {
            return;
        }
        LoginUser user = UserContext.get();
        if (user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.DEPT_ADMIN)) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
            if (kb == null) {
                throw new ClientException("知识库不存在: " + kbId);
            }
            if (user.getDeptId() != null && user.getDeptId().equals(kb.getDeptId())) {
                return;
            }
            throw new ClientException("无权管理其他部门知识库: " + kbId);
        }
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
    public void checkCreateUserAccess(String targetDeptId, java.util.List<String> roleIds) {
        if (!UserContext.hasUser()) {
            throw new ClientException("未登录用户不可创建用户");
        }
        if (isSuperAdmin()) {
            return;
        }
        LoginUser user = UserContext.get();
        if (!isDeptAdmin()) {
            throw new ClientException("无权创建用户");
        }
        if (user.getDeptId() == null || !user.getDeptId().equals(targetDeptId)) {
            throw new ClientException("DEPT_ADMIN 只能在本部门创建用户");
        }
        // 禁止给新用户分配 role_type=SUPER_ADMIN 的角色
        if (roleIds != null && !roleIds.isEmpty()) {
            long superRoleCount = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class)
                            .in(RoleDO::getId, roleIds)
                            .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
            ).size();
            if (superRoleCount > 0) {
                throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
            }
        }
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
            throw new ClientException("无权管理用户");
        }
        LoginUser current = UserContext.get();
        UserDO target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new ClientException("目标用户不存在");
        }
        if (target.getDeptId() == null || !target.getDeptId().equals(current.getDeptId())) {
            throw new ClientException("DEPT_ADMIN 只能管理本部门用户");
        }
    }

    @Override
    public void checkAssignRolesAccess(String targetUserId, java.util.List<String> newRoleIds) {
        // 先复用用户管理权校验
        checkUserManageAccess(targetUserId);
        if (isSuperAdmin()) {
            return; // SUPER_ADMIN 可分配任意角色
        }
        // DEPT_ADMIN：newRoleIds 里不能有 SUPER_ADMIN 角色
        if (newRoleIds != null && !newRoleIds.isEmpty()) {
            long superRoleCount = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class)
                            .in(RoleDO::getId, newRoleIds)
                            .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
            ).size();
            if (superRoleCount > 0) {
                throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
            }
        }
    }
}
