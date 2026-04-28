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

package com.knowledgebase.ai.ragent.user.service.support;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.knowledgebase.ai.ragent.user.service.UserProfileLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbAccessSubjectFactoryImplTest {

    @Mock
    private UserProfileLoader userProfileLoader;

    @InjectMocks
    private KbAccessSubjectFactoryImpl factory;

    @BeforeEach
    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void currentOrThrow_systemActor_throwsIllegalStateException() {
        UserContext.set(LoginUser.builder()
                .username("mq-op")
                .system(true)
                .build());

        assertThatThrownBy(factory::currentOrThrow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system");
    }

    @Test
    void currentOrThrow_noUser_throwsClientException() {
        // UserContext intentionally not set
        assertThatThrownBy(factory::currentOrThrow)
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("missing user context");
    }

    @Test
    void currentOrThrow_validUser_returnsSubjectFromLoginUser() {
        UserContext.set(LoginUser.builder()
                .userId("u1")
                .username("alice")
                .deptId("DEPT_A")
                .roleTypes(Set.of(RoleType.USER))
                .maxSecurityLevel(1)
                .build());

        KbAccessSubject s = factory.currentOrThrow();

        assertThat(s.userId()).isEqualTo("u1");
        assertThat(s.deptId()).isEqualTo("DEPT_A");
        assertThat(s.roleTypes()).containsExactly(RoleType.USER);
        assertThat(s.maxSecurityLevel()).isEqualTo(1);
    }

    @Test
    void forTargetUser_userNotFound_throwsClientException() {
        when(userProfileLoader.load("missing")).thenReturn(null);

        assertThatThrownBy(() -> factory.forTargetUser("missing"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("目标用户不存在");
    }

    @Test
    void forTargetUser_validUser_returnsSubjectFromProfileLoader() {
        when(userProfileLoader.load("ficc_user_id")).thenReturn(new LoadedUserProfile(
                "ficc_user_id", "ficc", null,
                "FICC_DEPT", "FICC", List.of("FICC_USER"),
                Set.of(RoleType.USER), 1, false, false));

        KbAccessSubject s = factory.forTargetUser("ficc_user_id");

        assertThat(s.userId()).isEqualTo("ficc_user_id");
        assertThat(s.deptId()).isEqualTo("FICC_DEPT");
        assertThat(s.roleTypes()).containsExactly(RoleType.USER);
        assertThat(s.maxSecurityLevel()).isEqualTo(1);
    }
}
