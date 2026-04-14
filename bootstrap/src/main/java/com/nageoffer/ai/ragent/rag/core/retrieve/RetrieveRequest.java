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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 向量检索请求参数：
 * - 支持基础 query + topK
 * - 支持指定 Milvus collectionName
 * - 支持简单的 metadata 等值过滤（扩展用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrieveRequest {

    /**
     * 用户自然语言问题 / 查询语句
     */
    private String query;

    /**
     * 返回 TopK，默认 5
     */
    @Builder.Default
    private int topK = 5;

    /**
     * 目标向量集合名称：
     * - 为空时走默认 Collection
     * - 非空时按指定 Collection 检索
     */
    private String collectionName;

    /**
     * 类型化的元数据过滤条件列表。
     * 每个元素表示 "metadata.&lt;field&gt; &lt;op&gt; &lt;value&gt;" 的原子条件，AND 连接。
     * <p>
     * 由下游 RetrieverService 的具体实现（OpenSearch/Milvus/pgvector）负责翻译。
     * null 或空列表表示不加过滤。
     */
    private List<MetadataFilter> metadataFilters;
}

