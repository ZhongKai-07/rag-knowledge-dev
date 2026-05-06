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

package com.knowledgebase.ai.ragent.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档级源卡片内的单个 chunk 片段。PR 6 起加 6 个 nullable layout 字段。
 *
 * <p><b>v4 review P2 #5</b>：使用 {@code NON_EMPTY}（不是 NON_NULL）—— 既跳 null，又跳空 List/String，
 * 防止 BASIC chunk 因为 `headingPath = []` 这种边缘 wire 出 `"headingPath":[]` 假阳性。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SourceChunk {
    private String chunkId;
    private int chunkIndex;
    private String preview;
    private float score;

    /** PR 6 chunk-level evidence（与 Collateral PR tier-A 契约对齐） */
    private Integer pageNumber;
    private Integer pageStart;
    private Integer pageEnd;
    private List<String> headingPath;
    private String blockType;
    private List<String> sourceBlockIds;
}
