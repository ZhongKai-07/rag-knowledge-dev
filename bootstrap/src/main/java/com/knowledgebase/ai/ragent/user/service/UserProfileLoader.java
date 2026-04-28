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

package com.knowledgebase.ai.ragent.user.service;

import com.knowledgebase.ai.ragent.user.dao.dto.LoadedUserProfile;

/**
 * 单一职责：给 userId 返回完整的 user+dept+role 快照。
 * 被 AuthServiceImpl.login / UserController.currentUser / UserContextInterceptor 共用。
 *
 * <p>PR3 不做 Redis 缓存（Decision 3-G）。每次都 JOIN。
 */
public interface UserProfileLoader {
    /**
     * @param userId 用户主键
     * @return 完整 profile；若用户不存在返回 null
     */
    LoadedUserProfile load(String userId);
}
