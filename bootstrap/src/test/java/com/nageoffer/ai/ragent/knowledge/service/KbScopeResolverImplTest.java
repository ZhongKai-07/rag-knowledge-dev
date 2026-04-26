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

package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.Permission;
import com.nageoffer.ai.ragent.framework.context.RoleType;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.framework.security.port.KbMetadataReader;
import com.nageoffer.ai.ragent.framework.security.port.KbReadAccessPort;
import com.nageoffer.ai.ragent.knowledge.service.impl.KbScopeResolverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KbScopeResolverImplTest {

    private KbReadAccessPort kbReadAccess;
    private KbMetadataReader kbMetadataReader;
    private KbScopeResolverImpl resolver;

    @BeforeEach
    void setUp() {
        kbReadAccess = mock(KbReadAccessPort.class);
        kbMetadataReader = mock(KbMetadataReader.class);
        resolver = new KbScopeResolverImpl(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForRead_nullUser_returnsEmpty() {
        AccessScope scope = resolver.resolveForRead(null);

        AccessScope.Ids ids = assertInstanceOf(AccessScope.Ids.class, scope);
        assertEquals(Set.of(), ids.kbIds());
        verifyNoInteractions(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForRead_nullUserId_returnsEmpty() {
        AccessScope scope = resolver.resolveForRead(LoginUser.builder()
                .roleTypes(Set.of(RoleType.USER))
                .build());

        AccessScope.Ids ids = assertInstanceOf(AccessScope.Ids.class, scope);
        assertEquals(Set.of(), ids.kbIds());
        verifyNoInteractions(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForRead_superAdmin_returnsAllWithoutPortInteraction() {
        AccessScope scope = resolver.resolveForRead(LoginUser.builder()
                .userId("super")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN))
                .build());

        assertInstanceOf(AccessScope.All.class, scope);
        verifyNoInteractions(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForRead_regularUser_delegatesToReadPort() {
        AccessScope expected = AccessScope.ids(Set.of("kb-1", "kb-2"));
        when(kbReadAccess.getAccessScope("user-1", Permission.READ)).thenReturn(expected);

        AccessScope scope = resolver.resolveForRead(LoginUser.builder()
                .userId("user-1")
                .roleTypes(Set.of(RoleType.USER))
                .build());

        assertEquals(expected, scope);
        verify(kbReadAccess).getAccessScope("user-1", Permission.READ);
        verifyNoInteractions(kbMetadataReader);
    }

    @Test
    void resolveForOwnerScope_superAdmin_returnsAllWithoutMetadataInteraction() {
        AccessScope scope = resolver.resolveForOwnerScope(LoginUser.builder()
                .userId("super")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN))
                .build());

        assertInstanceOf(AccessScope.All.class, scope);
        verifyNoInteractions(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForOwnerScope_deptAdmin_returnsDeptKbIds() {
        when(kbMetadataReader.listKbIdsByDeptId("dept-1")).thenReturn(Set.of("kb-dept"));

        AccessScope scope = resolver.resolveForOwnerScope(LoginUser.builder()
                .userId("admin-1")
                .deptId("dept-1")
                .roleTypes(Set.of(RoleType.DEPT_ADMIN))
                .build());

        AccessScope.Ids ids = assertInstanceOf(AccessScope.Ids.class, scope);
        assertEquals(Set.of("kb-dept"), ids.kbIds());
        verify(kbMetadataReader).listKbIdsByDeptId("dept-1");
        verifyNoInteractions(kbReadAccess);
    }

    @Test
    void resolveForOwnerScope_deptAdminWithNullDept_returnsEmpty() {
        AccessScope scope = resolver.resolveForOwnerScope(LoginUser.builder()
                .userId("admin-1")
                .roleTypes(Set.of(RoleType.DEPT_ADMIN))
                .build());

        AccessScope.Ids ids = assertInstanceOf(AccessScope.Ids.class, scope);
        assertEquals(Set.of(), ids.kbIds());
        verifyNoInteractions(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForOwnerScope_regularUser_returnsEmpty() {
        AccessScope scope = resolver.resolveForOwnerScope(LoginUser.builder()
                .userId("user-1")
                .deptId("dept-1")
                .roleTypes(Set.of(RoleType.USER))
                .build());

        AccessScope.Ids ids = assertInstanceOf(AccessScope.Ids.class, scope);
        assertEquals(Set.of(), ids.kbIds());
        verifyNoInteractions(kbReadAccess, kbMetadataReader);
    }

    @Test
    void resolveForOwnerScope_nullUser_returnsEmpty() {
        AccessScope scope = resolver.resolveForOwnerScope(null);

        AccessScope.Ids ids = assertInstanceOf(AccessScope.Ids.class, scope);
        assertEquals(Set.of(), ids.kbIds());
        verifyNoInteractions(kbReadAccess, kbMetadataReader);
    }
}
