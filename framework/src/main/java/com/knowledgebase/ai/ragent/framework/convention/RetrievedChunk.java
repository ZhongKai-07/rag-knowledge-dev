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

package com.knowledgebase.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * RAG 检索命中结果
 * <p>
 * 表示一次向量检索或相关性搜索命中的单条记录
 * 包含原始文档片段 主键以及相关性得分
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode(of = "id")
public class RetrievedChunk {

    /**
     * 命中记录的唯一标识
     * 比如向量库中的 primary key 或文档 id
     */
    private String id;

    /**
     * 命中的文本内容
     * 一般是被切分后的文档片段或段落
     */
    private String text;

    /**
     * 命中得分
     * 数值越大表示与查询的相关性越高
     */
    private Float score;

    /**
     * 所属知识库 ID（从 OpenSearch metadata.kb_id 回填）。
     * 授权后置处理器 {@code AuthzPostProcessor} 使用，null 视为 fail-closed。
     * 非 OpenSearch 后端（Milvus/Pg）当前不回填，由下游 fail-close 兜底。
     */
    private String kbId;

    /**
     * 文档安全等级（从 OpenSearch metadata.security_level 回填）。
     * 授权后置处理器使用；null 表示不做等级过滤（系统态/老数据）。
     */
    private Integer securityLevel;

    /**
     * 文档 ID（从向量库 metadata.doc_id 回填）。
     * 用于"回答来源"功能按文档聚合 chunks。
     * OpenSearch 后端回填；其他后端（Milvus/Pg）当前不回填（与 kbId/securityLevel 同例），空值兼容旧数据。
     */
    private String docId;

    /**
     * 分块在所属文档内的顺序索引（从 metadata.chunk_index 回填）。
     * 用于 source 卡片内的阅读顺序展示；非顺序索引（如跨文档的全局序号）不使用此字段。
     * OpenSearch 后端回填；其他后端当前不回填，空值兼容旧数据。
     */
    private Integer chunkIndex;

    // PR 6 layout 字段（chunk-level evidence，仅 OpenSearch backend 回填；非 OS backend null）
    private Integer pageNumber;
    private Integer pageStart;
    private Integer pageEnd;
    private java.util.List<String> headingPath;
    private String blockType;
    private java.util.List<String> sourceBlockIds;
}
