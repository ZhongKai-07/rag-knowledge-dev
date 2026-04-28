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

package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RetrievalScopeBuilderImpl implements RetrievalScopeBuilder {

    private final KbReadAccessPort kbReadAccess;

    @Override
    public RetrievalScope build(String requestedKbId) {
        // 1. fail-closed 优先: requestedKbId 非空 → checkReadAccess 由 PR1
        // bypassIfSystemOrAssertActor 守卫负责无 user/无权抛 ClientException.
        if (requestedKbId != null) {
            kbReadAccess.checkReadAccess(requestedKbId);
        }

        // 2. requestedKbId == null 且无 user → empty (多 KB 全量场景未登录).
        LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
        if (user == null || user.getUserId() == null) {
            return RetrievalScope.empty();
        }

        // 3. SUPER_ADMIN → all sentinel.
        if (user.getRoleTypes() != null && user.getRoleTypes().contains(RoleType.SUPER_ADMIN)) {
            return RetrievalScope.all(requestedKbId);
        }

        // 4. 其他: AccessScope.ids + 一次性算齐 security levels.
        AccessScope accessScope = kbReadAccess.getAccessScope(Permission.READ);
        if (!(accessScope instanceof AccessScope.Ids ids)) {
            // SUPER_ADMIN 已在 step 3 处理; 此处遇到 All 异常防御.
            return new RetrievalScope(accessScope, Map.of(), requestedKbId);
        }
        Set<String> kbIds = ids.kbIds();
        Map<String, Integer> levels = kbIds.isEmpty()
                ? Map.of()
                : kbReadAccess.getMaxSecurityLevelsForKbs(kbIds);
        return new RetrievalScope(accessScope, levels, requestedKbId);
    }
}
