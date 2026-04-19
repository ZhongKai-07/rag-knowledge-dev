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

import java.util.List;

/**
 * P1.3b: 用户对单个知识库的有效权限记录（Tab 1 成员面板"可访问的知识库"列表）。
 * <p>
 * 算法见设计文档 §二 D13（review v3 #2 修正，四步走）。
 * {@code implicit} 与 explicit 并行判定，effectivePermission = implicit ? MANAGE : explicit。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserKbGrantVO {
    private String kbId;
    private String kbName;
    private String deptId;
    /** 有效权限：implicit ? MANAGE : explicitPermission */
    private String permission;
    /** 仅来自显式 role 链的权限；null 表示同部门 DEPT_ADMIN 隐式命中，无显式绑定。审计/调试用。 */
    private String explicitPermission;
    /** 该 KB 密级上限（getMaxSecurityLevelForKb） */
    private Integer securityLevel;
    /** 给出 explicitPermission 的角色 ID 列表；implicit 命中时为空数组 */
    private List<String> sourceRoleIds;
    /** 是否来自"DEPT_ADMIN 同部门隐式 MANAGE"。true 时 permission 被强制拉到 MANAGE。 */
    private boolean implicit;
}
