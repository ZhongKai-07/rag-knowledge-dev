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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContextTests {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void isSystem_returns_false_when_no_user_context_set() {
        assertFalse(UserContext.isSystem());
    }

    @Test
    void isSystem_returns_false_for_normal_user() {
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
        assertFalse(UserContext.isSystem());
    }

    @Test
    void isSystem_returns_true_for_explicit_system_actor() {
        UserContext.set(LoginUser.builder().username("mq-op").system(true).build());
        assertTrue(UserContext.isSystem());
    }
}
