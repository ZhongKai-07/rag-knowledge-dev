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

package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import com.knowledgebase.ai.ragent.framework.security.port.AccessScope;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索 scope 一等公民 record,承载权限决策三件套:
 * <ul>
 *   <li>{@code accessScope} — 用户对 KB 的访问范围 (sealed All/Ids)</li>
 *   <li>{@code kbSecurityLevels} — 当前用户在每个 KB 上的天花板 (immutable, 可空)</li>
 *   <li>{@code targetKbId} — 单 KB 定向请求 (nullable; null 表示多 KB 全量)</li>
 * </ul>
 *
 * <p>由 {@link RetrievalScopeBuilder#build(String)} 在生产请求路径构造,
 * 异步/系统态路径 (如 EvalRunExecutor) 用 {@link #all(String)} sentinel.
 *
 * <p>PR4-1 不变量: 项目内构造 {@code new RetrievalScope(...)} 仅允许
 * 出现在本 record 自身(静态工厂内)、{@link RetrievalScopeBuilderImpl}、
 * 测试代码三处. ArchUnit 守门见 {@code PermissionBoundaryArchTest}.
 */
public record RetrievalScope(
        AccessScope accessScope,
        Map<String, Integer> kbSecurityLevels,
        String targetKbId) {

    public RetrievalScope {
        Objects.requireNonNull(accessScope, "accessScope");
        // review P2#2: AccessScope.Ids 可能持有可变 Set, 重新包装彻底冻结.
        if (accessScope instanceof AccessScope.Ids ids) {
            accessScope = AccessScope.ids(Set.copyOf(ids.kbIds()));
        }
        kbSecurityLevels = kbSecurityLevels == null
                ? Map.of()
                : Map.copyOf(kbSecurityLevels);
    }

    /** 未登录或显式无授权: 空 ids + 空 map + null target. */
    public static RetrievalScope empty() {
        return new RetrievalScope(AccessScope.empty(), Map.of(), null);
    }

    /**
     * 全量放行 sentinel (SUPER_ADMIN 或系统态/eval 路径合法持有).
     * kbSecurityLevels 留空 — SUPER_ADMIN 跳过等级过滤由 AuthzPostProcessor
     * ceiling==null 分支自然处理.
     */
    public static RetrievalScope all(String targetKbId) {
        return new RetrievalScope(AccessScope.all(), Map.of(), targetKbId);
    }

    /** 单 KB 定向场景: targetKbId 非空. 多 KB 全量场景: null. */
    public boolean isSingleKb() {
        return targetKbId != null;
    }
}
