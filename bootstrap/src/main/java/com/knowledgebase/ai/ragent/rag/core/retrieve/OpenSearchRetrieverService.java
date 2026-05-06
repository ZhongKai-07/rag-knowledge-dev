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

package com.knowledgebase.ai.ragent.rag.core.retrieve;

import com.knowledgebase.ai.ragent.core.chunk.VectorChunk;
import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;
import com.knowledgebase.ai.ragent.infra.embedding.EmbeddingService;
import com.knowledgebase.ai.ragent.rag.config.RAGDefaultProperties;
import com.knowledgebase.ai.ragent.rag.core.vector.ChunkLayoutMetadata;
import com.knowledgebase.ai.ragent.rag.core.vector.VectorMetadataFields;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch", matchIfMissing = true)
public class OpenSearchRetrieverService implements RetrieverService {

    private static final String PIPELINE_NAME = "ragent-hybrid-search-pipeline";

    private final OpenSearchClient client;
    private final EmbeddingService embeddingService;
    private final RAGDefaultProperties ragDefaultProperties;
    private final ObjectMapper objectMapper;

    private volatile boolean pipelineReady = false;

    @PostConstruct
    public void init() {
        try {
            try (var response = client.generic().execute(
                    Requests.builder()
                            .method("GET")
                            .endpoint("_search/pipeline/" + PIPELINE_NAME)
                            .build())) {
                if (response.getStatus() == 200) {
                    pipelineReady = true;
                    log.info("Hybrid search pipeline available for retrieval");
                }
            }
        } catch (Exception e) {
            log.debug("Pipeline not detected, using knn-only retrieval until pipeline is created");
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        String collectionName = StringUtils.hasText(retrieveParam.getCollectionName())
                ? retrieveParam.getCollectionName()
                : ragDefaultProperties.getCollectionName();

        List<Float> embedding = embeddingService.embed(retrieveParam.getQuery());
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i);
        }

        return doSearch(collectionName, retrieveParam.getQuery(), vector,
                retrieveParam.getTopK(), retrieveParam.getMetadataFilters());
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        String collectionName = StringUtils.hasText(retrieveParam.getCollectionName())
                ? retrieveParam.getCollectionName()
                : ragDefaultProperties.getCollectionName();

        return doSearch(collectionName, retrieveParam.getQuery(), vector,
                retrieveParam.getTopK(), retrieveParam.getMetadataFilters());
    }

    @SuppressWarnings("unchecked")
    private List<RetrievedChunk> doSearch(String collectionName, String query, float[] vector,
                                          int topK, List<MetadataFilter> metadataFilters) {
        // QSI-3: 所有 retrieval 必须经 DefaultMetadataFilterBuilder, filter set 至少含
        // kb_id IN 与 security_level LTE_OR_MISSING. 缺失即 fail-fast, 不被下面的
        // catch (Exception) 吞成空 list. 必须在 try 之前调用.
        enforceFilterContract(metadataFilters, collectionName);
        try {
            String queryJson;

            if (pipelineReady) {
                queryJson = buildHybridQuery(query, vector, topK, metadataFilters);
            } else {
                queryJson = buildKnnOnlyQuery(vector, topK, metadataFilters);
            }

            String endpoint = collectionName + "/_search?size=" + topK
                    + "&_source_includes=id,content,metadata";

            try (var response = client.generic().execute(
                    Requests.builder()
                            .method("POST")
                            .endpoint(endpoint)
                            .json(queryJson)
                            .build())) {

                if (response.getStatus() >= 300) {
                    log.error("OpenSearch search returned status {} for index: {}", response.getStatus(), collectionName);
                    return List.of();
                }

                String body = new String(response.getBody().get().body().readAllBytes());
                Map<String, Object> responseMap = objectMapper.readValue(body, new TypeReference<>() {});
                Map<String, Object> hits = (Map<String, Object>) responseMap.get("hits");
                List<Map<String, Object>> hitList = (List<Map<String, Object>>) hits.get("hits");

                return hitList.stream()
                        .map(this::toRetrievedChunk)
                        .collect(Collectors.toList());
            }

        } catch (IllegalStateException e) {
            // 显式不吞契约违规: 防御未来重构 (例如有人把 enforceFilterContract 挪进 try)
            throw e;
        } catch (Exception e) {
            log.error("OpenSearch retrieval failed for index: {}", collectionName, e);
            return List.of();
        }
    }

    /**
     * QSI-3: 所有 OpenSearch retrieval 必须经过 DefaultMetadataFilterBuilder,
     * filter set 必须同时含 kb_id IN 与 security_level LTE_OR_MISSING.
     * 缺失即 fail-fast, 避免静默查询绕过 RBAC 边界.
     */
    private static void enforceFilterContract(List<MetadataFilter> filters, String collectionName) {
        boolean hasKbIdFilter = filters != null && filters.stream()
                .anyMatch(f -> VectorMetadataFields.KB_ID.equals(f.field())
                        && f.op() == MetadataFilter.FilterOp.IN);
        boolean hasSecurityLevelFilter = filters != null && filters.stream()
                .anyMatch(f -> VectorMetadataFields.SECURITY_LEVEL.equals(f.field())
                        && f.op() == MetadataFilter.FilterOp.LTE_OR_MISSING);
        if (!hasKbIdFilter || !hasSecurityLevelFilter) {
            throw new IllegalStateException(
                    "OpenSearch filter contract violated for collection=" + collectionName
                            + ", hasKbIdFilter=" + hasKbIdFilter
                            + ", hasSecurityLevelFilter=" + hasSecurityLevelFilter
                            + ". All retrieval must go through DefaultMetadataFilterBuilder.");
        }
    }

    private String buildHybridQuery(String query, float[] vector, int topK,
                                    List<MetadataFilter> metadataFilters) {
        String vectorStr = floatArrayToJson(vector);
        String filterClause = buildFilterClause(metadataFilters);

        if (filterClause.isEmpty()) {
            return """
                    {
                      "query": {
                        "hybrid": {
                          "queries": [
                            { "knn": { "embedding": { "vector": %s, "k": %d } } },
                            { "match": { "content": "%s" } }
                          ]
                        }
                      }
                    }
                    """.formatted(vectorStr, topK, escapeJson(query));
        }

        return """
                {
                  "query": {
                    "hybrid": {
                      "queries": [
                        {
                          "bool": {
                            "must": { "knn": { "embedding": { "vector": %s, "k": %d } } },
                            "filter": [%s]
                          }
                        },
                        {
                          "bool": {
                            "must": { "match": { "content": "%s" } },
                            "filter": [%s]
                          }
                        }
                      ]
                    }
                  }
                }
                """.formatted(vectorStr, topK, filterClause, escapeJson(query), filterClause);
    }

    private String buildKnnOnlyQuery(float[] vector, int topK, List<MetadataFilter> metadataFilters) {
        String vectorStr = floatArrayToJson(vector);
        String filterClause = buildFilterClause(metadataFilters);

        if (filterClause.isEmpty()) {
            return """
                    { "query": { "knn": { "embedding": { "vector": %s, "k": %d } } } }
                    """.formatted(vectorStr, topK);
        }

        return """
                {
                  "query": {
                    "bool": {
                      "must": { "knn": { "embedding": { "vector": %s, "k": %d } } },
                      "filter": [%s]
                    }
                  }
                }
                """.formatted(vectorStr, topK, filterClause);
    }

    private String buildFilterClause(List<MetadataFilter> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return "";
        }
        return metadataFilters.stream()
                .map(this::renderFilter)
                .collect(Collectors.joining(", "));
    }

    private String renderFilter(MetadataFilter f) {
        String path = "metadata." + escapeJson(f.field());
        return switch (f.op()) {
            case EQ -> """
                    { "term": { "%s": %s } }""".formatted(path, jsonValue(f.value()));
            case LTE -> """
                    { "range": { "%s": { "lte": %s } } }""".formatted(path, jsonValue(f.value()));
            case LTE_OR_MISSING -> """
                    { "bool": { "should": [{ "range": { "%s": { "lte": %s } } }, { "bool": { "must_not": { "exists": { "field": "%s" } } } }], "minimum_should_match": 1 } }""".formatted(path, jsonValue(f.value()), path);
            case GTE -> """
                    { "range": { "%s": { "gte": %s } } }""".formatted(path, jsonValue(f.value()));
            case LT -> """
                    { "range": { "%s": { "lt": %s } } }""".formatted(path, jsonValue(f.value()));
            case GT -> """
                    { "range": { "%s": { "gt": %s } } }""".formatted(path, jsonValue(f.value()));
            case IN -> """
                    { "terms": { "%s": %s } }""".formatted(path, jsonArray(f.value()));
        };
    }

    private String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "\"" + escapeJson(v.toString()) + "\"";
    }

    private String jsonArray(Object v) {
        if (!(v instanceof Collection<?> c)) {
            throw new IllegalArgumentException("IN filter expects Collection, got " + v);
        }
        return c.stream()
                .map(this::jsonValue)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    @SuppressWarnings("unchecked")
    private RetrievedChunk toRetrievedChunk(Map<String, Object> hit) {
        Map<String, Object> source = (Map<String, Object>) hit.get("_source");
        String id = source != null && source.get("id") != null
                ? String.valueOf(source.get("id"))
                : String.valueOf(hit.get("_id"));
        String content = source != null ? String.valueOf(source.getOrDefault("content", "")) : "";
        Object rawScore = hit.get("_score");
        float score = rawScore != null
                ? Math.min(((Number) rawScore).floatValue(), 1.0f)
                : 0f;

        String kbId = null;
        Integer securityLevel = null;
        String docId = null;
        Integer chunkIndex = null;
        Map<String, Object> metaMap = null;
        if (source != null) {
            Object meta = source.get("metadata");
            if (meta instanceof Map<?, ?> rawMeta) {
                @SuppressWarnings("unchecked")
                Map<String, Object> castMeta = (Map<String, Object>) rawMeta;
                metaMap = castMeta;
                Object kb = metaMap.get(VectorMetadataFields.KB_ID);
                if (kb != null && !kb.toString().isBlank()) {
                    kbId = kb.toString();
                }
                Object sl = metaMap.get(VectorMetadataFields.SECURITY_LEVEL);
                if (sl instanceof Number n) {
                    securityLevel = n.intValue();
                }
                Object did = metaMap.get(VectorMetadataFields.DOC_ID);
                if (did != null && !did.toString().isBlank()) {
                    docId = did.toString();
                }
                Object ci = metaMap.get(VectorMetadataFields.CHUNK_INDEX);
                if (ci instanceof Number n) {
                    chunkIndex = n.intValue();
                }
            }
        }

        // PR 6: 抽 6 个 chunk-level layout 字段（用 ChunkLayoutMetadata reader 复用三态兼容）
        VectorChunk metaWrapper = new VectorChunk();
        metaWrapper.setMetadata(metaMap != null ? metaMap : Map.of());

        return RetrievedChunk.builder()
                .id(id)
                .text(content)
                .score(score)
                .kbId(kbId)
                .securityLevel(securityLevel)
                .docId(docId)
                .chunkIndex(chunkIndex)
                .pageNumber(ChunkLayoutMetadata.pageNumber(metaWrapper))
                .pageStart(ChunkLayoutMetadata.pageStart(metaWrapper))
                .pageEnd(ChunkLayoutMetadata.pageEnd(metaWrapper))
                .headingPath(ChunkLayoutMetadata.headingPath(metaWrapper))
                .blockType(ChunkLayoutMetadata.blockType(metaWrapper))
                .sourceBlockIds(ChunkLayoutMetadata.sourceBlockIds(metaWrapper))
                .build();
    }

    /** Test seam — exposes metadata-extraction logic without OS / Spring (PR 6 Task 17). */
    static RetrievedChunk toRetrievedChunkForTest(Map<String, Object> metaMap, String id, String text, Float score) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setText(text);
        chunk.setScore(score);

        Object kbId = metaMap.get("kb_id");
        if (kbId != null) chunk.setKbId(kbId.toString());
        Object docId = metaMap.get("doc_id");
        if (docId != null) chunk.setDocId(docId.toString());
        Object chunkIndex = metaMap.get("chunk_index");
        if (chunkIndex instanceof Number n) chunk.setChunkIndex(n.intValue());
        Object securityLevel = metaMap.get("security_level");
        if (securityLevel instanceof Number n) chunk.setSecurityLevel(n.intValue());

        VectorChunk metaWrapper = new VectorChunk();
        metaWrapper.setMetadata(metaMap);
        chunk.setPageNumber(ChunkLayoutMetadata.pageNumber(metaWrapper));
        chunk.setPageStart(ChunkLayoutMetadata.pageStart(metaWrapper));
        chunk.setPageEnd(ChunkLayoutMetadata.pageEnd(metaWrapper));
        chunk.setHeadingPath(ChunkLayoutMetadata.headingPath(metaWrapper));
        chunk.setBlockType(ChunkLayoutMetadata.blockType(metaWrapper));
        chunk.setSourceBlockIds(ChunkLayoutMetadata.sourceBlockIds(metaWrapper));
        return chunk;
    }

    private String floatArrayToJson(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
