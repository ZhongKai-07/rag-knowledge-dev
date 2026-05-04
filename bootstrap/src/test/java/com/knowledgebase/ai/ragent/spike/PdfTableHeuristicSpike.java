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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Spike 2: 表格启发式检测 (不引 Tabula，避免与 Tika 自带 PDFBox 3.x 版本冲突)。
 *
 * 思路：按行 (近似 y 坐标) 把 TextPosition 聚合，看每行的 x 间隙分布。
 * 表格的特征是连续多行有相似的 x 列起点（"列对齐"）。
 *
 * 跑法：
 *   1. 把 PDF 样本放到 tmp/parser-spike/samples/
 *   2. 在 IDE 里右键 main 直接跑
 *   3. 输出 stdout，建议 > tmp/parser-spike/table-heuristic.txt
 *
 * 看什么：
 *   - "candidate table region" 块是否真覆盖了你 PDF 里的表格
 *   - 列起点列表对得上眼睛看到的表格列吗
 *   - 如果启发式抓不准（合并单元格 / 无边框 / 跨页表）→ 这部分场景需要上 Tabula 或 Docling
 */
public class PdfTableHeuristicSpike {

    private static final Path SAMPLES_DIR = Path.of("tmp/parser-spike/samples");

    private static final float Y_TOLERANCE = 2.0f;       // 同一行的 y 容差 (pt)
    private static final float X_COL_TOLERANCE = 5.0f;   // 列对齐容差 (pt)
    private static final int MIN_COLS = 3;               // 至少 3 列才算"表格行"
    private static final int MIN_ROWS = 3;               // 连续 3 行列对齐才算表格

    public static void main(String[] args) throws IOException {
        if (!Files.exists(SAMPLES_DIR)) {
            System.err.println("[skip] samples dir missing: " + SAMPLES_DIR.toAbsolutePath());
            return;
        }
        try (Stream<Path> stream = Files.list(SAMPLES_DIR)) {
            List<Path> pdfs = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
            if (pdfs.isEmpty()) {
                System.err.println("[skip] no PDF samples in " + SAMPLES_DIR.toAbsolutePath());
                return;
            }
            for (Path pdf : pdfs) {
                analyze(pdf);
            }
        }
    }

    private static void analyze(Path pdf) throws IOException {
        System.out.println("\n========== " + pdf.getFileName() + " ==========");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                analyzePage(doc, p);
            }
        } catch (Exception e) {
            System.err.println("[err] " + pdf + " -> " + e);
        }
    }

    private static void analyzePage(PDDocument doc, int pageNum) throws IOException {
        Stripper s = new Stripper();
        s.setStartPage(pageNum);
        s.setEndPage(pageNum);
        s.getText(doc);
        List<Row> rows = groupIntoRows(s.positions);
        if (rows.size() < MIN_ROWS) return;

        // 找连续多行 x 起点对齐的"表格区"
        List<TableRegion> regions = detectTables(rows);
        if (regions.isEmpty()) return;

        System.out.println("\n-- page " + pageNum + " : " + regions.size() + " candidate table region(s) --");
        for (int i = 0; i < regions.size(); i++) {
            TableRegion r = regions.get(i);
            System.out.printf("  region[%d]  rows=%d  cols=%d  yRange=[%.0f, %.0f]  cols=%s%n",
                    i, r.rows.size(), r.colXs.size(), r.yMin, r.yMax, formatCols(r.colXs));
            // 打印前 5 行预览
            int preview = Math.min(5, r.rows.size());
            for (int j = 0; j < preview; j++) {
                Row row = r.rows.get(j);
                System.out.println("    | " + row.cellsToString());
            }
            if (r.rows.size() > preview) {
                System.out.println("    | ... +" + (r.rows.size() - preview) + " more rows");
            }
        }
    }

    // -------- 行聚合 --------
    private static List<Row> groupIntoRows(List<TextPosition> all) {
        all.sort(Comparator.comparingDouble(TextPosition::getYDirAdj));
        List<Row> rows = new ArrayList<>();
        Row cur = null;
        for (TextPosition tp : all) {
            float y = tp.getYDirAdj();
            if (cur == null || Math.abs(y - cur.y) > Y_TOLERANCE) {
                cur = new Row(y);
                rows.add(cur);
            }
            cur.add(tp);
        }
        for (Row r : rows) r.finish();
        return rows;
    }

    // -------- 表格检测 --------
    private static List<TableRegion> detectTables(List<Row> rows) {
        List<TableRegion> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            // 跳过列数不足的行
            if (rows.get(i).colXs.size() < MIN_COLS) { i++; continue; }
            int j = i;
            List<Row> chunk = new ArrayList<>();
            chunk.add(rows.get(j));
            j++;
            while (j < rows.size() && colsAlignedEnough(chunk.get(0).colXs, rows.get(j).colXs)) {
                chunk.add(rows.get(j));
                j++;
            }
            if (chunk.size() >= MIN_ROWS) {
                TableRegion r = new TableRegion();
                r.rows = chunk;
                r.colXs = chunk.get(0).colXs;
                r.yMin = chunk.get(0).y;
                r.yMax = chunk.get(chunk.size() - 1).y;
                out.add(r);
            }
            i = Math.max(j, i + 1);
        }
        return out;
    }

    private static boolean colsAlignedEnough(List<Float> a, List<Float> b) {
        if (b.size() < MIN_COLS) return false;
        // 至少 60% 的 a 列起点能在 b 里找到匹配
        int hit = 0;
        for (Float ax : a) {
            for (Float bx : b) {
                if (Math.abs(ax - bx) <= X_COL_TOLERANCE) { hit++; break; }
            }
        }
        return hit >= Math.max(MIN_COLS, (int) Math.ceil(a.size() * 0.6));
    }

    private static String formatCols(List<Float> xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.0f", xs.get(i)));
        }
        return sb.append("]").toString();
    }

    // -------- 数据结构 --------
    private static final class Stripper extends PDFTextStripper {
        final List<TextPosition> positions = new ArrayList<>();
        Stripper() throws IOException { super(); }
        @Override
        protected void writeString(String text, List<TextPosition> ps) throws IOException {
            positions.addAll(ps);
            super.writeString(text, ps);
        }
    }

    private static final class Row {
        final float y;
        final List<TextPosition> tokens = new ArrayList<>();
        List<Float> colXs = new ArrayList<>();
        List<String> cellTexts = new ArrayList<>();

        Row(float y) { this.y = y; }
        void add(TextPosition tp) { tokens.add(tp); }

        /** 把同一行 token 按 x 排序，按"间隙 > 字宽 1.5x"切成"列单元"。 */
        void finish() {
            if (tokens.isEmpty()) return;
            tokens.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
            float avgWidth = (float) tokens.stream()
                    .mapToDouble(TextPosition::getWidthDirAdj).average().orElse(2.0);
            float gapThreshold = Math.max(2f, avgWidth * 1.5f);

            StringBuilder cell = new StringBuilder();
            float cellStartX = tokens.get(0).getXDirAdj();
            float lastEndX = cellStartX;
            colXs.add(cellStartX);

            for (TextPosition tp : tokens) {
                float x = tp.getXDirAdj();
                if (x - lastEndX > gapThreshold && cell.length() > 0) {
                    cellTexts.add(cell.toString().strip());
                    cell.setLength(0);
                    cellStartX = x;
                    colXs.add(cellStartX);
                }
                cell.append(tp.getUnicode());
                lastEndX = x + tp.getWidthDirAdj();
            }
            if (cell.length() > 0) cellTexts.add(cell.toString().strip());
        }

        String cellsToString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cellTexts.size(); i++) {
                if (i > 0) sb.append(" | ");
                String c = cellTexts.get(i);
                sb.append(c.length() > 25 ? c.substring(0, 25) + "…" : c);
            }
            return sb.toString();
        }
    }

    private static final class TableRegion {
        List<Row> rows;
        List<Float> colXs;
        float yMin;
        float yMax;
    }
}
