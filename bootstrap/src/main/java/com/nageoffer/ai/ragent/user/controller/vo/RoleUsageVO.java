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
 * P1.3c: 角色使用面板 — 展示某角色挂载了哪些用户、共享给了哪些 KB。
 * 供 Tab 3 角色详情面板 + 删除确认对话框 使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleUsageVO {
    private String roleId;
    private String roleName;
    private String roleType;
    private String deptId;
    private String deptName;
    /** 持有该角色的用户 */
    private List<UserRef> users;
    /** 该角色共享到的 KB */
    private List<KbRef> kbs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRef {
        private String userId;
        private String username;
        private String deptId;
        private String deptName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KbRef {
        private String kbId;
        private String kbName;
        private String deptId;
        private String deptName;
        private String permission;
        private Integer maxSecurityLevel;
    }
}
