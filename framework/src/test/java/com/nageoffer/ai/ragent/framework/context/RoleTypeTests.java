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
import static org.junit.jupiter.api.Assertions.*;

class RoleTypeTests {

    @Test
    void valueOf_recognizes_all_three_roles() {
        assertEquals(RoleType.SUPER_ADMIN, RoleType.valueOf("SUPER_ADMIN"));
        assertEquals(RoleType.DEPT_ADMIN, RoleType.valueOf("DEPT_ADMIN"));
        assertEquals(RoleType.USER, RoleType.valueOf("USER"));
    }

    @Test
    void permission_ordering_READ_less_than_WRITE_less_than_MANAGE() {
        // READ(0) < WRITE(1) < MANAGE(2) —— 用 ordinal() 方便比较"至少是 READ"
        assertTrue(Permission.READ.ordinal() < Permission.WRITE.ordinal());
        assertTrue(Permission.WRITE.ordinal() < Permission.MANAGE.ordinal());
    }
}
