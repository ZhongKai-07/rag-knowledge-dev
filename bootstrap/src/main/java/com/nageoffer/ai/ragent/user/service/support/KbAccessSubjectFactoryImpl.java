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

package com.nageoffer.ai.ragent.user.service.support;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.nageoffer.ai.ragent.user.service.UserProfileLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KbAccessSubjectFactoryImpl implements KbAccessSubjectFactory {

    private final UserProfileLoader userProfileLoader;

    @Override
    public KbAccessSubject currentOrThrow() {
        if (UserContext.isSystem()) {
            throw new IllegalStateException(
                    "system actor cannot construct KbAccessSubject; "
                            + "system path bypasses calculator (PR1 invariant B)");
        }
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            throw new ClientException("missing user context");
        }
        LoginUser u = UserContext.get();
        return new KbAccessSubject(
                u.getUserId(),
                u.getDeptId(),
                u.getRoleTypes(),
                u.getMaxSecurityLevel());
    }

    @Override
    public KbAccessSubject forTargetUser(String userId) {
        LoadedUserProfile p = userProfileLoader.load(userId);
        if (p == null) {
            throw new ClientException("目标用户不存在: " + userId);
        }
        return new KbAccessSubject(
                p.userId(),
                p.deptId(),
                p.roleTypes(),
                p.maxSecurityLevel());
    }
}
