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

package com.nageoffer.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * P1.3a: 专供权限中心新页面用的角色 VO。
 * <p>
 * 不复用 {@code RoleDO} 作为返回体，避免污染老 {@code GET /role} 契约；同时增补 {@code deptName}
 * 等衍生字段供前端直接渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessRoleVO {
    private String id;
    private String name;
    private String description;
    /** SUPER_ADMIN / DEPT_ADMIN / USER */
    private String roleType;
    private Integer maxSecurityLevel;
    /** 角色归属部门 ID（sys_dept.id）；"1" = GLOBAL */
    private String deptId;
    /** 角色归属部门名称（冗余字段，前端直接展示） */
    private String deptName;
}
