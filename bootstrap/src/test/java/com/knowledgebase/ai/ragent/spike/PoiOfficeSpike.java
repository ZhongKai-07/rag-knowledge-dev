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

package com.knowledgebase.ai.ragent.spike;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Spike 3: POI 直读 Excel + Word，对比 Tika 把它们扁平化成纯文本的损失。
 *
 * 跑法：
 *   1. 把 .xlsx / .xls / .docx 样本放到 tmp/parser-spike/samples/
 *   2. 在 IDE 里右键 main 直接跑
 *   3. 输出 stdout，建议 > tmp/parser-spike/poi-office.txt
 *
 * 看什么：
 *   Excel：
 *     - sheet 名 / 合并区域 / 公式是否都拿到了
 *     - "merged region" 标记的范围对得上眼睛看到的合并单元格吗
 *     - 数值/日期 cell 是否需要 DataFormatter 才不丢精度
 *   Word：
 *     - paragraph.getStyle() 拿到的是不是 "Heading1/Heading2/..."（标准模板）
 *       还是 null / 自定义中文样式名（手写模板，需要字号兜底）
 *     - 表格能否原样取出（行 × 列）
 */
public class PoiOfficeSpike {

    private static final Path SAMPLES_DIR = Path.of("tmp/parser-spike/samples");

    public static void main(String[] args) throws Exception {
        if (!Files.exists(SAMPLES_DIR)) {
            System.err.println("[skip] samples dir missing: " + SAMPLES_DIR.toAbsolutePath());
            return;
        }
        try (Stream<Path> stream = Files.list(SAMPLES_DIR)) {
            List<Path> files = stream.sorted().toList();
            for (Path f : files) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                    analyzeExcel(f);
                } else if (name.endsWith(".docx")) {
                    analyzeWord(f);
                }
            }
        }
    }

    // -------- Excel --------
    private static void analyzeExcel(Path file) {
        System.out.println("\n========== [XLSX] " + file.getFileName() + " ==========");
        try (InputStream in = Files.newInputStream(file);
             Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                System.out.printf("%n-- sheet[%d] name='%s' rows=%d (last=%d) cols~=%d --%n",
                        s, sheet.getSheetName(),
                        sheet.getPhysicalNumberOfRows(),
                        sheet.getLastRowNum(),
                        guessMaxCols(sheet));

                // 合并区域
                int merged = sheet.getNumMergedRegions();
                if (merged > 0) {
                    System.out.println("  merged regions:");
                    for (int i = 0; i < merged; i++) {
                        CellRangeAddress r = sheet.getMergedRegion(i);
                        System.out.println("    " + r.formatAsString());
                    }
                }

                // 前 10 行 × 前 8 列预览
                int previewRows = Math.min(10, sheet.getLastRowNum() + 1);
                int previewCols = Math.min(8, guessMaxCols(sheet));
                System.out.println("  preview (first " + previewRows + " rows x " + previewCols + " cols):");
                for (int r = 0; r < previewRows; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) { System.out.println("    [r" + r + "] (empty)"); continue; }
                    StringBuilder sb = new StringBuilder("    [r" + r + "] ");
                    for (int c = 0; c < previewCols; c++) {
                        Cell cell = row.getCell(c);
                        String v = cell == null ? "" : fmt.formatCellValue(cell);
                        if (v.length() > 18) v = v.substring(0, 18) + "…";
                        if (c > 0) sb.append(" | ");
                        sb.append(v);
                    }
                    System.out.println(sb);
                }
            }
        } catch (Exception e) {
            System.err.println("[err] " + file + " -> " + e);
        }
    }

    private static int guessMaxCols(Sheet sheet) {
        int max = 0;
        int sample = Math.min(20, sheet.getLastRowNum() + 1);
        for (int r = 0; r < sample; r++) {
            Row row = sheet.getRow(r);
            if (row != null) max = Math.max(max, row.getLastCellNum());
        }
        return Math.max(max, 0);
    }

    // -------- Word --------
    private static void analyzeWord(Path file) {
        System.out.println("\n========== [DOCX] " + file.getFileName() + " ==========");
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            // 段落 + 样式
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            Map<String, Integer> styleHisto = new HashMap<>();
            int nullStyle = 0;
            for (XWPFParagraph p : paragraphs) {
                String style = p.getStyle();
                if (style == null || style.isBlank()) nullStyle++;
                else styleHisto.merge(style, 1, Integer::sum);
            }
            System.out.println("paragraphs=" + paragraphs.size() + "  nullStyle=" + nullStyle);
            System.out.println("-- style histogram --");
            styleHisto.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("  %-30s  x%d%n", e.getKey(), e.getValue()));

            // 候选标题：style 含 Heading / 标题
            System.out.println("\n-- heading-style paragraphs (first 40) --");
            paragraphs.stream()
                    .filter(p -> {
                        String s = p.getStyle();
                        return s != null && (s.toLowerCase().contains("heading") || s.contains("标题"));
                    })
                    .limit(40)
                    .forEach(p -> {
                        String txt = p.getText();
                        if (txt.length() > 80) txt = txt.substring(0, 80) + "…";
                        System.out.printf("  [%s] %s%n", p.getStyle(), txt);
                    });

            // 表格
            List<XWPFTable> tables = doc.getTables();
            System.out.println("\ntables=" + tables.size());
            for (int t = 0; t < tables.size(); t++) {
                XWPFTable tab = tables.get(t);
                List<XWPFTableRow> rows = tab.getRows();
                System.out.printf("  table[%d] rows=%d cols=%d%n",
                        t, rows.size(),
                        rows.isEmpty() ? 0 : rows.get(0).getTableCells().size());
                int preview = Math.min(3, rows.size());
                for (int r = 0; r < preview; r++) {
                    StringBuilder sb = new StringBuilder("    | ");
                    for (XWPFTableCell c : rows.get(r).getTableCells()) {
                        String txt = c.getText();
                        if (txt.length() > 20) txt = txt.substring(0, 20) + "…";
                        sb.append(txt).append(" | ");
                    }
                    System.out.println(sb);
                }
            }
        } catch (Exception e) {
            System.err.println("[err] " + file + " -> " + e);
        }
    }
}
