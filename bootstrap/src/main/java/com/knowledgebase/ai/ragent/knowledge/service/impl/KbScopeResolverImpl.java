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

package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import com.knowledgebase.ai.ragent.knowledge.service.KbScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class KbScopeResolverImpl implements KbScopeResolver {

    private final KbReadAccessPort kbReadAccess;
    private final KbMetadataReader kbMetadataReader;

    @Override
    public AccessScope resolveForRead(LoginUser user) {
        if (user == null || user.getUserId() == null) {
            return AccessScope.empty();
        }
        if (hasRole(user, RoleType.SUPER_ADMIN)) {
            return AccessScope.all();
        }
        return kbReadAccess.getAccessScope(Permission.READ);
    }

    @Override
    public AccessScope resolveForOwnerScope(LoginUser user) {
        if (user == null || user.getUserId() == null) {
            return AccessScope.empty();
        }
        if (hasRole(user, RoleType.SUPER_ADMIN)) {
            return AccessScope.all();
        }
        if (hasRole(user, RoleType.DEPT_ADMIN) && user.getDeptId() != null) {
            Set<String> kbIds = kbMetadataReader.listKbIdsByDeptId(user.getDeptId());
            return AccessScope.ids(kbIds != null ? kbIds : Set.of());
        }
        return AccessScope.empty();
    }

    private boolean hasRole(LoginUser user, RoleType roleType) {
        return user.getRoleTypes() != null && user.getRoleTypes().contains(roleType);
    }
}
