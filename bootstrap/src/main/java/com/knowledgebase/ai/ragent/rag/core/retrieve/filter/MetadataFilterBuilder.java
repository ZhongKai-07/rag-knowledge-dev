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

import java.util.List;

/**
 * 元数据过滤条件构建器。
 * 将 per-KB security_level 过滤逻辑从 static 方法提取为可注入 bean，
 * 方便测试和 AuthzPostProcessor 的白盒验证。
 */
public interface MetadataFilterBuilder {

    /**
     * 根据检索上下文和目标 KB ID 构建元数据过滤条件。
     *
     * @param ctx  当前检索上下文（包含 kbSecurityLevels 等授权信息）
     * @param kbId 目标知识库 ID
     * @return 过滤条件列表，空列表表示不过滤
     */
    List<MetadataFilter> build(SearchContext ctx, String kbId);
}
