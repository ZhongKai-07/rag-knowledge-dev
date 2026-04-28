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
import com.knowledgebase.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import com.knowledgebase.ai.ragent.user.dao.entity.SysDeptDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.knowledgebase.ai.ragent.user.service.UserProfileLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserProfileLoaderImpl implements UserProfileLoader {

    private final UserMapper userMapper;
    private final SysDeptMapper sysDeptMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    @Override
    public LoadedUserProfile load(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }

        String deptId = user.getDeptId();
        String deptName = null;
        if (deptId != null) {
            SysDeptDO dept = sysDeptMapper.selectById(deptId);
            if (dept != null) {
                deptName = dept.getDeptName();
            }
        }

        List<String> roleIds = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId)
        ).stream().map(UserRoleDO::getRoleId).collect(Collectors.toList());

        Set<RoleType> roleTypes = EnumSet.noneOf(RoleType.class);
        int maxSecurityLevel = 0;
        if (!roleIds.isEmpty()) {
            List<RoleDO> roles = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds)
            );
            for (RoleDO role : roles) {
                if (role.getRoleType() != null) {
                    try {
                        roleTypes.add(RoleType.valueOf(role.getRoleType()));
                    } catch (IllegalArgumentException ignored) {
                        // role_type DB 写入了枚举外字符串：忽略，避免登录路径整体失败
                    }
                }
                if (role.getMaxSecurityLevel() != null && role.getMaxSecurityLevel() > maxSecurityLevel) {
                    maxSecurityLevel = role.getMaxSecurityLevel();
                }
            }
        }

        boolean isSuperAdmin = roleTypes.contains(RoleType.SUPER_ADMIN);
        boolean isDeptAdmin = roleTypes.contains(RoleType.DEPT_ADMIN);

        return new LoadedUserProfile(
                user.getId(),
                user.getUsername(),
                user.getAvatar(),
                deptId,
                deptName,
                Collections.unmodifiableList(roleIds),
                Collections.unmodifiableSet(roleTypes),
                maxSecurityLevel,
                isSuperAdmin,
                isDeptAdmin
        );
    }
}
