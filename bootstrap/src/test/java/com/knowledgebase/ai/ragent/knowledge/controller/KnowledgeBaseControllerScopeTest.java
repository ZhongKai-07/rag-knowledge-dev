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

package com.knowledgebase.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.knowledgebase.ai.ragent.knowledge.service.KbScopeResolver;
import com.knowledgebase.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.knowledgebase.ai.ragent.user.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseControllerScopeTest {

    private KnowledgeBaseService knowledgeBaseService;
    private KbScopeResolver kbScopeResolver;
    private KnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        kbScopeResolver = mock(KbScopeResolver.class);
        RoleService roleService = mock(RoleService.class);
        controller = new KnowledgeBaseController(knowledgeBaseService, kbScopeResolver, roleService);
        when(knowledgeBaseService.pageQuery(any(), any())).thenReturn(new Page<KnowledgeBaseVO>());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void pageQuery_ownerScope_deptAdminUsesOwnedKbIds() {
        UserContext.set(LoginUser.builder()
                .userId("ops-admin")
                .deptId("dept-ops")
                .roleTypes(Set.of(RoleType.DEPT_ADMIN))
                .build());
        when(kbScopeResolver.resolveForOwnerScope(any()))
                .thenReturn(AccessScope.ids(Set.of("kb-ops-1")));

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setScope("owner");
        controller.pageQuery(request);

        ArgumentCaptor<LoginUser> userCaptor = ArgumentCaptor.forClass(LoginUser.class);
        ArgumentCaptor<AccessScope> scopeCaptor = ArgumentCaptor.forClass(AccessScope.class);
        verify(kbScopeResolver).resolveForOwnerScope(userCaptor.capture());
        verify(kbScopeResolver, never()).resolveForRead(any());
        verify(knowledgeBaseService).pageQuery(any(), scopeCaptor.capture());
        assertEquals("ops-admin", userCaptor.getValue().getUserId());
        AccessScope.Ids ids = (AccessScope.Ids) scopeCaptor.getValue();
        assertEquals(Set.of("kb-ops-1"), ids.kbIds());
    }

    @Test
    void pageQuery_defaultScope_deptAdminKeepsAccessSemantics() {
        UserContext.set(LoginUser.builder()
                .userId("ops-admin")
                .deptId("dept-ops")
                .roleTypes(Set.of(RoleType.DEPT_ADMIN))
                .build());
        when(kbScopeResolver.resolveForRead(any()))
                .thenReturn(AccessScope.ids(Set.of("kb-ops-1", "kb-shared")));

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        controller.pageQuery(request);

        ArgumentCaptor<AccessScope> scopeCaptor = ArgumentCaptor.forClass(AccessScope.class);
        verify(kbScopeResolver).resolveForRead(any());
        verify(kbScopeResolver, never()).resolveForOwnerScope(any());
        verify(knowledgeBaseService).pageQuery(any(), scopeCaptor.capture());
        AccessScope.Ids ids = (AccessScope.Ids) scopeCaptor.getValue();
        assertEquals(Set.of("kb-ops-1", "kb-shared"), ids.kbIds());
    }

    @Test
    void pageQuery_ownerScope_superAdminKeepsFullList() {
        UserContext.set(LoginUser.builder()
                .userId("super")
                .deptId("1")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN))
                .build());
        when(kbScopeResolver.resolveForOwnerScope(any())).thenReturn(AccessScope.all());

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setScope("owner");
        controller.pageQuery(request);

        ArgumentCaptor<AccessScope> scopeCaptor = ArgumentCaptor.forClass(AccessScope.class);
        verify(kbScopeResolver).resolveForOwnerScope(any());
        verify(knowledgeBaseService).pageQuery(any(), scopeCaptor.capture());
        assertEquals(AccessScope.all(), scopeCaptor.getValue());
    }
}
