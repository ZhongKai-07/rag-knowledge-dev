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

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserRoleDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.SuperAdminMutationIntent;
import com.nageoffer.ai.ragent.user.service.UserProfileLoader;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final KbAccessService kbAccessService;
    private final UserProfileLoader userProfileLoader;

    @Override
    public IPage<UserVO> pageQuery(UserPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        var wrapper = Wrappers.lambdaQuery(UserDO.class)
                .like(StrUtil.isNotBlank(keyword), UserDO::getUsername, keyword)
                .orderByDesc(UserDO::getUpdateTime);

        if (!kbAccessService.isSuperAdmin() && kbAccessService.isDeptAdmin()) {
            // DEPT_ADMIN only sees users from their dept
            LoginUser currentUser = UserContext.get();
            if (currentUser != null && currentUser.getDeptId() != null) {
                wrapper.eq(UserDO::getDeptId, currentUser.getDeptId());
            }
        }

        IPage<UserDO> result = userMapper.selectPage(page, wrapper);
        return result.convert(this::toVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(UserCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
        Assert.notBlank(password, () -> new ClientException("密码不能为空"));

        kbAccessService.checkCreateUserAccess(requestParam.getDeptId(), requestParam.getRoleIds());

        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("默认管理员用户名不可用");
        }
        ensureUsernameAvailable(username, null);

        UserDO record = UserDO.builder()
                .username(username)
                .password(password)
                .role(UserRole.USER.getCode())
                .avatar(StrUtil.trimToNull(requestParam.getAvatar()))
                .deptId(requestParam.getDeptId())
                .build();
        userMapper.insert(record);

        if (requestParam.getRoleIds() != null) {
            for (String roleId : requestParam.getRoleIds()) {
                UserRoleDO ur = new UserRoleDO();
                ur.setUserId(record.getId());
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }

        return String.valueOf(record.getId());
    }

    @Override
    public void update(String id, UserUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);

        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (!username.equals(record.getUsername())) {
                if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                    throw new ClientException("默认管理员用户名不可用");
                }
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        if (requestParam.getPassword() != null) {
            String password = StrUtil.trimToNull(requestParam.getPassword());
            Assert.notBlank(password, () -> new ClientException("新密码不能为空"));
            record.setPassword(password);
        }

        if (requestParam.getDeptId() != null) {
            record.setDeptId(requestParam.getDeptId());
        }

        userMapper.updateById(record);
    }

    @Override
    public void delete(String id) {
        if (kbAccessService.isUserSuperAdmin(id)) {
            int after = kbAccessService.simulateActiveSuperAdminCountAfter(
                    new SuperAdminMutationIntent.DeleteUser(id));
            if (after < 1) {
                throw new ClientException("不能删除该用户：此操作会使系统失去最后一个 SUPER_ADMIN");
            }
        }
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        userMapper.deleteById(record.getId());
    }

    @Override
    public void changePassword(ChangePasswordRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String current = StrUtil.trimToNull(requestParam.getCurrentPassword());
        String next = StrUtil.trimToNull(requestParam.getNewPassword());
        Assert.notBlank(current, () -> new ClientException("当前密码不能为空"));
        Assert.notBlank(next, () -> new ClientException("新密码不能为空"));

        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, loginUser.getUserId()));
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        if (!passwordMatches(current, record.getPassword())) {
            throw new ClientException("当前密码不正确");
        }
        record.setPassword(next);
        userMapper.updateById(record);
    }

    private UserDO loadById(String id) {
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, id));
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        return record;
    }

    private void ensureNotDefaultAdmin(UserDO record) {
        if (record != null && DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
            throw new ClientException("默认管理员不允许修改或删除");
        }
    }

    private void ensureUsernameAvailable(String username, String excludeId) {
        UserDO existing = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .ne(excludeId != null, UserDO::getId, excludeId));
        if (existing != null) {
            throw new ClientException("用户名已存在");
        }
    }

    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }

    private UserVO toVO(UserDO record) {
        UserVO vo = UserVO.builder()
                .id(String.valueOf(record.getId()))
                .username(record.getUsername())
                .avatar(record.getAvatar())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
        // Enrich with profile data (deptName, roleTypes, maxSecurityLevel)
        try {
            LoadedUserProfile profile = userProfileLoader.load(record.getId());
            if (profile != null) {
                vo.setDeptId(profile.deptId());
                vo.setDeptName(profile.deptName());
                vo.setRoleTypes(profile.roleTypes().stream().map(Enum::name).toList());
                vo.setMaxSecurityLevel(profile.maxSecurityLevel());
            }
        } catch (Exception ignored) {
            // Best-effort enrichment; fall back to bare UserDO fields
            vo.setDeptId(record.getDeptId());
        }
        return vo;
    }
}
