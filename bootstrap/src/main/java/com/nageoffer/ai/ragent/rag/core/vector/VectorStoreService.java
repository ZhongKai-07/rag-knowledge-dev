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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;

import java.util.List;
import java.util.Map;

/**
 * 向量存储服务接口
 */
public interface VectorStoreService {

    /**
     * 批量建立文档的向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param kbId           所属知识库 ID（写入 metadata.kb_id；null 时写入空字符串，AuthzPostProcessor 会 fail-closed drop）
     * @param securityLevel  文档安全等级 0-3（写入 metadata.security_level；null 时兜底为 0）
     * @param chunks         文档切片列表，须包含已计算好的 embedding
     */
    void indexDocumentChunks(String collectionName, String docId,
                             String kbId, Integer securityLevel,
                             List<VectorChunk> chunks);

    /**
     * 更新单个 chunk 的向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param kbId           所属知识库 ID（写入 metadata.kb_id）
     * @param securityLevel  文档安全等级 0-3（写入 metadata.security_level；null 时兜底为 0）
     * @param chunk          待更新的文档切片，须包含最新的 embedding
     */
    void updateChunk(String collectionName, String docId,
                     String kbId, Integer securityLevel,
                     VectorChunk chunk);

    /**
     * 删除文档的所有向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     */
    void deleteDocumentVectors(String collectionName, String docId);

    /**
     * 删除指定的单个 chunk 向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param chunkId        chunk 的唯一标识
     */
    void deleteChunkById(String collectionName, String chunkId);

    /**
     * 批量删除指定 chunk 的向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param chunkIds       chunk 唯一标识列表
     */
    void deleteChunksByIds(String collectionName, List<String> chunkIds);

    /**
     * 批量更新指定文档的所有 chunk 在向量库里的 metadata 字段（不动 vector）。
     *
     * <p>用于 {@code security_level} 等 document 级字段变更后的 chunk metadata 刷新。
     * OpenSearch 实现走 {@code POST {collection}/_update_by_query} 带 Painless script；
     * Milvus / pgvector 实现当前抛 {@code UnsupportedOperationException}（本 PR 外的 follow-up 补齐）。
     *
     * @param collectionName 目标向量集合/索引名
     * @param docId          文档 ID（用于 {@code metadata.doc_id} 过滤）
     * @param fields         要更新的字段名 → 新值
     */
    void updateChunksMetadata(String collectionName, String docId, Map<String, Object> fields);
}
