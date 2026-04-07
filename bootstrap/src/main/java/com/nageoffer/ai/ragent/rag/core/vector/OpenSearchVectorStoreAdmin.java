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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.rag.config.OpenSearchProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchVectorStoreAdmin implements VectorStoreAdmin {

    private static final String PIPELINE_NAME = "ragent-hybrid-search-pipeline";

    private final OpenSearchClient client;
    private final RAGDefaultProperties ragDefaultProperties;
    private final OpenSearchProperties openSearchProperties;

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
                    log.info("Hybrid search pipeline detected on startup: {}", PIPELINE_NAME);
                }
            }
        } catch (Exception e) {
            log.warn("Could not check pipeline on startup, hybrid search disabled until first ingestion");
        }
    }

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String indexName = spec.getSpaceId().getLogicalName();

        try {
            boolean exists = client.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                log.debug("OpenSearch index already exists: {}", indexName);
                return;
            }

            ensurePipelineExists();

            String analyzer = detectAnalyzer();
            String searchAnalyzer = detectSearchAnalyzer();
            int dimension = ragDefaultProperties.getDimension();

            String requestBody = buildCreateIndexJson(analyzer, searchAnalyzer, dimension);

            try (var response = client.generic().execute(
                    Requests.builder()
                            .method("PUT")
                            .endpoint(indexName)
                            .json(requestBody)
                            .build())) {
                if (response.getStatus() >= 300) {
                    throw new RuntimeException("OpenSearch returned status " + response.getStatus()
                            + " when creating index: " + indexName);
                }
            }

            log.info("Created OpenSearch index: {}, dimension: {}, analyzer: {}", indexName, dimension, analyzer);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create OpenSearch index: " + indexName, e);
        }
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            return client.indices().exists(e -> e.index(spaceId.getLogicalName())).value();
        } catch (Exception e) {
            log.error("Failed to check OpenSearch index existence: {}", spaceId.getLogicalName(), e);
            return false;
        }
    }

    public boolean isPipelineReady() {
        return pipelineReady;
    }

    private void ensurePipelineExists() {
        try {
            try (var response = client.generic().execute(
                    Requests.builder()
                            .method("GET")
                            .endpoint("_search/pipeline/" + PIPELINE_NAME)
                            .build())) {
                if (response.getStatus() == 200) {
                    pipelineReady = true;
                    log.debug("Search pipeline already exists: {}", PIPELINE_NAME);
                    return;
                }
            }
        } catch (Exception ignored) {
            // Pipeline doesn't exist, fall through to create it
        }

        try {
            double vectorWeight = openSearchProperties.getHybrid().getVectorWeight();
            double textWeight = openSearchProperties.getHybrid().getTextWeight();

            String pipelineBody = """
                    {
                      "description": "RAGent hybrid search normalization pipeline",
                      "phase_results_processors": [
                        {
                          "normalization-processor": {
                            "normalization": { "technique": "min_max" },
                            "combination": {
                              "technique": "arithmetic_mean",
                              "parameters": { "weights": [%s, %s] }
                            }
                          }
                        }
                      ]
                    }
                    """.formatted(vectorWeight, textWeight);

            try (var response = client.generic().execute(
                    Requests.builder()
                            .method("PUT")
                            .endpoint("_search/pipeline/" + PIPELINE_NAME)
                            .json(pipelineBody)
                            .build())) {
                if (response.getStatus() >= 300) {
                    log.error("Failed to create search pipeline, status: {}. Hybrid query will be unavailable. Falling back to pure knn.", response.getStatus());
                    pipelineReady = false;
                    return;
                }
            }

            pipelineReady = true;
            log.info("Created search pipeline: {}", PIPELINE_NAME);
        } catch (Exception e) {
            pipelineReady = false;
            log.error("Failed to create search pipeline, hybrid query will be unavailable. Falling back to pure knn.", e);
        }
    }

    private String detectAnalyzer() {
        String configured = openSearchProperties.getAnalyzer().getDefaultAnalyzer();
        try {
            client.indices().analyze(a -> a.text("测试").analyzer(configured));
            return configured;
        } catch (Exception e) {
            log.warn("Analyzer '{}' not available, falling back to 'standard'. Install IK plugin for better Chinese search.", configured);
            return "standard";
        }
    }

    private String detectSearchAnalyzer() {
        String configured = openSearchProperties.getAnalyzer().getSearchAnalyzer();
        try {
            client.indices().analyze(a -> a.text("测试").analyzer(configured));
            return configured;
        } catch (Exception e) {
            return "standard";
        }
    }

    private String buildCreateIndexJson(String analyzer, String searchAnalyzer, int dimension) {
        String defaultPipelineJson = pipelineReady
                ? "\"index.search.default_pipeline\": \"" + PIPELINE_NAME + "\","
                : "";
        return """
                {
                  "settings": {
                    %s
                    "index.knn": true,
                    "number_of_shards": "1",
                    "number_of_replicas": "0"
                  },
                  "mappings": %s
                }
                """.formatted(defaultPipelineJson, buildMappingJson(analyzer, searchAnalyzer, dimension));
    }

    private String buildMappingJson(String analyzer, String searchAnalyzer, int dimension) {
        return """
                {
                  "dynamic": false,
                  "properties": {
                    "id": { "type": "keyword" },
                    "content": { "type": "text", "analyzer": "%s", "search_analyzer": "%s" },
                    "embedding": {
                      "type": "knn_vector",
                      "dimension": %d,
                      "method": {
                        "name": "hnsw",
                        "space_type": "cosinesimil",
                        "engine": "lucene",
                        "parameters": { "ef_construction": 200, "m": 48 }
                      }
                    },
                    "metadata": {
                      "dynamic": false,
                      "properties": {
                        "doc_id": { "type": "keyword" },
                        "chunk_index": { "type": "integer" },
                        "task_id": { "type": "keyword", "index": false },
                        "pipeline_id": { "type": "keyword" },
                        "source_type": { "type": "keyword" },
                        "source_location": { "type": "keyword", "index": false },
                        "keywords": {
                          "type": "text",
                          "analyzer": "%s",
                          "fields": { "raw": { "type": "keyword" } }
                        },
                        "summary": { "type": "text", "analyzer": "%s" }
                      }
                    }
                  }
                }
                """.formatted(analyzer, searchAnalyzer, dimension, searchAnalyzer, analyzer);
    }
}
