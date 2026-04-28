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

package com.knowledgebase.ai.ragent.user.dao.dto;

import com.knowledgebase.ai.ragent.framework.context.RoleType;

import java.util.List;
import java.util.Set;

/**
 * 用户身份快照（bootstrap 内部 DTO）。
 *
 * <p>单次 JOIN 的结果：t_user + sys_dept + t_user_role + t_role。
 * 从这里可投影到 LoginUser（授权用）/ LoginVO（登录响应）/ CurrentUserVO（/user/me 响应）。
 */
public record LoadedUserProfile(
        String userId,
        String username,
        String avatar,
        String deptId,
        String deptName,
        List<String> roleIds,
        Set<RoleType> roleTypes,
        int maxSecurityLevel,
        boolean isSuperAdmin,
        boolean isDeptAdmin
) {}
