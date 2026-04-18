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

package com.nageoffer.ai.ragent.rag.core.retrieve.filter;

import com.nageoffer.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.vector.VectorMetadataFields;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 默认实现：按 kb 查表 security_level，生成 LTE_OR_MISSING 过滤条件。
 */
@Component
public class DefaultMetadataFilterBuilder implements MetadataFilterBuilder {

    @Override
    public List<MetadataFilter> build(SearchContext ctx, String kbId) {
        if (kbId == null || ctx.getKbSecurityLevels() == null) {
            return Collections.emptyList();
        }
        Integer level = ctx.getKbSecurityLevels().get(kbId);
        if (level == null) {
            return Collections.emptyList();
        }
        return List.of(new MetadataFilter(
                VectorMetadataFields.SECURITY_LEVEL,
                MetadataFilter.FilterOp.LTE_OR_MISSING,
                level));
    }
}
