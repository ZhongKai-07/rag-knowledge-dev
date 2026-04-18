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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 授权后置处理器（纵深防御）。
 * <p>
 * 以 order=0 最先执行, 对 retriever 侧已做的权限过滤做二次校验:
 * <ol>
 *   <li>{@code kbId == null/blank} → fail-closed drop（防 Milvus/Pg 穿透）</li>
 *   <li>scope 为 Ids 且 kbId 不在白名单 → drop</li>
 *   <li>securityLevel > 用户在该 KB 的天花板 → drop</li>
 * </ol>
 * <p>
 * 若 dropped > 0, 以 ERROR 级别记录 — 该日志出现表示 retriever 过滤存在漏洞。
 */
@Slf4j
@Component
public class AuthzPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "Authz";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return UserContext.hasUser();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        AccessScope scope = context.getAccessScope();
        List<RetrievedChunk> filtered = chunks.stream()
                .filter(chunk -> isAllowed(chunk, scope, context))
                .collect(Collectors.toList());

        int dropped = chunks.size() - filtered.size();
        if (dropped == 0) {
            // 正常路径: retriever 已过滤干净, 避免返回新 list 拷贝
            return chunks;
        }
        log.error("AuthzPostProcessor dropped {} chunks – retriever filter failure detected. " +
                        "Scope type: {}",
                dropped,
                scope == null ? "null" : scope.getClass().getSimpleName());
        return filtered;
    }

    private boolean isAllowed(RetrievedChunk chunk, AccessScope scope, SearchContext context) {
        String kbId = chunk.getKbId();

        // Rule 1: kbId 缺失 → fail-closed（防 Milvus/Pg 后端穿透）
        if (kbId == null || kbId.isBlank()) {
            log.warn("AuthzPostProcessor: dropping chunk id={} – kbId is null/blank (non-OpenSearch backend?)",
                    chunk.getId());
            return false;
        }

        // Rule 2: 白名单检查 (All scope 对非空 kbId 全放行, Ids 查集合)
        if (scope == null || !scope.allows(kbId)) {
            return false;
        }

        // Rule 3: security_level 天花板检查
        Integer level = chunk.getSecurityLevel();
        if (level != null && context.getKbSecurityLevels() != null) {
            Integer ceiling = context.getKbSecurityLevels().get(kbId);
            // ceiling == null: 系统态或 SUPER_ADMIN (All scope 不预解析), 跳过等级过滤
            if (ceiling != null && level > ceiling) {
                return false;
            }
        }

        return true;
    }
}
