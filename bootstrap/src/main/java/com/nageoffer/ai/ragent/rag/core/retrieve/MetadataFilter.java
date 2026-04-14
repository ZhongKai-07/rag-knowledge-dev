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

/**
 * 向量检索的 metadata 过滤条件（类型化版本）。
 *
 * <p>用于在 {@code RetrieveRequest.metadataFilters} 里表达"metadata.&lt;field&gt; &lt;op&gt; &lt;value&gt;"
 * 的原子条件。由下游 {@code RetrieverService} 的具体实现（OpenSearch/Milvus/pgvector）各自把它翻译
 * 成对应存储的过滤语法。
 *
 * <p>示例：
 * <pre>{@code
 * // "security_level <= 2"
 * new MetadataFilter("security_level", FilterOp.LTE, 2)
 *
 * // "doc_id == 'abc123'"
 * new MetadataFilter("doc_id", FilterOp.EQ, "abc123")
 *
 * // "source_type in ('file', 'url')"
 * new MetadataFilter("source_type", FilterOp.IN, List.of("file", "url"))
 * }</pre>
 *
 * <p><strong>为何不用字符串 key 后缀约定</strong>（例如 {@code security_level_lte}）：若将来 metadata
 * 字段名本身以 {@code _lte} 结尾（例如 {@code estimated_lte}），会被误识别为 range 操作。使用类型化
 * enum 消除这种歧义。
 */
public record MetadataFilter(String field, FilterOp op, Object value) {

    /**
     * 过滤操作类型。
     *
     * <ul>
     *     <li>{@link #EQ}：精确匹配（term）</li>
     *     <li>{@link #LTE}/{@link #GTE}/{@link #LT}/{@link #GT}：范围比较（range）</li>
     *     <li>{@link #IN}：多值匹配（terms），{@link #value} 需为 {@link java.util.Collection}</li>
     * </ul>
     */
    public enum FilterOp {
        EQ,
        LTE,
        /** 字段值 &lt;= value，或字段不存在（兼容未设置 security_level 的旧数据） */
        LTE_OR_MISSING,
        GTE,
        LT,
        GT,
        IN
    }
}
