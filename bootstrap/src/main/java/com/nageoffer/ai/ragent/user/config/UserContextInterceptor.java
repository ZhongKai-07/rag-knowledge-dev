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
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.nageoffer.ai.ragent.user.service.UserProfileLoader;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文拦截器。
 *
 * <p>在请求进入时通过 {@link UserProfileLoader} 加载完整的 user+dept+role 快照，
 * 封装成 {@link LoginUser} 塞进 {@link UserContext} ThreadLocal。后续业务代码无需再走 DB。
 */
@Component
@RequiredArgsConstructor
public class UserContextInterceptor implements HandlerInterceptor {

    private final UserProfileLoader userProfileLoader;

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
        LoadedUserProfile profile = userProfileLoader.load(loginId);
        if (profile == null) {
            return true;
        }

        UserContext.set(
                LoginUser.builder()
                        .userId(profile.userId())
                        .username(profile.username())
                        .avatar(profile.avatar())
                        .deptId(profile.deptId())
                        .roleTypes(profile.roleTypes())
                        .maxSecurityLevel(profile.maxSecurityLevel())
                        .build()
        );
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler, Exception ex) {
        UserContext.clear();
    }
}
