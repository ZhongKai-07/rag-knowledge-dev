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

package com.knowledgebase.ai.ragent.framework.security.port;

/**
 * 当前请求上下文的用户角色探针。
 * 只做"是/否"判断，不做权限决策。
 */
public interface CurrentUserProbe {

    /** 当前上下文是否是 SUPER_ADMIN。*/
    boolean isSuperAdmin();

    /** 当前上下文是否是 DEPT_ADMIN（任一部门）。*/
    boolean isDeptAdmin();

    /** 指定 userId 是否持有任一 SUPER_ADMIN 角色。*/
    boolean isUserSuperAdmin(String userId);
}
