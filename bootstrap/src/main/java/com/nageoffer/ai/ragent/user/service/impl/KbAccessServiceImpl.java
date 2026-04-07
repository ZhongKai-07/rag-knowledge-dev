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
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
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

    @Override
    public Set<String> getAccessibleKbIds(String userId) {
        // Check cache
        String cacheKey = CACHE_PREFIX + userId;
        RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
        Set<String> cached = bucket.get();
        if (cached != null) {
            return cached;
        }

        // Query: user -> roles
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId));

        if (userRoles.isEmpty()) {
            bucket.set(Set.of(), CACHE_TTL);
            return Set.of();
        }

        List<String> roleIds = userRoles.stream()
                .map(UserRoleDO::getRoleId)
                .toList();

        // Query: roles -> kb_ids
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .in(RoleKbRelationDO::getRoleId, roleIds));

        Set<String> kbIds = relations.stream()
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toSet());

        // Filter out deleted KBs
        if (!kbIds.isEmpty()) {
            List<KnowledgeBaseDO> validKbs = knowledgeBaseMapper.selectList(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .in(KnowledgeBaseDO::getId, kbIds)
                            .select(KnowledgeBaseDO::getId));

            kbIds = validKbs.stream()
                    .map(KnowledgeBaseDO::getId)
                    .collect(Collectors.toSet());
        }

        bucket.set(kbIds, CACHE_TTL);
        return kbIds;
    }

    @Override
    public void checkAccess(String kbId) {
        // System context (MQ consumer, scheduled task) — no full login state, skip
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            return;
        }
        // Admin bypass
        if ("admin".equals(UserContext.getRole())) {
            return;
        }
        // User authorization check
        Set<String> accessible = getAccessibleKbIds(UserContext.getUserId());
        if (!accessible.contains(kbId)) {
            throw new ClientException("无权访问该知识库: " + kbId);
        }
    }

    @Override
    public void evictCache(String userId) {
        redissonClient.getBucket(CACHE_PREFIX + userId).delete();
    }
}
