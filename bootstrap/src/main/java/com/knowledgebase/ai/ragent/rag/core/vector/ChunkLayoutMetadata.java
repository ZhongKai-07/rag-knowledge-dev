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

package com.knowledgebase.ai.ragent.rag.core.vector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * VectorChunk.metadata 上 layout 字段的类型化访问层。
 *
 * <p>边界（v3）：仅做 Map 内 type access。<b>不依赖任何 knowledge / parser 域类型</b>。
 * Reader 三态兼容（List / String[] / JSON String），Writer 跳过 null/empty。
 *
 * <p>DO ↔ VectorChunk 与 CreateRequest ↔ VectorChunk 的桥接交给
 * {@code knowledge.service.support.KnowledgeChunkLayoutMapper}，<b>不在本类</b>。
 */
@Slf4j
public final class ChunkLayoutMetadata {

    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private ChunkLayoutMetadata() {}

    // ============ Reader ============

    public static Integer pageNumber(VectorChunk c)         { return readInt(c, VectorMetadataFields.PAGE_NUMBER); }
    public static Integer pageStart(VectorChunk c)          { return readInt(c, VectorMetadataFields.PAGE_START); }
    public static Integer pageEnd(VectorChunk c)            { return readInt(c, VectorMetadataFields.PAGE_END); }
    public static List<String> headingPath(VectorChunk c)   { return readStringList(c, VectorMetadataFields.HEADING_PATH); }
    public static String blockType(VectorChunk c)           { return readString(c, VectorMetadataFields.BLOCK_TYPE); }
    public static List<String> sourceBlockIds(VectorChunk c){ return readStringList(c, VectorMetadataFields.SOURCE_BLOCK_IDS); }
    public static String bboxRefs(VectorChunk c)            { return readString(c, VectorMetadataFields.BBOX_REFS); }
    public static String textLayerType(VectorChunk c)       { return readString(c, VectorMetadataFields.TEXT_LAYER_TYPE); }
    public static Double layoutConfidence(VectorChunk c)    { return readDouble(c, VectorMetadataFields.LAYOUT_CONFIDENCE); }

    private static Integer readInt(VectorChunk c, String key) {
        Object v = read(c, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Double readDouble(VectorChunk c, String key) {
        Object v = read(c, key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String readString(VectorChunk c, String key) {
        Object v = read(c, key);
        return v == null ? null : v.toString();
    }

    /**
     * 三态兼容：List / String[] / JSON String 兜底。
     * <p><b>v4 review P2 #5</b>：返回 {@code null}（不是空 List）来表达 "key 缺失"，
     * 让 BASIC chunk 的 SourceChunk 序列化与 {@code @JsonInclude(NON_EMPTY)} 对齐。
     */
    private static List<String> readStringList(VectorChunk c, String key) {
        Object v = read(c, key);
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<String> result = list.stream().filter(Objects::nonNull).map(Object::toString).toList();
            return result.isEmpty() ? null : result;
        }
        if (v instanceof String[] arr) {
            List<String> result = Arrays.stream(arr).filter(Objects::nonNull).toList();
            return result.isEmpty() ? null : result;
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                List<String> parsed = SHARED_MAPPER.readValue(s, STRING_LIST);
                return parsed == null || parsed.isEmpty() ? null : parsed;
            } catch (Exception ignored) {
                return List.of(s);   // 单值 String 兜底（视为非空）
            }
        }
        return null;
    }

    private static Object read(VectorChunk c, String key) {
        if (c == null || c.getMetadata() == null) return null;
        return c.getMetadata().get(key);
    }

    // ============ Writer Builder ============

    public static Builder writer(VectorChunk c) {
        if (c.getMetadata() == null) c.setMetadata(new HashMap<>());
        return new Builder(c.getMetadata());
    }

    public static final class Builder {
        private final Map<String, Object> meta;
        Builder(Map<String, Object> meta) { this.meta = meta; }

        public Builder pageNumber(Integer v)             { return putIfPresent(VectorMetadataFields.PAGE_NUMBER, v); }
        public Builder pageRange(Integer start, Integer end) {
            putIfPresent(VectorMetadataFields.PAGE_START, start);
            putIfPresent(VectorMetadataFields.PAGE_END, end);
            return this;
        }
        public Builder headingPath(List<String> path) {
            if (path != null && !path.isEmpty()) meta.put(VectorMetadataFields.HEADING_PATH, path);
            return this;
        }
        /** writer 对 BlockType 解耦：调用方传 BlockType.name() String，避免反向 import core.parser.layout.BlockType */
        public Builder blockType(String name) {
            if (name != null && !name.isBlank()) meta.put(VectorMetadataFields.BLOCK_TYPE, name);
            return this;
        }
        public Builder sourceBlockIds(List<String> ids) {
            if (ids != null && !ids.isEmpty()) meta.put(VectorMetadataFields.SOURCE_BLOCK_IDS, ids);
            return this;
        }
        public Builder bboxRefs(String json) {
            if (json != null && !json.isBlank() && !"[]".equals(json))
                meta.put(VectorMetadataFields.BBOX_REFS, json);
            return this;
        }
        public Builder textLayerType(String tlt) {
            if (tlt != null && !tlt.isBlank()) meta.put(VectorMetadataFields.TEXT_LAYER_TYPE, tlt);
            return this;
        }
        public Builder layoutConfidence(Double conf) {
            if (conf != null) meta.put(VectorMetadataFields.LAYOUT_CONFIDENCE, conf);
            return this;
        }

        private Builder putIfPresent(String key, Object value) {
            if (value != null) meta.put(key, value);
            return this;
        }
    }
}
