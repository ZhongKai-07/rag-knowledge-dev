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

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.dto.SourceRefPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将检索 chunk 转换为前端可展示的来源卡片
 */
@Component
public class SourceCitationResolver {

    private static final int MAX_SOURCES = 5;
    private static final int SNIPPET_LEN = 140;
    private static final int SUMMARY_LEN = 600;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public SourceCitationResolver(KnowledgeChunkMapper knowledgeChunkMapper,
                                  KnowledgeDocumentMapper knowledgeDocumentMapper) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
    }

    public List<SourceRefPayload> buildSources(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return List.of();
        }
        List<RetrievedChunk> top = chunks.stream()
                .filter(chunk -> StrUtil.isNotBlank(chunk.getId()) && StrUtil.isNotBlank(chunk.getText()))
                .limit(MAX_SOURCES)
                .toList();
        if (top.isEmpty()) {
            return List.of();
        }

        List<String> chunkIds = top.stream().map(RetrievedChunk::getId).toList();
        Map<String, KnowledgeChunkDO> chunkMap = loadChunks(chunkIds);
        Map<String, KnowledgeDocumentDO> docMap = loadDocuments(chunkMap.values().stream()
                .map(KnowledgeChunkDO::getDocId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList());

        List<SourceRefPayload> sources = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            RetrievedChunk item = top.get(i);
            KnowledgeChunkDO chunk = chunkMap.get(item.getId());
            KnowledgeDocumentDO doc = chunk == null ? null : docMap.get(chunk.getDocId());
            String snippet = trim(item.getText(), SNIPPET_LEN);
            String summary = trim(item.getText(), SUMMARY_LEN);
            String docName = doc == null ? fallbackDocName(item.getId(), i + 1) : doc.getDocName();
            sources.add(new SourceRefPayload(
                    i + 1,
                    item.getId(),
                    chunk == null ? null : chunk.getDocId(),
                    docName,
                    chunk == null ? item.getKbId() : chunk.getKbId(),
                    item.getScore(),
                    snippet,
                    summary
            ));
        }
        return sources;
    }

    public String buildCitationMarkers(List<SourceRefPayload> sources) {
        if (CollUtil.isEmpty(sources)) {
            return "";
        }
        StringBuilder markers = new StringBuilder("\n\n");
        for (SourceRefPayload source : sources) {
            markers.append("[").append(source.index()).append("]");
        }
        return markers.toString();
    }

    private Map<String, KnowledgeChunkDO> loadChunks(List<String> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return Map.of();
        }
        List<KnowledgeChunkDO> rows = knowledgeChunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .in(KnowledgeChunkDO::getId, chunkIds)
                        .eq(KnowledgeChunkDO::getDeleted, 0)
        );
        Map<String, KnowledgeChunkDO> result = new LinkedHashMap<>();
        for (KnowledgeChunkDO row : rows) {
            result.put(row.getId(), row);
        }
        return result;
    }

    private Map<String, KnowledgeDocumentDO> loadDocuments(List<String> docIds) {
        if (CollUtil.isEmpty(docIds)) {
            return Map.of();
        }
        List<KnowledgeDocumentDO> rows = knowledgeDocumentMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .in(KnowledgeDocumentDO::getId, docIds)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        Map<String, KnowledgeDocumentDO> result = new LinkedHashMap<>();
        for (KnowledgeDocumentDO row : rows) {
            result.put(row.getId(), row);
        }
        return result;
    }

    private String trim(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    private String fallbackDocName(String chunkId, int index) {
        if (StrUtil.isNotBlank(chunkId)) {
            return "片段 " + chunkId;
        }
        return "来源 " + index;
    }
}
