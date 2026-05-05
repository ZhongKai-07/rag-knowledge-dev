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
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Adapter：把 Docling 私有响应结构翻译成 engine-neutral 的 {@link ParseResult}。
 *
 * <p>未来若引入第二种结构化解析引擎，新建对应 Adapter 即可，不必动 {@link DocumentParser} 接口。
 */
@Component
public class DoclingResponseAdapter {

    public ParseResult toParseResult(DoclingConvertResponse resp) {
        if (resp == null || resp.document() == null) {
            return new ParseResult("", resp == null ? Map.of() : resp.metadata(), List.of(), List.of());
        }
        DoclingConvertResponse.Document doc = resp.document();
        List<DoclingConvertResponse.DoclingBlock> rawBlocks = doc.blocks() == null ? List.of() : doc.blocks();
        List<LayoutBlock> blocks = rawBlocks.stream().map(this::mapBlock).toList();
        List<LayoutTable> tables =
                (doc.tables() == null ? List.<DoclingConvertResponse.DoclingTable>of() : doc.tables())
                        .stream().map(this::mapTable).toList();
        String text = doc.text() == null ? "" : doc.text();
        List<DocumentPageText> pages = mapPages(blocks, rawBlocks);
        return new ParseResult(text, resp.metadata() == null ? Map.of() : resp.metadata(), pages, tables);
    }

    private LayoutBlock mapBlock(DoclingConvertResponse.DoclingBlock b) {
        return new LayoutBlock(
                b.id(),
                mapType(b.type()),
                b.pageNo() == null ? 0 : b.pageNo(),
                b.bbox() == null
                        ? null
                        : new LayoutBlock.Bbox(
                                b.bbox().x(), b.bbox().y(), b.bbox().width(), b.bbox().height()),
                b.text() == null ? "" : b.text(),
                b.readingOrder(),
                b.confidence(),
                b.level(),
                b.sectionPath() == null ? List.of() : b.sectionPath());
    }

    private LayoutTable mapTable(DoclingConvertResponse.DoclingTable t) {
        return new LayoutTable(
                t.id(),
                t.pageNo() == null ? 0 : t.pageNo(),
                t.bbox() == null
                        ? null
                        : new LayoutBlock.Bbox(
                                t.bbox().x(), t.bbox().y(), t.bbox().width(), t.bbox().height()),
                t.rows() == null ? List.of() : t.rows(),
                t.sectionPath() == null ? List.of() : t.sectionPath(),
                t.readingOrder(),
                t.confidence());
    }

    private List<DocumentPageText> mapPages(
            List<LayoutBlock> blocks, List<DoclingConvertResponse.DoclingBlock> rawBlocks) {
        Map<Integer, List<LayoutBlock>> blocksByPage = blocks.stream()
                .collect(Collectors.groupingBy(LayoutBlock::pageNo, LinkedHashMap::new, Collectors.toList()));
        Map<Integer, List<DoclingConvertResponse.DoclingBlock>> rawByPage = rawBlocks.stream()
                .collect(Collectors.groupingBy(
                        b -> b.pageNo() == null ? 0 : b.pageNo(), LinkedHashMap::new, Collectors.toList()));

        return blocksByPage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<LayoutBlock> sorted = sortByReadingOrder(e.getValue());
                    String pageText = sorted.stream()
                            .map(LayoutBlock::text)
                            .filter(s -> s != null && !s.isBlank())
                            .collect(Collectors.joining("\n"));
                    return new DocumentPageText(
                            null,
                            e.getKey(),
                            pageText,
                            inferTextLayerType(rawByPage.get(e.getKey())),
                            averageConfidence(sorted),
                            sorted);
                })
                .toList();
    }

    private List<LayoutBlock> sortByReadingOrder(List<LayoutBlock> blocks) {
        return blocks.stream()
                .sorted(Comparator.comparing(b -> b.readingOrder() == null ? Integer.MAX_VALUE : b.readingOrder()))
                .toList();
    }

    private String inferTextLayerType(List<DoclingConvertResponse.DoclingBlock> rawBlocks) {
        if (rawBlocks == null || rawBlocks.isEmpty()) return null;
        boolean hasOcr = rawBlocks.stream().anyMatch(b -> "OCR".equalsIgnoreCase(b.textLayerType()));
        boolean hasNative = rawBlocks.stream().anyMatch(b -> "NATIVE_TEXT".equalsIgnoreCase(b.textLayerType()));
        if (hasOcr && hasNative) return "MIXED";
        if (hasOcr) return "OCR";
        if (hasNative) return "NATIVE_TEXT";
        return rawBlocks.stream()
                .map(DoclingConvertResponse.DoclingBlock::textLayerType)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private Double averageConfidence(List<LayoutBlock> blocks) {
        OptionalDouble average = blocks.stream()
                .map(LayoutBlock::confidence)
                .filter(v -> v != null)
                .mapToDouble(Double::doubleValue)
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }

    private BlockType mapType(String docling) {
        if (docling == null) return BlockType.OTHER;
        return switch (docling.toLowerCase()) {
            case "heading", "title", "section-header" -> BlockType.TITLE;
            case "paragraph", "text" -> BlockType.PARAGRAPH;
            case "table" -> BlockType.TABLE;
            case "header", "page-header" -> BlockType.HEADER;
            case "footer", "page-footer" -> BlockType.FOOTER;
            case "list", "list-item" -> BlockType.LIST;
            case "caption" -> BlockType.CAPTION;
            default -> BlockType.OTHER;
        };
    }
}
