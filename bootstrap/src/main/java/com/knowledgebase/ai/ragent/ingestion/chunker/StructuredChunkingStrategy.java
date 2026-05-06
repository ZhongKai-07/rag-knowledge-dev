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

package com.knowledgebase.ai.ragent.ingestion.chunker;

import com.knowledgebase.ai.ragent.core.chunk.ChunkingMode;
import com.knowledgebase.ai.ragent.core.chunk.ChunkingOptions;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.core.parser.ParseResult;
import com.knowledgebase.ai.ragent.core.parser.layout.BlockType;
import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutBlock;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Layout-aware structured chunker（PR 6 / Q5）。
 *
 * <p>priority=100；{@link #supports(ChunkingMode, IngestionChunkingInput)} 不依赖 mode 参数 ——
 * layout 可用性是客观判断，mode 只在 layout 不可用时引导 fallback 选择 legacy chunker。
 *
 * <p>算法：
 * <ol>
 *   <li>Tables → 每表一个原子 chunk（block_type=TABLE）</li>
 *   <li>Body blocks → 按页排序，TITLE 触发 section flush，超 dims.max() 中途切</li>
 *   <li>flushSection → 1+ body chunks，headingPath 从首 block 推断，block_type=PARAGRAPH</li>
 * </ol>
 */
@Component
public class StructuredChunkingStrategy implements IngestionChunkingStrategy {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(ChunkingMode mode, IngestionChunkingInput input) {
        ParseResult pr = input.parseResult();
        if (pr == null) return false;
        return !pr.pages().isEmpty() || !pr.tables().isEmpty();
    }

    @Override
    public List<VectorChunk> chunk(IngestionChunkingInput input, ChunkingOptions options) {
        StructuredChunkingDimensions dims = StructuredChunkingOptionsResolver.resolve(options);
        ParseResult pr = input.parseResult();
        List<VectorChunk> out = new ArrayList<>();

        // 1. tables：每个表格一个 chunk，原子不切
        for (LayoutTable t : pr.tables()) {
            VectorChunk vc = newChunk(serializeTable(t), out.size());
            ChunkLayoutMetadata.writer(vc)
                    .pageNumber(t.pageNo())
                    .pageRange(t.pageNo(), t.pageNo())
                    .headingPath(t.headingPath())
                    .blockType(BlockType.TABLE.name())
                    .sourceBlockIds(t.tableId() == null ? List.of() : List.of(t.tableId()))
                    .bboxRefs(bboxRefsJson(t.tableId(), t.pageNo(), t.bbox()))
                    .textLayerType(textLayerTypeForRange(pr.pages(), t.pageNo(), t.pageNo()))
                    .layoutConfidence(t.confidence());
            out.add(vc);
        }

        // 2. body blocks：按 title 边界分段，按 dims 软切
        List<LayoutBlock> currentSection = new ArrayList<>();
        for (DocumentPageText page : pr.pages().stream()
                .sorted(Comparator.comparingInt(DocumentPageText::pageNo)).toList()) {
            List<LayoutBlock> orderedBlocks = page.blocks().stream()
                    .filter(this::isContentBlock)
                    .sorted(Comparator.comparing(
                            b -> b.readingOrder() == null ? Integer.MAX_VALUE : b.readingOrder()))
                    .toList();
            for (LayoutBlock b : orderedBlocks) {
                if (b.blockType() == BlockType.TITLE && !currentSection.isEmpty()) {
                    flushSection(currentSection, pr.pages(), out, dims);
                    currentSection.clear();
                }
                currentSection.add(b);
            }
        }
        if (!currentSection.isEmpty()) flushSection(currentSection, pr.pages(), out, dims);
        return out;
    }

    private boolean isContentBlock(LayoutBlock b) {
        return b.blockType() != BlockType.HEADER && b.blockType() != BlockType.FOOTER;
    }

    private void flushSection(
            List<LayoutBlock> section,
            List<DocumentPageText> pages,
            List<VectorChunk> out,
            StructuredChunkingDimensions dims) {
        if (section.isEmpty()) return;
        StringBuilder buf = new StringBuilder();
        List<LayoutBlock> slice = new ArrayList<>();
        List<String> path = section.get(0).headingPath();
        if (section.get(0).blockType() == BlockType.TITLE) {
            List<String> extended = new ArrayList<>(path);
            extended.add(section.get(0).text());
            path = extended;
        }
        for (LayoutBlock b : section) {
            if (buf.length() + b.text().length() > dims.max() && buf.length() > 0) {
                out.add(buildBodyChunk(buf.toString(), path, List.copyOf(slice), pages, out.size()));
                buf.setLength(0);
                slice.clear();
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(b.text());
            slice.add(b);
        }
        if (buf.length() > 0) {
            out.add(buildBodyChunk(buf.toString(), path, List.copyOf(slice), pages, out.size()));
        }
    }

    private VectorChunk buildBodyChunk(
            String text,
            List<String> headingPath,
            List<LayoutBlock> sourceBlocks,
            List<DocumentPageText> pages,
            int index) {
        int pageStart = sourceBlocks.stream().mapToInt(LayoutBlock::pageNo).min().orElse(0);
        int pageEnd = sourceBlocks.stream().mapToInt(LayoutBlock::pageNo).max().orElse(pageStart);
        List<String> blockIds = sourceBlocks.stream()
                .map(LayoutBlock::blockId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
        String bboxRefs = sourceBlocks.stream()
                .filter(b -> b.bbox() != null)
                .map(b -> bboxRefJson(b.blockId(), b.pageNo(), b.bbox()))
                .collect(Collectors.joining(",", "[", "]"));
        if ("[]".equals(bboxRefs)) bboxRefs = null;

        VectorChunk vc = newChunk(text, index);
        ChunkLayoutMetadata.writer(vc)
                .pageNumber(pageStart)
                .pageRange(pageStart, pageEnd)
                .headingPath(headingPath)
                .blockType(BlockType.PARAGRAPH.name())
                .sourceBlockIds(blockIds)
                .bboxRefs(bboxRefs)
                .textLayerType(textLayerTypeForRange(pages, pageStart, pageEnd))
                .layoutConfidence(averageConfidence(sourceBlocks));
        return vc;
    }

    private VectorChunk newChunk(String content, int index) {
        return VectorChunk.builder()
                .chunkId(java.util.UUID.randomUUID().toString())
                .index(index)
                .content(content)
                .metadata(new HashMap<>())
                .build();
    }

    private String textLayerTypeForRange(List<DocumentPageText> pages, int pageStart, int pageEnd) {
        List<String> values = pages.stream()
                .filter(p -> p.pageNo() >= pageStart && p.pageNo() <= pageEnd)
                .map(DocumentPageText::textLayerType)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (values.isEmpty()) return null;
        return values.size() == 1 ? values.get(0) : "MIXED";
    }

    private Double averageConfidence(List<LayoutBlock> blocks) {
        OptionalDouble avg = blocks.stream()
                .map(LayoutBlock::confidence)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
        return avg.isPresent() ? avg.getAsDouble() : null;
    }

    private String bboxRefsJson(String blockId, int pageNo, LayoutBlock.Bbox bbox) {
        if (bbox == null) return null;
        return "[" + bboxRefJson(blockId, pageNo, bbox) + "]";
    }

    private String bboxRefJson(String blockId, int pageNo, LayoutBlock.Bbox bbox) {
        return "{\"blockId\":\""
                + (blockId == null ? "" : blockId)
                + "\","
                + "\"pageNo\":"
                + pageNo
                + ","
                + "\"x\":"
                + bbox.x()
                + ",\"y\":"
                + bbox.y()
                + ","
                + "\"width\":"
                + bbox.width()
                + ",\"height\":"
                + bbox.height()
                + "}";
    }

    private String serializeTable(LayoutTable t) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : t.rows()) sb.append(String.join(" | ", row)).append("\n");
        return sb.toString().stripTrailing();
    }
}
