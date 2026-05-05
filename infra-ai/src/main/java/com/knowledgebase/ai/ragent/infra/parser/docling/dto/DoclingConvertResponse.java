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
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Docling {@code POST /v1alpha/convert/file} 响应的镜像（仅业务关心的子集）。
 *
 * <p>v0.5.1 实测的真实 schema —— 与 plan 草稿差异很大：
 * <ul>
 *   <li>顶层是 {@code ConvertDocumentResponse}，含 {@code document / status / errors / processing_time / timings}，
 *       <strong>不</strong>是 {@code document + metadata}。</li>
 *   <li>{@code document} 是 {@code DocumentResponse}，结构化内容在 {@code json_content}（{@link DoclingDocument}）；
 *       请求时必须显式 {@code to_formats=json} 才会被填充。</li>
 *   <li>{@code DoclingDocument.texts} 才是 plan 假设的 "blocks"；按 {@code prov[0]} 拿到 page / bbox。</li>
 *   <li>bbox 形如 {@code {l,t,r,b,coord_origin}}（坐标系可能 TOPLEFT 或 BOTTOMLEFT），
 *       {@link com.knowledgebase.ai.ragent.core.parser.DoclingResponseAdapter} 负责转 {@code {x,y,width,height}}。</li>
 *   <li>v0.5.1 不暴露 {@code confidence}、{@code reading_order}、{@code text_layer_type} —— Adapter 用 list 索引代位 readingOrder。</li>
 * </ul>
 *
 * <p>{@link JsonIgnoreProperties} 容忍后续版本飘移；下游 {@code DoclingResponseAdapter} 只读
 * 必要字段，对未读字段升级零影响。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingConvertResponse(
        DocumentResponse document,
        String status,
        List<ErrorItem> errors,
        @JsonProperty("processing_time") Double processingTime,
        Map<String, Object> timings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentResponse(
            String filename,
            @JsonProperty("md_content") String mdContent,
            @JsonProperty("json_content") DoclingDocument jsonContent,
            @JsonProperty("html_content") String htmlContent,
            @JsonProperty("text_content") String textContent,
            @JsonProperty("doctags_content") String doctagsContent) {}

    /** 嵌套的 DoclingDocument，结构化版面在这里。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingDocument(
            @JsonProperty("schema_name") String schemaName,
            String version,
            String name,
            List<TextItem> texts,
            List<TableItem> tables,
            /** key=pageNo (字符串)；value 含 size + image。 */
            Map<String, PageItem> pages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TextItem(
            @JsonProperty("self_ref") String selfRef,
            /** 父引用（用于推断 sectionPath，PR 6 再实现）。 */
            Ref parent,
            /** "text" / "section_header" / "list_item" / "page_footer" / "footnote" / etc. */
            String label,
            /** 仅 section_header 出现。 */
            Integer level,
            /** prov 是 List —— 同一逻辑块可能横跨多页；Adapter 只取第一项。 */
            List<Provenance> prov,
            /** 清洗后的文本。 */
            String text,
            /** OCR 原始文本，可能与 text 不同。 */
            String orig) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableItem(
            @JsonProperty("self_ref") String selfRef,
            Ref parent,
            String label,
            List<Provenance> prov,
            TableData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableData(
            @JsonProperty("num_rows") Integer numRows,
            @JsonProperty("num_cols") Integer numCols,
            @JsonProperty("table_cells") List<TableCell> tableCells) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableCell(
            String text,
            @JsonProperty("row_span") Integer rowSpan,
            @JsonProperty("col_span") Integer colSpan,
            @JsonProperty("start_row_offset_idx") Integer startRow,
            @JsonProperty("end_row_offset_idx") Integer endRow,
            @JsonProperty("start_col_offset_idx") Integer startCol,
            @JsonProperty("end_col_offset_idx") Integer endCol,
            @JsonProperty("column_header") Boolean columnHeader,
            @JsonProperty("row_header") Boolean rowHeader) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Provenance(
            @JsonProperty("page_no") Integer pageNo,
            Bbox bbox,
            /** [start, end] 字符偏移。 */
            List<Integer> charspan) {}

    /** Docling 用 {l,t,r,b}（左/顶/右/底，注意 coord_origin 可能 TOPLEFT 或 BOTTOMLEFT）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bbox(double l, double t, double r, double b, @JsonProperty("coord_origin") String coordOrigin) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageItem(Size size, ImageRef image) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Size(double width, double height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageRef(String mimetype, Integer dpi, Size size) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(@JsonProperty("$ref") String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorItem(String code, String message) {}
}
