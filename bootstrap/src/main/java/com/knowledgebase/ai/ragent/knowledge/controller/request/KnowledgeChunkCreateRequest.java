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

package com.knowledgebase.ai.ragent.knowledge.controller.request;

import lombok.Data;

/**
 * 知识库 Chunk 创建请求。PR 6 起加 9 个 nullable layout 字段：
 * <ul>
 *   <li>manual chunk creation API（KnowledgeChunkController）调用时不会填（手工 chunk 没 layout）</li>
 *   <li>ingestion persist 路径由 KnowledgeChunkLayoutMapper.copyToCreateRequest 自动填</li>
 *   <li>headingPath / sourceBlockIds 与 KnowledgeChunkDO 同形态：JSON String</li>
 * </ul>
 */
@Data
public class KnowledgeChunkCreateRequest {

    /** 现有字段不动 */
    private String content;
    private Integer index;
    private String chunkId;

    /** PR 6 layout 字段 */
    private Integer pageNumber;
    private Integer pageStart;
    private Integer pageEnd;
    private String headingPath;        // JSON 数组字符串
    private String blockType;
    private String sourceBlockIds;     // JSON 数组字符串
    private String bboxRefs;
    private String textLayerType;
    private Double layoutConfidence;
}
