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

package com.knowledgebase.ai.ragent.user.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.util.StrUtil;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限认证接口实现。
 *
 * <p>{@code @SaCheckRole("X")} 注解匹配的是这里 {@link #getRoleList} 返回的字符串列表元素。
 * PR1 后返回值是 {@code List<RoleType.name()>}，所以注解里的字符串也从 {@code "admin"}
 * 替换成 {@code "SUPER_ADMIN"}（见 Task 10）。
 */
@Component
@RequiredArgsConstructor
public class SaTokenStpInterfaceImpl implements StpInterface {

    private final RoleMapper roleMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return Collections.emptyList();
        }
        String loginIdStr = loginId.toString();
        if (!StrUtil.isNumeric(loginIdStr)) {
            return Collections.emptyList();
        }

        List<String> roleTypes = roleMapper.selectRoleTypesByUserId(loginIdStr);
        return roleTypes == null ? Collections.emptyList() : roleTypes;
    }
}
