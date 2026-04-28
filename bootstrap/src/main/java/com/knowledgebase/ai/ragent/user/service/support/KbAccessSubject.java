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

import com.knowledgebase.ai.ragent.framework.context.RoleType;

import java.util.Set;

/**
 * 权限计算的显式主体快照。
 *
 * <p>Calculator 的所有决策**仅**基于此 record 字段 — 无 ThreadLocal、
 * 无延迟加载。两类构造路径见 {@link KbAccessSubjectFactory}.
 *
 * <p>PR3 不变量 PR3-1：calculator 不得 import {@code UserContext} —
 * 主体信息全由调用方通过此 record 传入。
 */
public record KbAccessSubject(
        String userId,
        String deptId,
        Set<RoleType> roleTypes,
        int maxSecurityLevel) {

    public boolean isSuperAdmin() {
        return roleTypes != null && roleTypes.contains(RoleType.SUPER_ADMIN);
    }

    public boolean isDeptAdmin() {
        return roleTypes != null && roleTypes.contains(RoleType.DEPT_ADMIN);
    }
}
