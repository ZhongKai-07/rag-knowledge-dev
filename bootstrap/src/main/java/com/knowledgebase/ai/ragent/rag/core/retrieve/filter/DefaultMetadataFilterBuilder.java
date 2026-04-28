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

package com.knowledgebase.ai.ragent.rag.core.retrieve.filter;

import com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 默认实现：为非空 kbId 永远输出 kb_id (FilterOp.IN [kbId]) 和
 * security_level (FilterOp.LTE_OR_MISSING level) 两条 filter。
 *
 * <p>PR5 起这两条 filter 是 OpenSearch retrieval 的 query shape invariant：
 * <ul>
 *   <li>{@code kb_id IN [kbId]}：singleton list 在 OpenSearch 端渲染为
 *       {@code terms(metadata.kb_id, [kbId])}，给检索加 KB 软隔离纵深，
 *       与 {@code collectionName} 物理隔离形成双重防御</li>
 *   <li>{@code security_level LTE_OR_MISSING level}：缺 entry / null map 时
 *       fallback 到 {@link Integer#MAX_VALUE}（no-op range），让"OpenSearch DSL 始终
 *       同时含 metadata.kb_id 与 metadata.security_level filter"成为可静态断言的契约。
 *       这是 query shape，**不是**授权决策——授权由 {@code AccessScope} 与
 *       {@code AuthzPostProcessor} 兜底</li>
 * </ul>
 *
 * <p>{@code kbId == null} 返空，由 {@code OpenSearchRetrieverService.enforceFilterContract}
 * 接住 fail-fast，避免 builder 层重复抛异常造成栈耦合。
 */
@Component
public class DefaultMetadataFilterBuilder implements MetadataFilterBuilder {

    @Override
    public List<MetadataFilter> build(SearchContext ctx, String kbId) {
        if (kbId == null) {
            return Collections.emptyList();
        }
        int level = resolveLevel(ctx, kbId);
        return List.of(
                new MetadataFilter(
                        VectorMetadataFields.KB_ID,
                        MetadataFilter.FilterOp.IN,
                        List.of(kbId)),
                new MetadataFilter(
                        VectorMetadataFields.SECURITY_LEVEL,
                        MetadataFilter.FilterOp.LTE_OR_MISSING,
                        level));
    }

    /**
     * 解析当前 KB 的安全等级天花板。
     * <p>{@code ctx.kbSecurityLevels} 为 null 或缺该 kbId 的 entry 时返回
     * {@link Integer#MAX_VALUE}（no-op range，等价不过滤），让 query shape 契约绝对化。
     */
    private static int resolveLevel(SearchContext ctx, String kbId) {
        Map<String, Integer> map = ctx.getKbSecurityLevels();
        if (map == null) {
            return Integer.MAX_VALUE;
        }
        Integer v = map.get(kbId);
        return v != null ? v : Integer.MAX_VALUE;
    }
}
