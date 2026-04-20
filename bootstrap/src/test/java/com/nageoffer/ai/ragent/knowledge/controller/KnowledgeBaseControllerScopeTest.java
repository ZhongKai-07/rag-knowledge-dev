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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseControllerScopeTest {

    private KnowledgeBaseService knowledgeBaseService;
    private KbAccessService kbAccessService;
    private KbMetadataReader kbMetadataReader;
    private KnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        kbAccessService = mock(KbAccessService.class);
        RoleService roleService = mock(RoleService.class);
        kbMetadataReader = mock(KbMetadataReader.class);
        controller = new KnowledgeBaseController(knowledgeBaseService, kbAccessService, roleService, kbMetadataReader);
        when(knowledgeBaseService.pageQuery(any())).thenReturn(new Page<KnowledgeBaseVO>());
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
        when(kbAccessService.isSuperAdmin()).thenReturn(false);
        when(kbAccessService.isDeptAdmin()).thenReturn(true);
        when(kbMetadataReader.listKbIdsByDeptId("dept-ops")).thenReturn(Set.of("kb-ops-1"));

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setScope("owner");
        controller.pageQuery(request);

        ArgumentCaptor<KnowledgeBasePageRequest> captor = ArgumentCaptor.forClass(KnowledgeBasePageRequest.class);
        verify(knowledgeBaseService).pageQuery(captor.capture());
        assertEquals(Set.of("kb-ops-1"), captor.getValue().getAccessibleKbIds());
        verify(kbAccessService, never()).getAccessibleKbIds("ops-admin");
    }

    @Test
    void pageQuery_defaultScope_deptAdminKeepsAccessSemantics() {
        UserContext.set(LoginUser.builder()
                .userId("ops-admin")
                .deptId("dept-ops")
                .roleTypes(Set.of(RoleType.DEPT_ADMIN))
                .build());
        when(kbAccessService.isSuperAdmin()).thenReturn(false);
        when(kbAccessService.getAccessibleKbIds("ops-admin")).thenReturn(Set.of("kb-ops-1", "kb-shared"));

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        controller.pageQuery(request);

        ArgumentCaptor<KnowledgeBasePageRequest> captor = ArgumentCaptor.forClass(KnowledgeBasePageRequest.class);
        verify(knowledgeBaseService).pageQuery(captor.capture());
        assertEquals(Set.of("kb-ops-1", "kb-shared"), captor.getValue().getAccessibleKbIds());
    }

    @Test
    void pageQuery_ownerScope_superAdminKeepsFullList() {
        UserContext.set(LoginUser.builder()
                .userId("super")
                .deptId("1")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN))
                .build());
        when(kbAccessService.isSuperAdmin()).thenReturn(true);

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setScope("owner");
        controller.pageQuery(request);

        ArgumentCaptor<KnowledgeBasePageRequest> captor = ArgumentCaptor.forClass(KnowledgeBasePageRequest.class);
        verify(knowledgeBaseService).pageQuery(captor.capture());
        assertNull(captor.getValue().getAccessibleKbIds());
        verify(kbMetadataReader, never()).listKbIdsByDeptId(any());
    }
}
