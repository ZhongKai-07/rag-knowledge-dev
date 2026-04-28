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

import java.util.Set;

/**
 * 知识库访问范围，检索路径单一状态源。
 * 使用 if instanceof 消费（Java 17，禁止 switch record pattern）。
 *
 * 示例消费模式：
 * <pre>
 * if (scope instanceof AccessScope.All) { // 全量放行，不过滤 }
 * if (scope instanceof AccessScope.Ids ids) { filterBy(ids.kbIds()); }
 * </pre>
 */
public sealed interface AccessScope
        permits AccessScope.All, AccessScope.Ids {

    /**
     * 指定 kbId 是否在当前访问范围内。{@code null} 视为无效 KB, 一律 fail-closed。
     */
    boolean allows(String kbId);

    /** 全量放行（SUPER_ADMIN 或系统态）。*/
    record All() implements AccessScope {
        @Override
        public boolean allows(String kbId) {
            return kbId != null;
        }
    }

    /**
     * 仅允许指定 KB ID 集合。
     * 空集表示无权限（未登录 / 无任何授权 KB）。
     */
    record Ids(Set<String> kbIds) implements AccessScope {
        @Override
        public boolean allows(String kbId) {
            return kbId != null && kbIds.contains(kbId);
        }
    }

    static AccessScope all() {
        return new All();
    }

    static AccessScope ids(Set<String> kbIds) {
        return new Ids(kbIds);
    }

    static AccessScope empty() {
        return new Ids(Set.of());
    }
}
