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

package com.nageoffer.ai.ragent.knowledge.service;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;

/**
 * Resolves knowledge-base list/search scopes as {@link AccessScope}.
 *
 * <p>Callers provide an already captured {@link LoginUser}; this keeps controller/service
 * boundaries explicit and avoids using nullable ID sets as an implicit tri-state.
 */
public interface KbScopeResolver {

    /**
     * Resolve the KBs a user may read.
     *
     * @param user caller identity snapshot, or {@code null} for no caller
     * @return read scope; unauthenticated or incomplete identities return {@link AccessScope#empty()}
     */
    AccessScope resolveForRead(LoginUser user);

    /**
     * Resolve the KBs a user owns for admin list pages.
     *
     * @param user caller identity snapshot, or {@code null} for no caller
     * @return owner scope; unauthenticated, incomplete, or non-admin identities return {@link AccessScope#empty()}
     */
    AccessScope resolveForOwnerScope(LoginUser user);
}
