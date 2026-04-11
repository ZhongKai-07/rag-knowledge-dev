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

package com.nageoffer.ai.ragent.user.config;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户上下文拦截器。
 *
 * <p>在请求进入时一次性从数据库 join 查出 {@code user + roles}，封装成 {@link LoginUser}
 * 塞进 {@link UserContext} ThreadLocal。后续业务代码无需再走 DB。
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        // 异步调度请求跳过（SSE 完成回调会触发 asyncDispatch，此时 SaToken 上下文已丢失）
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        // 预检请求放行，避免 CORS 阻断
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String loginId = StpUtil.getLoginIdAsString();
        UserDO user = userMapper.selectById(loginId);

        // 一次性 join 查所有关联角色
        List<RoleDO> roles = roleMapper.selectRolesByUserId(loginId);
        Set<RoleType> roleTypes = roles.stream()
                .map(RoleDO::getRoleType)
                .filter(StrUtil::isNotBlank)
                .map(rt -> {
                    try {
                        return RoleType.valueOf(rt);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(RoleType.class)));
        int maxSecurityLevel = roles.stream()
                .mapToInt(r -> r.getMaxSecurityLevel() == null ? 0 : r.getMaxSecurityLevel())
                .max()
                .orElse(0);

        UserContext.set(
                LoginUser.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .role(user.getRole())
                        .avatar(StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar())
                        .deptId(user.getDeptId())
                        .roleTypes(roleTypes)
                        .maxSecurityLevel(maxSecurityLevel)
                        .build()
        );
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
        UserContext.clear();
    }
}
