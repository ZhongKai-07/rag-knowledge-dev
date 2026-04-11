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

package com.nageoffer.ai.ragent.framework.context;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LoginUserTests {

    @Test
    void builder_creates_user_with_multi_role() {
        LoginUser user = LoginUser.builder()
                .userId("1")
                .username("alice")
                .role("admin")            // legacy
                .avatar("https://x.com/a.png")
                .deptId("OPS")
                .roleTypes(Set.of(RoleType.SUPER_ADMIN, RoleType.USER))
                .maxSecurityLevel(3)
                .build();

        assertEquals("1", user.getUserId());
        assertEquals("alice", user.getUsername());
        assertEquals("OPS", user.getDeptId());
        assertEquals(Set.of(RoleType.SUPER_ADMIN, RoleType.USER), user.getRoleTypes());
        assertEquals(3, user.getMaxSecurityLevel());
    }

    @Test
    void default_values_for_optional_fields() {
        LoginUser user = LoginUser.builder()
                .userId("2")
                .username("bob")
                .role("user")
                .build();

        assertNull(user.getDeptId());
        assertNull(user.getRoleTypes());
        assertEquals(0, user.getMaxSecurityLevel());
    }
}
