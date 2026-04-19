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

package com.nageoffer.ai.ragent.rag.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * trace / evaluation 查询的当前用户可见范围辅助。
 */
@Component
@RequiredArgsConstructor
public class TraceEvalAccessSupport {

    private final CurrentUserProbe currentUserProbe;
    private final UserMapper userMapper;

    public <T> void applyUserScope(LambdaQueryWrapper<T> wrapper, SFunction<T, ?> userIdGetter) {
        LoginUser current = UserContext.get();
        if (current == null || !StringUtils.hasText(current.getUserId())) {
            wrapper.apply("1 = 0");
            return;
        }
        if (currentUserProbe.isSuperAdmin()) {
            return;
        }
        if (currentUserProbe.isDeptAdmin()) {
            String deptId = current.getDeptId();
            if (!StringUtils.hasText(deptId)) {
                wrapper.apply("1 = 0");
                return;
            }
            wrapper.inSql(
                    userIdGetter,
                    "SELECT id FROM t_user WHERE dept_id = '" + escapeSqlLiteral(deptId) + "' AND deleted = 0"
            );
            return;
        }
        wrapper.eq(userIdGetter, current.getUserId());
    }

    public boolean isVisible(String ownerUserId) {
        LoginUser current = UserContext.get();
        if (current == null || !StringUtils.hasText(current.getUserId()) || !StringUtils.hasText(ownerUserId)) {
            return false;
        }
        if (currentUserProbe.isSuperAdmin()) {
            return true;
        }
        if (currentUserProbe.isDeptAdmin()) {
            String deptId = current.getDeptId();
            if (!StringUtils.hasText(deptId)) {
                return false;
            }
            UserDO owner = userMapper.selectById(ownerUserId);
            return owner != null && Objects.equals(deptId, owner.getDeptId());
        }
        return Objects.equals(current.getUserId(), ownerUserId);
    }

    private String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
