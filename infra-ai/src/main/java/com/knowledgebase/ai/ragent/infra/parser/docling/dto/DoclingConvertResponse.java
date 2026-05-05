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

package com.knowledgebase.ai.ragent.infra.parser.docling.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Docling {@code /v1alpha/convert/file} 响应的镜像（仅业务关心的子集）。
 *
 * <p>用 {@link JsonIgnoreProperties} 容忍 Docling 版本漂移；首次接入时按 Task 5.6 的真实 curl 输出
 * 校准字段名（必要时配 {@code @JsonProperty} 把 snake_case 映射到 camelCase 字段）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingConvertResponse(Document document, Map<String, Object> metadata) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(String text, List<DoclingBlock> blocks, List<DoclingTable> tables) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingBlock(
            String id,
            /** "heading" / "paragraph" / "list" / "table" / etc. */
            String type,
            Integer pageNo,
            Bbox bbox,
            Integer readingOrder,
            Double confidence,
            /** "NATIVE_TEXT" / "OCR" / "MIXED" / null. */
            String textLayerType,
            Integer level,
            String text,
            List<String> sectionPath) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingTable(
            String id,
            Integer pageNo,
            Bbox bbox,
            List<List<String>> rows,
            List<String> sectionPath,
            Integer readingOrder,
            Double confidence) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bbox(double x, double y, double width, double height) {}
}
