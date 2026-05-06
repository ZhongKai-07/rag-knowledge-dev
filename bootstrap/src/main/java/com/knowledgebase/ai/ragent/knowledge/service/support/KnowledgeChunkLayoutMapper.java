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

package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Knowledge 域内的 layout 字段桥接层（PR 6 / v3 review 引入）：
 * <ul>
 *   <li>{@link #copyFromDO}: re-index 路径用，DO → VectorChunk.metadata（JSON String → List 反序列化）</li>
 *   <li>{@link #copyToCreateRequest}: persist 路径用，VectorChunk.metadata → CreateRequest（List → JSON String 序列化）</li>
 * </ul>
 *
 * <p>本类自由 import knowledge.dao.entity.KnowledgeChunkDO 和
 * knowledge.controller.request.KnowledgeChunkCreateRequest；这是合规的 knowledge → rag.core.vector
 * 单向依赖。{@link ChunkLayoutMetadata} 维持 vector core 抽象的纯净。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeChunkLayoutMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    /** DB 反向回填：DO 9 字段 → VectorChunk.metadata。 */
    public void copyFromDO(KnowledgeChunkDO chunkDO, VectorChunk target) {
        if (chunkDO == null || target == null) return;
        ChunkLayoutMetadata.Builder w = ChunkLayoutMetadata.writer(target);
        w.pageNumber(chunkDO.getPageNumber());
        w.pageRange(chunkDO.getPageStart(), chunkDO.getPageEnd());
        w.blockType(chunkDO.getBlockType());
        w.bboxRefs(chunkDO.getBboxRefs());
        w.textLayerType(chunkDO.getTextLayerType());
        w.layoutConfidence(chunkDO.getLayoutConfidence());
        w.headingPath(parseStringList(chunkDO.getHeadingPath(), "headingPath", chunkDO.getId()));
        w.sourceBlockIds(parseStringList(chunkDO.getSourceBlockIds(), "sourceBlockIds", chunkDO.getId()));
    }

    /**
     * Persist 序列化：VectorChunk.metadata → CreateRequest 9 字段。
     * v4 review P2 #5：reader 现在缺 key 返 null（不是空 List），所以 hp / sb 必须 null-safe。
     */
    public void copyToCreateRequest(VectorChunk source, KnowledgeChunkCreateRequest target) {
        if (source == null || target == null || source.getMetadata() == null) return;
        target.setPageNumber(ChunkLayoutMetadata.pageNumber(source));
        target.setPageStart(ChunkLayoutMetadata.pageStart(source));
        target.setPageEnd(ChunkLayoutMetadata.pageEnd(source));
        target.setBlockType(ChunkLayoutMetadata.blockType(source));
        target.setBboxRefs(ChunkLayoutMetadata.bboxRefs(source));
        target.setTextLayerType(ChunkLayoutMetadata.textLayerType(source));
        target.setLayoutConfidence(ChunkLayoutMetadata.layoutConfidence(source));

        List<String> hp = ChunkLayoutMetadata.headingPath(source);    // 可能 null
        target.setHeadingPath(hp == null || hp.isEmpty() ? null : toJson(hp));
        List<String> sb = ChunkLayoutMetadata.sourceBlockIds(source);
        target.setSourceBlockIds(sb == null || sb.isEmpty() ? null : toJson(sb));
    }

    private List<String> parseStringList(String json, String fieldName, String chunkId) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to deserialize {} for chunk {}: {}", fieldName, chunkId, json);
            return Collections.emptyList();
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }
}
