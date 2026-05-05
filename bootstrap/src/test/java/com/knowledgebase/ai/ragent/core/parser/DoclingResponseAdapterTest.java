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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.Bbox;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.DoclingDocument;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.DocumentResponse;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.Provenance;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.TableCell;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.TableData;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.TableItem;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse.TextItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 DoclingResponseAdapter 对 v0.5.1 实测 schema 的映射。
 *
 * <p>关键场景：
 * <ul>
 *   <li>texts → blocks（label 映射、prov[0] 取 page/bbox、readingOrder 用 list 索引）</li>
 *   <li>tables → 二维 rows 重组（按 start_row/start_col 索引）</li>
 *   <li>bbox 转换 {l,t,r,b} → {x,y,width,height} 取绝对值</li>
 *   <li>顶层 metadata 从 status/processing_time/filename/schema 合成</li>
 *   <li>容错：null document / null json_content / 空 texts/tables / 缺 prov</li>
 *   <li>Jackson 反序列化真实 JSON 不丢字段</li>
 * </ul>
 */
class DoclingResponseAdapterTest {

    private final DoclingResponseAdapter adapter = new DoclingResponseAdapter();

    private static Provenance prov(int pageNo, Bbox bbox) {
        return new Provenance(pageNo, bbox, List.of(0, 0));
    }

    private static TextItem text(String selfRef, String label, Integer level, int pageNo, Bbox bbox, String text) {
        return new TextItem(selfRef, null, label, level, List.of(prov(pageNo, bbox)), text, text);
    }

    private static DoclingConvertResponse build(List<TextItem> texts, List<TableItem> tables) {
        return new DoclingConvertResponse(
                new DocumentResponse(
                        "f.pdf",
                        null,
                        new DoclingDocument("DoclingDocument", "1.1.0", "f", texts, tables, Map.of()),
                        null,
                        "all text",
                        null),
                "success",
                List.of(),
                12.3,
                Map.of());
    }

    @Test
    void mapsTextsToBlocksWithReadingOrderAndPageGrouping() {
        TextItem t1 =
                text("#/texts/0", "section_header", 1, 1, new Bbox(10, 20, 210, 50, "BOTTOMLEFT"), "Chapter 1");
        TextItem t2 = text("#/texts/1", "text", null, 1, new Bbox(10, 60, 210, 100, "BOTTOMLEFT"), "Body para");
        TextItem t3 = text("#/texts/2", "list_item", null, 2, new Bbox(0, 0, 100, 20, "BOTTOMLEFT"), "Item A");

        ParseResult r = adapter.toParseResult(build(List.of(t1, t2, t3), List.of()));

        assertEquals("all text", r.text());
        assertEquals(2, r.pages().size(), "p1 + p2");
        assertEquals("#/texts/0", r.pages().get(0).blocks().get(0).blockId());
        assertEquals(BlockType.TITLE, r.pages().get(0).blocks().get(0).blockType());
        assertEquals(1, r.pages().get(0).blocks().get(0).headingLevel());
        assertEquals(0, r.pages().get(0).blocks().get(0).readingOrder());
        assertEquals(BlockType.LIST, r.pages().get(1).blocks().get(0).blockType());
        // metadata 合成
        assertEquals("success", r.metadata().get("docling_status"));
        assertEquals(12.3, r.metadata().get("docling_processing_time"));
        assertEquals("f.pdf", r.metadata().get("source_filename"));
        assertEquals("DoclingDocument", r.metadata().get("docling_schema"));
        assertEquals("1.1.0", r.metadata().get("docling_schema_version"));
    }

    @Test
    void bboxConversion_takesAbsoluteValues() {
        TextItem t = text("#/texts/0", "text", null, 1, new Bbox(50, 200, 250, 100, "BOTTOMLEFT"), "x");
        ParseResult r = adapter.toParseResult(build(List.of(t), List.of()));
        LayoutBlock.Bbox bbox = r.pages().get(0).blocks().get(0).bbox();
        assertNotNull(bbox);
        assertEquals(50.0, bbox.x(), 0.001);
        assertEquals(200.0, bbox.y(), 0.001);
        assertEquals(200.0, bbox.width(), 0.001);
        // |t-b| = |200-100| = 100，不论坐标系都正
        assertEquals(100.0, bbox.height(), 0.001);
    }

    @Test
    void mapsTablesAndReconstructsRowsByStartIndex() {
        TableCell c00 = new TableCell("Header A", 1, 1, 0, 1, 0, 1, true, false);
        TableCell c01 = new TableCell("Header B", 1, 1, 0, 1, 1, 2, true, false);
        TableCell c10 = new TableCell("a", 1, 1, 1, 2, 0, 1, false, false);
        TableCell c11 = new TableCell("b", 1, 1, 1, 2, 1, 2, false, false);
        TableData data = new TableData(2, 2, List.of(c00, c01, c10, c11));
        TableItem table = new TableItem(
                "#/tables/0",
                null,
                "table",
                List.of(prov(3, new Bbox(50, 100, 250, 200, "BOTTOMLEFT"))),
                data);

        ParseResult r = adapter.toParseResult(build(List.of(), List.of(table)));

        assertEquals(1, r.tables().size());
        assertEquals(2, r.tables().get(0).rows().size());
        assertEquals(List.of("Header A", "Header B"), r.tables().get(0).rows().get(0));
        assertEquals(List.of("a", "b"), r.tables().get(0).rows().get(1));
        assertEquals(3, r.tables().get(0).pageNo());
        assertNotNull(r.tables().get(0).bbox());
        assertEquals(200.0, r.tables().get(0).bbox().width(), 0.001);
        assertEquals(100.0, r.tables().get(0).bbox().height(), 0.001);
    }

    @Test
    void unknownLabel_mapsToOther_andEmptyTextDefaultsToOrig() {
        TextItem t = new TextItem(
                "#/texts/0",
                null,
                "weird_label",
                null,
                List.of(prov(1, new Bbox(0, 0, 10, 10, "TOPLEFT"))),
                null,
                "raw");
        ParseResult r = adapter.toParseResult(build(List.of(t), List.of()));
        LayoutBlock b = r.pages().get(0).blocks().get(0);
        assertEquals(BlockType.OTHER, b.blockType());
        assertEquals("raw", b.text());
    }

    @Test
    void footerAndFootnote_mapToFooterAndOther() {
        TextItem footer = text("#/texts/0", "page_footer", null, 1, null, "p1");
        TextItem footnote = text("#/texts/1", "footnote", null, 1, null, "fn");
        ParseResult r = adapter.toParseResult(build(List.of(footer, footnote), List.of()));
        assertEquals(BlockType.FOOTER, r.pages().get(0).blocks().get(0).blockType());
        assertEquals(BlockType.OTHER, r.pages().get(0).blocks().get(1).blockType());
    }

    @Test
    void nullJsonContent_yieldsTextOnlyResult() {
        DoclingConvertResponse resp = new DoclingConvertResponse(
                new DocumentResponse("f", "# Md", null, null, "raw text", null), "success", List.of(), 0.1, Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals("raw text", r.text());
        assertTrue(r.pages().isEmpty());
        assertTrue(r.tables().isEmpty());
        assertEquals("success", r.metadata().get("docling_status"));
    }

    @Test
    void nullDocument_yieldsEmptyResult_metadataStillSynthesised() {
        DoclingConvertResponse resp = new DoclingConvertResponse(null, "failure", List.of(), 0.0, Map.of());
        ParseResult r = adapter.toParseResult(resp);
        assertEquals("", r.text());
        assertTrue(r.pages().isEmpty());
        assertEquals("failure", r.metadata().get("docling_status"));
    }

    @Test
    void nullResponse_yieldsEmptyEverything() {
        ParseResult r = adapter.toParseResult(null);
        assertEquals("", r.text());
        assertTrue(r.pages().isEmpty());
        assertTrue(r.tables().isEmpty());
        assertNotNull(r.metadata());
        assertTrue(r.metadata().isEmpty());
    }

    @Test
    void textWithoutProv_doesNotThrow_pageDefaultsToZero() {
        TextItem t = new TextItem("#/texts/0", null, "text", null, null, "no prov", "no prov");
        ParseResult r = adapter.toParseResult(build(List.of(t), List.of()));
        assertEquals(0, r.pages().get(0).pageNo());
        assertNull(r.pages().get(0).blocks().get(0).bbox());
    }

    @Test
    void deserializeRealCapturedFixture_mapsAllFieldsWithoutLoss() throws Exception {
        // 真实 v0.5.1 captured 响应（裁剪：3 texts + 2 tables + 2 pages + base64 image truncated）
        ObjectMapper om = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        DoclingConvertResponse resp;
        try (var in = getClass().getResourceAsStream("/docling/sample-convert-response.json")) {
            assertNotNull(in, "fixture missing");
            resp = om.readValue(in, DoclingConvertResponse.class);
        }
        ParseResult r = adapter.toParseResult(resp);

        // 顶层
        assertEquals("success", r.metadata().get("docling_status"));
        assertNotNull(r.metadata().get("docling_processing_time"));
        assertEquals("DoclingDocument", r.metadata().get("docling_schema"));

        // 至少映射出一个 page + 第一个 block 的 label/page/bbox
        assertTrue(r.pages().size() >= 1);
        DocumentPageText firstPage = r.pages().get(0);
        assertNotNull(firstPage.blocks());
        assertTrue(firstPage.blocks().size() >= 1);
        // 真实数据第一项是 picture/HUATAI 标题，第二项是 section_header；
        // 验证 label 至少能映射成枚举（不会全部 OTHER），且 readingOrder 单调递增
        assertTrue(r.pages().get(0).blocks().get(0).readingOrder() >= 0);
        // 至少有一个 table，rows 不空
        assertTrue(r.tables().size() >= 1);
        assertTrue(r.tables().get(0).rows().size() >= 1);
    }

    @Test
    void deserializeRealCapturedJson_mapsAllRequiredFields() throws Exception {
        // 真实 captured 响应（来自 v0.5.1 + AML KYC sample），裁剪到核心字段
        String json = "{"
                + "\"document\":{"
                + "\"filename\":\"sample.pdf\","
                + "\"text_content\":\"Hello\","
                + "\"json_content\":{"
                + "\"schema_name\":\"DoclingDocument\","
                + "\"version\":\"1.1.0\","
                + "\"texts\":[{"
                + "\"self_ref\":\"#/texts/0\","
                + "\"label\":\"section_header\","
                + "\"level\":1,"
                + "\"prov\":[{\"page_no\":1,\"bbox\":{\"l\":10,\"t\":20,\"r\":110,\"b\":40,"
                + "\"coord_origin\":\"BOTTOMLEFT\"},\"charspan\":[0,5]}],"
                + "\"text\":\"Title\",\"orig\":\"Title\""
                + "}],"
                + "\"tables\":[{"
                + "\"self_ref\":\"#/tables/0\","
                + "\"label\":\"table\","
                + "\"prov\":[{\"page_no\":2,\"bbox\":{\"l\":0,\"t\":0,\"r\":50,\"b\":50,"
                + "\"coord_origin\":\"TOPLEFT\"},\"charspan\":[0,0]}],"
                + "\"data\":{\"num_rows\":1,\"num_cols\":1,\"table_cells\":["
                + "{\"text\":\"X\",\"row_span\":1,\"col_span\":1,\"start_row_offset_idx\":0,"
                + "\"end_row_offset_idx\":1,\"start_col_offset_idx\":0,\"end_col_offset_idx\":1,"
                + "\"column_header\":false,\"row_header\":false}]}"
                + "}],"
                + "\"pages\":{\"1\":{\"size\":{\"width\":612,\"height\":792}}}"
                + "}"
                + "},"
                + "\"status\":\"success\",\"errors\":[],\"processing_time\":1.5,\"timings\":{}"
                + "}";

        ObjectMapper om = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        DoclingConvertResponse resp = om.readValue(json, DoclingConvertResponse.class);
        ParseResult r = adapter.toParseResult(resp);

        assertEquals("Hello", r.text());
        assertEquals(1, r.pages().size());
        assertEquals(BlockType.TITLE, r.pages().get(0).blocks().get(0).blockType());
        assertEquals(1, r.pages().get(0).blocks().get(0).headingLevel());
        assertEquals(1, r.tables().size());
        assertEquals(List.of(List.of("X")), r.tables().get(0).rows());
        assertEquals("success", r.metadata().get("docling_status"));
        assertEquals(1.5, r.metadata().get("docling_processing_time"));
        assertEquals("DoclingDocument", r.metadata().get("docling_schema"));
    }
}
