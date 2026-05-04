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
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
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
 * Spike 1: PDF 字号直方图 + 候选标题 + 页眉页脚检测。
 *
 * 跑法：
 *   1. 把 PDF 样本放到 tmp/parser-spike/samples/
 *   2. 在 IDE 里右键 main 直接跑（不走 Spring）
 *   3. 输出 stdout，建议 > tmp/parser-spike/font-histogram.txt
 *
 * 看什么：
 *   - 字号直方图是否分层清晰（正文一档 + 几档明显更大的标题）→ 字号启发式可行
 *   - 候选标题列表里"第X章 / 1.1 / Article" 是否大量混入正文 → 编号正则需要兜底到啥程度
 *   - 每页 y 顶部 / 底部固定文本 → 页眉页脚剥离方案
 */
public class PdfFontHistogramSpike {

    private static final Path SAMPLES_DIR = Path.of("tmp/parser-spike/samples");

    // y 在页面顶部 60pt 内 / 底部 60pt 内 视为页眉页脚候选区
    private static final float HEADER_BAND = 60f;
    private static final float FOOTER_BAND = 60f;

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
            PageHeight ph = new PageHeight(doc);
            Collector collector = new Collector(ph);
            collector.setStartPage(1);
            collector.setEndPage(doc.getNumberOfPages());
            collector.getText(doc); // drives writeString callbacks
            System.out.println("pages=" + doc.getNumberOfPages());
            collector.report();
        } catch (Exception e) {
            System.err.println("[err] " + pdf + " -> " + e);
        }
    }

    /** 只在第一次回调时知道页面高度，缓存按 page index → height。 */
    private static final class PageHeight {
        private final float[] heights;
        PageHeight(PDDocument doc) {
            heights = new float[doc.getNumberOfPages()];
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                heights[i] = doc.getPage(i).getMediaBox().getHeight();
            }
        }
        float at(int oneBasedPage) { return heights[oneBasedPage - 1]; }
    }

    private static final class Collector extends PDFTextStripper {
        private final PageHeight pageHeight;
        // round(fontSize, 1) → count
        private final Map<Double, Integer> sizeHisto = new TreeMap<>();
        // 候选标题：fontSize > 中位数 + 阈值 的整行文本
        private final List<String> headingCandidates = new ArrayList<>();
        // 页眉/页脚候选：每页顶部/底部带（trim 后）→ 出现页数
        private final Map<String, Integer> headerFreq = new HashMap<>();
        private final Map<String, Integer> footerFreq = new HashMap<>();

        private int currentPage = 1;
        private int totalPages = 0;
        private double bodyMedian = 0;

        Collector(PageHeight ph) throws IOException {
            this.pageHeight = ph;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            super.writeString(text, positions);
            if (positions.isEmpty() || text.isBlank()) return;

            // 取本行最大字号（避免上标/下标干扰）
            double maxSize = positions.stream()
                    .mapToDouble(TextPosition::getFontSizeInPt)
                    .max().orElse(0);
            double rounded = Math.round(maxSize * 10.0) / 10.0;
            sizeHisto.merge(rounded, 1, Integer::sum);

            // 行的 y 坐标（PDFBox: y from top）
            float y = positions.get(0).getYDirAdj();
            float h = pageHeight.at(currentPage);
            String trimmed = text.strip();

            if (y <= HEADER_BAND) {
                headerFreq.merge(trimmed, 1, Integer::sum);
            } else if (y >= h - FOOTER_BAND) {
                footerFreq.merge(trimmed, 1, Integer::sum);
            }

            // 候选标题（先粗采，最后再用中位数过滤）
            headingCandidates.add(String.format("[p%d size=%.1f y=%.0f] %s",
                    currentPage, rounded, y, trimmed));
        }

        @Override
        protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
            super.startPage(page);
        }

        @Override
        protected void endPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
            currentPage++;
            super.endPage(page);
        }

        void report() {
            totalPages = currentPage - 1;

            // 1) 字号直方图
            System.out.println("\n-- font size histogram (rounded to 0.1pt) --");
            int total = sizeHisto.values().stream().mapToInt(Integer::intValue).sum();
            sizeHisto.entrySet().stream()
                    .sorted(Map.Entry.<Double, Integer>comparingByValue().reversed())
                    .limit(15)
                    .forEach(e -> System.out.printf("  %5.1f pt   %6d   %5.1f%%%n",
                            e.getKey(), e.getValue(), 100.0 * e.getValue() / total));

            // 出现最多的字号 = 推定正文
            bodyMedian = sizeHisto.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(10.0);
            System.out.println("inferred body size = " + bodyMedian);

            // 2) 候选标题（字号 > body + 1pt 的行，最多 60 条）
            System.out.println("\n-- heading candidates (fontSize > body + 1pt) --");
            headingCandidates.stream()
                    .filter(s -> {
                        // 解析行首的 size=xx.x
                        int i = s.indexOf("size=");
                        if (i < 0) return false;
                        try {
                            double sz = Double.parseDouble(s.substring(i + 5, s.indexOf(' ', i + 5)));
                            return sz > bodyMedian + 1.0;
                        } catch (Exception ex) { return false; }
                    })
                    .limit(60)
                    .forEach(System.out::println);

            // 3) 页眉/页脚频次（出现 ≥ 总页数一半视作"重复"）
            System.out.println("\n-- header band repeats (top " + HEADER_BAND + "pt) --");
            int threshold = Math.max(2, totalPages / 2);
            headerFreq.entrySet().stream()
                    .filter(e -> e.getValue() >= threshold)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("  x%d  %s%n", e.getValue(), truncate(e.getKey())));

            System.out.println("\n-- footer band repeats (bottom " + FOOTER_BAND + "pt) --");
            footerFreq.entrySet().stream()
                    .filter(e -> e.getValue() >= threshold)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("  x%d  %s%n", e.getValue(), truncate(e.getKey())));
        }

        private String truncate(String s) {
            return s.length() > 80 ? s.substring(0, 80) + "..." : s;
        }
    }
}
