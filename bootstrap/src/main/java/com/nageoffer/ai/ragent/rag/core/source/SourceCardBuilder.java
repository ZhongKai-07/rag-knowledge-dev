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

package com.nageoffer.ai.ragent.rag.core.source;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dto.DocumentMetaSnapshot;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把检索到的 {@link RetrievedChunk} 聚合成文档级 {@link SourceCard} 列表。
 *
 * <p>纯聚合器：不理解业务分支（feature flag / 推不推判定 / SSE / 落库 / kbName
 * 查询）。边界判定由 {@code RAGChatServiceImpl} 的三层闸门控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceCardBuilder {

    private final KnowledgeDocumentService documentService;

    /**
     * 聚合 chunks 成文档级卡片。
     *
     * @param chunks          已去重的 chunk 列表（由编排层保证）
     * @param maxCards        卡片数上限（来自 {@code rag.sources.max-cards}）
     * @param previewMaxChars preview 文本的 codePoint 截断长度
     * @return 按 {@code topScore} 降序的 cards 列表（空列表合法）
     */
    public List<SourceCard> build(List<RetrievedChunk> chunks, int maxCards, int previewMaxChars) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        // 1) 过滤 docId == null 的 chunk，同时按 docId 分组（保留插入序）
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk c : chunks) {
            if (c.getDocId() == null) {
                log.warn("SourceCardBuilder: chunk id={} 无 docId，已丢弃", c.getId());
                continue;
            }
            grouped.computeIfAbsent(c.getDocId(), k -> new ArrayList<>()).add(c);
        }
        if (grouped.isEmpty()) {
            return List.of();
        }

        // 2) 批量查 meta
        Set<String> docIds = grouped.keySet();
        Map<String, DocumentMetaSnapshot> metaById = toMetaMap(documentService.findMetaByIds(docIds));

        // 3) 按 docId 生成卡片（过滤 meta 查不到的），按 topScore 降序，clip 到 maxCards
        List<SourceCard> cards = grouped.entrySet().stream()
                .filter(e -> metaById.containsKey(e.getKey()))
                .map(e -> buildCard(e.getKey(), e.getValue(), metaById.get(e.getKey()), previewMaxChars))
                .sorted(Comparator.comparingDouble((SourceCard c) -> c.getTopScore()).reversed())
                .limit(maxCards)
                .toList();

        // 4) 分配最终 index（降序后 1..N）
        List<SourceCard> numbered = new ArrayList<>(cards.size());
        int i = 1;
        for (SourceCard c : cards) {
            numbered.add(SourceCard.builder()
                    .index(i++)
                    .docId(c.getDocId())
                    .docName(c.getDocName())
                    .kbId(c.getKbId())
                    .topScore(c.getTopScore())
                    .chunks(c.getChunks())
                    .build());
        }
        return numbered;
    }

    private Map<String, DocumentMetaSnapshot> toMetaMap(List<DocumentMetaSnapshot> snapshots) {
        return snapshots.stream()
                .collect(Collectors.toMap(DocumentMetaSnapshot::docId, s -> s, (a, b) -> a));
    }

    private SourceCard buildCard(
            String docId,
            List<RetrievedChunk> chunksInDoc,
            DocumentMetaSnapshot meta,
            int previewMaxChars) {
        List<SourceChunk> sortedChunks = chunksInDoc.stream()
                .sorted(Comparator.comparing(
                        RetrievedChunk::getChunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(rc -> SourceChunk.builder()
                        .chunkId(rc.getId())
                        .chunkIndex(rc.getChunkIndex() == null ? -1 : rc.getChunkIndex())
                        .preview(truncateByCodePoint(rc.getText(), previewMaxChars))
                        .score(rc.getScore() == null ? 0f : rc.getScore())
                        .build())
                .toList();

        float topScore = (float) chunksInDoc.stream()
                .map(RetrievedChunk::getScore)
                .filter(Objects::nonNull)
                .mapToDouble(Float::doubleValue)
                .max()
                .orElse(0.0);

        return SourceCard.builder()
                .index(0) // 临时值，下面重新分配
                .docId(docId)
                .docName(meta.docName())
                .kbId(meta.kbId())
                .topScore(topScore)
                .chunks(sortedChunks)
                .build();
    }

    private static String truncateByCodePoint(String text, int max) {
        if (text == null) return "";
        if (text.codePointCount(0, text.length()) <= max) return text;
        int[] cps = text.codePoints().limit(max).toArray();
        return new String(cps, 0, cps.length);
    }
}
