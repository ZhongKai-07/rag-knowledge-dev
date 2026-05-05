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

package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.DoclingDocument;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.Provenance;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.TableCell;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.TableItem;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.TextItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adapter：把 Docling {@link DoclingConvertResponse}（v0.5.1 schema）翻译成 engine-neutral
 * {@link ParseResult}。
 *
 * <p>关键映射：
 * <ul>
 *   <li>{@code text} = {@code document.text_content}（顶层全文）</li>
 *   <li>blocks ← {@code document.json_content.texts}，readingOrder 用 list 索引代位</li>
 *   <li>tables ← {@code document.json_content.tables}，rows 从 {@code data.table_cells} 用
 *       {@code start_row_offset_idx + start_col_offset_idx} 二维重组</li>
 *   <li>bbox：Docling {@code {l,t,r,b,coord_origin}} → engine-neutral {@code {x=l, y=t, width=r-l, height=|b-t|}}
 *       —— BOTTOMLEFT/TOPLEFT 都做绝对值，下游不感知坐标系</li>
 *   <li>metadata：从 {@code status / processing_time / errors / filename / schema_name / version} 合成</li>
 * </ul>
 *
 * <p>v0.5.1 不暴露 {@code confidence / reading_order / text_layer_type}：
 * <ul>
 *   <li>{@code confidence} → null</li>
 *   <li>{@code readingOrder} → list 索引</li>
 *   <li>{@code textLayerType} → null（PR 5.5+ 由 OCR detector 单独推断，暂留空）</li>
 * </ul>
 *
 * <p>{@code sectionPath} v1 留空 —— Docling 用 {@code parent.$ref} 链表表达层级，需要遍历
 * 整个 {@code texts} 列表才能解析；推到 PR 6 结构化 chunker 实现时一并做。
 *
 * <p>未来若引入第二种结构化引擎，新建对应 Adapter 即可，不必动 {@link DocumentParser} 接口。
 */
@Component
public class DoclingResponseAdapter {

    public ParseResult toParseResult(DoclingConvertResponse resp) {
        if (resp == null || resp.document() == null) {
            return new ParseResult("", buildMetadata(resp), List.of(), List.of());
        }
        DoclingConvertResponse.DocumentResponse docResp = resp.document();
        String text = docResp.textContent() == null ? "" : docResp.textContent();
        DoclingDocument doc = docResp.jsonContent();
        if (doc == null) {
            // 没请求 json 输出 → 只能拿到 text_content；blocks/tables/pages 全空。
            return new ParseResult(text, buildMetadata(resp), List.of(), List.of());
        }

        List<TextItem> rawTexts = doc.texts() == null ? List.of() : doc.texts();
        List<TableItem> rawTables = doc.tables() == null ? List.of() : doc.tables();

        List<LayoutBlock> blocks = mapTextsToBlocks(rawTexts);
        List<LayoutTable> tables = mapTables(rawTables, rawTexts.size());
        // doc.pages() 含 page size / image，PR 6 page preview 再读；当前只按 blocks 分组
        List<DocumentPageText> pages = mapPages(blocks);

        return new ParseResult(text, buildMetadata(resp), pages, tables);
    }

    private List<LayoutBlock> mapTextsToBlocks(List<TextItem> texts) {
        List<LayoutBlock> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            TextItem t = texts.get(i);
            Provenance p = firstProv(t.prov());
            int pageNo = p == null || p.pageNo() == null ? 0 : p.pageNo();
            LayoutBlock.Bbox bbox = p == null ? null : convertBbox(p.bbox());
            String text = t.text() != null ? t.text() : (t.orig() != null ? t.orig() : "");
            result.add(new LayoutBlock(
                    t.selfRef(),
                    mapTextLabel(t.label()),
                    pageNo,
                    bbox,
                    text,
                    i, // readingOrder = list 索引
                    null, // confidence
                    t.level(),
                    List.of() // sectionPath 推后到 PR 6
                    ));
        }
        return result;
    }

    private List<LayoutTable> mapTables(List<TableItem> tables, int textsBaseOrder) {
        List<LayoutTable> result = new ArrayList<>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            TableItem tb = tables.get(i);
            Provenance p = firstProv(tb.prov());
            int pageNo = p == null || p.pageNo() == null ? 0 : p.pageNo();
            LayoutBlock.Bbox bbox = p == null ? null : convertBbox(p.bbox());
            List<List<String>> rows = reconstructRows(tb.data());
            result.add(new LayoutTable(
                    tb.selfRef(),
                    pageNo,
                    bbox,
                    rows,
                    List.of(), // headingPath 推后到 PR 6
                    textsBaseOrder + i, // readingOrder：表格在 texts 之后
                    null // confidence
                    ));
        }
        return result;
    }

    /**
     * 用 {@code start_row_offset_idx / start_col_offset_idx} 把 cells 重组为二维 List。
     * 不处理 row_span / col_span（v1 简化：合并单元格按起始格出现一次，其余位置保持空字符串）。
     */
    private List<List<String>> reconstructRows(DoclingConvertResponse.TableData data) {
        if (data == null || data.tableCells() == null || data.tableCells().isEmpty()) {
            return List.of();
        }
        int numRows = data.numRows() != null ? data.numRows() : maxIndexPlusOne(data.tableCells(), true);
        int numCols = data.numCols() != null ? data.numCols() : maxIndexPlusOne(data.tableCells(), false);
        if (numRows <= 0 || numCols <= 0) return List.of();

        String[][] grid = new String[numRows][numCols];
        for (TableCell c : data.tableCells()) {
            Integer r = c.startRow();
            Integer col = c.startCol();
            if (r == null || col == null || r < 0 || col < 0 || r >= numRows || col >= numCols) continue;
            String text = c.text() == null ? "" : c.text();
            // 已被前一格覆盖（理论上 Docling 同一格只出一次，这里做防御）
            if (grid[r][col] == null || grid[r][col].isEmpty()) {
                grid[r][col] = text;
            }
        }
        List<List<String>> rows = new ArrayList<>(numRows);
        for (int r = 0; r < numRows; r++) {
            List<String> row = new ArrayList<>(numCols);
            for (int c = 0; c < numCols; c++) {
                row.add(grid[r][c] == null ? "" : grid[r][c]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static int maxIndexPlusOne(List<TableCell> cells, boolean rowAxis) {
        int max = 0;
        for (TableCell c : cells) {
            Integer end = rowAxis ? c.endRow() : c.endCol();
            if (end != null && end > max) max = end;
        }
        return max;
    }

    /** Docling bbox {l,t,r,b} → engine-neutral {x,y,width,height}（绝对值消除坐标系差异）。 */
    private LayoutBlock.Bbox convertBbox(DoclingConvertResponse.Bbox b) {
        if (b == null) return null;
        double width = Math.abs(b.r() - b.l());
        double height = Math.abs(b.t() - b.b());
        return new LayoutBlock.Bbox(b.l(), b.t(), width, height);
    }

    private static Provenance firstProv(List<Provenance> prov) {
        return prov == null || prov.isEmpty() ? null : prov.get(0);
    }

    private List<DocumentPageText> mapPages(List<LayoutBlock> blocks) {
        Map<Integer, List<LayoutBlock>> blocksByPage = blocks.stream()
                .collect(Collectors.groupingBy(LayoutBlock::pageNo, LinkedHashMap::new, Collectors.toList()));
        return blocksByPage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<LayoutBlock> sorted = e.getValue().stream()
                            .sorted((a, b) -> Integer.compare(
                                    a.readingOrder() == null ? Integer.MAX_VALUE : a.readingOrder(),
                                    b.readingOrder() == null ? Integer.MAX_VALUE : b.readingOrder()))
                            .toList();
                    String pageText = sorted.stream()
                            .map(LayoutBlock::text)
                            .filter(s -> s != null && !s.isBlank())
                            .collect(Collectors.joining("\n"));
                    return new DocumentPageText(
                            null,
                            e.getKey(),
                            pageText,
                            null, // textLayerType v0.5.1 不暴露
                            null, // confidence v0.5.1 不暴露
                            sorted);
                })
                .toList();
    }

    private static Map<String, Object> buildMetadata(DoclingConvertResponse resp) {
        Map<String, Object> md = new HashMap<>();
        if (resp == null) return md;
        if (resp.status() != null) md.put("docling_status", resp.status());
        if (resp.processingTime() != null) md.put("docling_processing_time", resp.processingTime());
        if (resp.errors() != null && !resp.errors().isEmpty()) md.put("docling_errors", resp.errors());
        if (resp.document() != null) {
            DoclingConvertResponse.DocumentResponse d = resp.document();
            if (d.filename() != null) md.put("source_filename", d.filename());
            if (d.jsonContent() != null) {
                if (d.jsonContent().schemaName() != null) md.put("docling_schema", d.jsonContent().schemaName());
                if (d.jsonContent().version() != null) md.put("docling_schema_version", d.jsonContent().version());
            }
        }
        return md;
    }

    private BlockType mapTextLabel(String label) {
        if (label == null) return BlockType.OTHER;
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "section_header", "section-header", "heading", "title" -> BlockType.TITLE;
            case "text", "paragraph" -> BlockType.PARAGRAPH;
            case "list_item", "list", "list-item" -> BlockType.LIST;
            case "page_footer", "page-footer", "footer" -> BlockType.FOOTER;
            case "page_header", "page-header", "header" -> BlockType.HEADER;
            case "caption" -> BlockType.CAPTION;
            case "table" -> BlockType.TABLE;
            default -> BlockType.OTHER;
        };
    }
}
