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

package com.knowledgebase.ai.ragent.ingestion.domain.context;

import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorSpaceId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 文档摄取上下文实体类
 * 在文档摄取管道执行过程中，承载和传递所有中间数据和状态信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionContext {

    /**
     * 摄取任务的唯一标识符
     */
    private String taskId;

    /**
     * 执行本次摄取的管道ID
     */
    private String pipelineId;

    /**
     * 文档源信息
     */
    private DocumentSource source;

    /**
     * 文档的原始字节数据
     */
    private byte[] rawBytes;

    /**
     * 文档的MIME类型
     */
    private String mimeType;

    /**
     * 解析后的原始文本内容
     */
    private String rawText;

    /**
     * 结构化解析后的文档对象
     */
    private StructuredDocument document;

    /**
     * 文档切分后的文本块列表
     */
    private List<VectorChunk> chunks;

    /**
     * 经过增强处理后的文本内容
     */
    private String enhancedText;

    /**
     * 从文档中提取的关键词列表
     */
    private List<String> keywords;

    /**
     * 基于文档内容生成的问题列表
     */
    private List<String> questions;

    /**
     * 摄取过程中的元数据信息
     */
    private Map<String, Object> metadata;

    /**
     * 本次 ingestion 对应的知识库 ID。
     * 独立字段，用于 IndexerNode 透传给 VectorStoreService.indexDocumentChunks 写入 metadata.kb_id，
     * 不依赖 metadata Map，避免 round-trip 丢失。
     */
    private String kbId;

    /**
     * 文档安全等级（0=PUBLIC, 1=INTERNAL, 2=CONFIDENTIAL, 3=RESTRICTED）。
     * 独立字段，透传给 VectorStoreService 写入 metadata.security_level。null 时下游兜底为 0。
     */
    private Integer securityLevel;

    /**
     * 向量空间ID，指定向量数据写入的目标集合
     * 如果不指定，则使用默认的向量空间
     */
    private VectorSpaceId vectorSpaceId;

    /**
     * 当前摄取任务的状态
     */
    private IngestionStatus status;

    /**
     * 管道执行过程中的节点日志列表
     */
    private List<NodeLog> logs;

    /**
     * 摄取过程中发生的异常信息
     */
    private Throwable error;

    /**
     * 是否跳过 IndexerNode 的向量写入
     * 为 true 时，IndexerNode 仅做校验不执行写入，由调用方统一在事务中完成向量持久化
     */
    @Builder.Default
    private boolean skipIndexerWrite = false;

    /**
     * 解析模式语义值（{@code basic} / {@code enhanced}），由 upload 期 ParseModePolicy 决议得到。
     * 仅作传递与审计；ParserNode 实际从 NodeConfig.settings.parseMode 读取（见 PR 1）。
     * 此处的字段为后续 IndexerNode / 监控埋点保留 hand-off 通道。
     */
    private String parseMode;
}
