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

package com.knowledgebase.ai.ragent.rag.core.vector;

/**
 * OpenSearch metadata 字段的规范化常量。单一真相源：写路径、读路径、
 * 过滤器必须引用同一常量，避免拼写漂移悄悄破坏 RBAC。
 *
 * <p>Mapping DDL 维护在 {@code OpenSearchVectorStoreAdmin#buildMappingJson} 的 JSON 文本块里
 * (text block 不能直接引用常量, 改字段名时需同步两处)。
 */
public final class VectorMetadataFields {

    /** 所属知识库 ID. AuthzPostProcessor 以此做白名单校验. */
    public static final String KB_ID = "kb_id";

    /** 文档安全等级 (0=PUBLIC … 3=RESTRICTED). AuthzPostProcessor 以此做天花板校验. */
    public static final String SECURITY_LEVEL = "security_level";

    /** 文档 ID. 写路径: {@code OpenSearchVectorStoreService}/{@code IndexerNode}. 读路径: {@code OpenSearchRetrieverService} 回填到 {@link com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk#getDocId()}. */
    public static final String DOC_ID = "doc_id";

    /** 分块在文档内的顺序索引. 写路径同 DOC_ID. 读路径回填到 {@link com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk#getChunkIndex()}. */
    public static final String CHUNK_INDEX = "chunk_index";

    /** 显示用页码提示；PAGE_START/PAGE_END 才是规范化证据范围. */
    public static final String PAGE_NUMBER = "page_number";

    /** 该 chunk 起始的源 1-based 页号（layout 证据）. */
    public static final String PAGE_START = "page_start";

    /** 该 chunk 末尾的源 1-based 页号（layout 证据）. */
    public static final String PAGE_END = "page_end";

    /** 祖先标题路径（JSON 数组），用于结构化引用展示. */
    public static final String HEADING_PATH = "heading_path";

    /** 块类型：TITLE|PARAGRAPH|TABLE|HEADER|FOOTER|LIST|CAPTION|OTHER. */
    public static final String BLOCK_TYPE = "block_type";

    /** 来源 layout block id 列表（JSON 数组）. */
    public static final String SOURCE_BLOCK_IDS = "source_block_ids";

    /** 来源 bbox 引用（JSON 数组），供前端高亮定位使用. */
    public static final String BBOX_REFS = "bbox_refs";

    /** 文本层类型：NATIVE_TEXT|OCR|MIXED|UNKNOWN（解析器输出）. */
    public static final String TEXT_LAYER_TYPE = "text_layer_type";

    /** 解析器输出的 layout 置信度（enhanced 路径专用）. */
    public static final String LAYOUT_CONFIDENCE = "layout_confidence";

    private VectorMetadataFields() {
    }
}
