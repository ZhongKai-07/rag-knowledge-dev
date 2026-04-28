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

package com.knowledgebase.ai.ragent.framework.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前登录用户的上下文快照。
 *
 * <p>由 {@code UserContextInterceptor} 在请求进入时一次性从数据库装载，塞进
 * {@code UserContext}（TTL ThreadLocal）。业务代码通过静态方法访问。
 *
 * <p>包含多角色 RBAC 字段：{@code deptId} / {@code roleTypes} / {@code maxSecurityLevel}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser {

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户头像
     */
    private String avatar;

    /**
     * 所属部门 ID。
     */
    private String deptId;

    /**
     * 用户挂载的所有角色类型（跨 {@code t_user_role} 的所有关联去重）。
     */
    private Set<RoleType> roleTypes;

    /**
     * 用户跨所有角色的最大 {@code security_level}。
     * 用于向量检索过滤：{@code metadata.security_level <= maxSecurityLevel}。
     */
    private int maxSecurityLevel;

    /**
     * 系统态执行者标记。仅 MQ 消费者 / 定时任务 / 框架级回调入口允许显式置为 true，
     * 在 KbAccessService.check* 层短路放行。
     * <p>HTTP 入口忘记 attach user 不会落到此路径——guard 通过 isSystem()=false
     * + getUserId()=null 抛 ClientException。
     */
    @lombok.Builder.Default
    private boolean system = false;
}
