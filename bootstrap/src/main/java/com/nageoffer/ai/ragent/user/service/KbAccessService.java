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

package com.nageoffer.ai.ragent.user.service;

import java.util.Set;

public interface KbAccessService {

    /**
     * 获取用户可访问的所有知识库 ID。admin 返回全量。
     */
    Set<String> getAccessibleKbIds(String userId);

    /**
     * 校验当前用户是否有权访问指定知识库，无权则抛异常。
     * 完全依赖 UserContext：系统态（无登录态）直接放行，admin 放行，user 鉴权。
     */
    void checkAccess(String kbId);

    /**
     * 清除指定用户的权限缓存
     */
    void evictCache(String userId);
}
