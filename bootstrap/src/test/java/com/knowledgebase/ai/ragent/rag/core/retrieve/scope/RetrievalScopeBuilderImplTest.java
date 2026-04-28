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
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalScopeBuilderImplTest {

    @Mock
    private KbReadAccessPort kbReadAccess;

    @InjectMocks
    private RetrievalScopeBuilderImpl builder;

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void requestedKbId_null_and_not_logged_in_returns_empty() {
        UserContext.clear();

        RetrievalScope scope = builder.build(null);

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.Ids.class);
        assertThat(((AccessScope.Ids) scope.accessScope()).kbIds()).isEmpty();
        assertThat(scope.kbSecurityLevels()).isEmpty();
        assertThat(scope.targetKbId()).isNull();
        verifyNoMoreInteractions(kbReadAccess);
    }

    @Test
    void requestedKbId_present_and_not_logged_in_throws_missing_user_context() {
        doThrow(new ClientException("missing user context"))
                .when(kbReadAccess).checkReadAccess("kb-1");
        UserContext.clear();

        assertThatThrownBy(() -> builder.build("kb-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("missing user context");

        verify(kbReadAccess).checkReadAccess("kb-1");
        verify(kbReadAccess, never()).getAccessScope(Permission.READ);
        verify(kbReadAccess, never()).getMaxSecurityLevelsForKbs(Set.of("kb-1"));
    }

    @Test
    void requestedKbId_present_and_unauthorized_propagates_client_exception() {
        doThrow(new ClientException("无权访问"))
                .when(kbReadAccess).checkReadAccess("kb-1");
        UserContext.set(user("u-1", RoleType.USER));

        assertThatThrownBy(() -> builder.build("kb-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("无权访问");

        verify(kbReadAccess).checkReadAccess("kb-1");
        verify(kbReadAccess, never()).getAccessScope(Permission.READ);
    }

    @Test
    void super_admin_without_requestedKbId_returns_all_without_levels() {
        UserContext.set(user("sa-1", RoleType.SUPER_ADMIN));

        RetrievalScope scope = builder.build(null);

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.All.class);
        assertThat(scope.targetKbId()).isNull();
        assertThat(scope.kbSecurityLevels()).isEmpty();
        verifyNoMoreInteractions(kbReadAccess);
    }

    @Test
    void super_admin_with_requestedKbId_checks_read_then_returns_all_with_target() {
        UserContext.set(user("sa-1", RoleType.SUPER_ADMIN));

        RetrievalScope scope = builder.build("kb-1");

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.All.class);
        assertThat(scope.targetKbId()).isEqualTo("kb-1");
        assertThat(scope.kbSecurityLevels()).isEmpty();
        verify(kbReadAccess).checkReadAccess("kb-1");
        verify(kbReadAccess, never()).getAccessScope(Permission.READ);
        verify(kbReadAccess, never()).getMaxSecurityLevelsForKbs(Set.of("kb-1"));
    }

    @Test
    void dept_admin_gets_ids_scope_and_security_levels_once() {
        Set<String> kbIds = Set.of("kb-1", "kb-2");
        AccessScope accessScope = AccessScope.ids(kbIds);
        Map<String, Integer> levels = Map.of("kb-1", 2, "kb-2", 1);
        when(kbReadAccess.getAccessScope(Permission.READ)).thenReturn(accessScope);
        when(kbReadAccess.getMaxSecurityLevelsForKbs(kbIds)).thenReturn(levels);
        UserContext.set(user("dept-1", RoleType.DEPT_ADMIN));

        RetrievalScope scope = builder.build(null);

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.Ids.class);
        assertThat(((AccessScope.Ids) scope.accessScope()).kbIds()).containsExactlyInAnyOrderElementsOf(kbIds);
        assertThat(scope.kbSecurityLevels()).containsExactlyInAnyOrderEntriesOf(levels);
        assertThat(scope.targetKbId()).isNull();
        verify(kbReadAccess).getAccessScope(Permission.READ);
        verify(kbReadAccess).getMaxSecurityLevelsForKbs(kbIds);
    }

    @Test
    void user_gets_ids_scope_and_security_levels_for_requestedKbId() {
        Set<String> kbIds = Set.of("kb-1");
        AccessScope accessScope = AccessScope.ids(kbIds);
        Map<String, Integer> levels = Map.of("kb-1", 1);
        when(kbReadAccess.getAccessScope(Permission.READ)).thenReturn(accessScope);
        when(kbReadAccess.getMaxSecurityLevelsForKbs(kbIds)).thenReturn(levels);
        UserContext.set(user("u-1", RoleType.USER));

        RetrievalScope scope = builder.build("kb-1");

        assertThat(scope.accessScope()).isInstanceOf(AccessScope.Ids.class);
        assertThat(((AccessScope.Ids) scope.accessScope()).kbIds()).containsExactly("kb-1");
        assertThat(scope.kbSecurityLevels()).containsExactlyEntriesOf(levels);
        assertThat(scope.targetKbId()).isEqualTo("kb-1");
        verify(kbReadAccess).checkReadAccess("kb-1");
        verify(kbReadAccess).getAccessScope(Permission.READ);
        verify(kbReadAccess).getMaxSecurityLevelsForKbs(kbIds);
    }

    private static LoginUser user(String userId, RoleType roleType) {
        return LoginUser.builder()
                .userId(userId)
                .username(userId)
                .roleTypes(Set.of(roleType))
                .build();
    }
}
