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

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "opensearch")
public class OpenSearchVectorStoreService implements VectorStoreService {

    private final OpenSearchClient client;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().index(collectionName);

            for (VectorChunk chunk : chunks) {
                String chunkId = chunk.getChunkId();
                Map<String, Object> doc = buildDocument(collectionName, docId, chunk);

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .id(chunkId)
                                .document(doc)));
            }

            BulkResponse response = client.bulk(bulkBuilder.build());
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("Bulk index error for chunk {}: {}", item.id(), item.error().reason());
                    }
                }
                throw new RuntimeException("Bulk index had errors for doc: " + docId);
            }

            log.info("Indexed {} chunks for doc {} into index {}", chunks.size(), docId, collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to index chunks for doc: " + docId, e);
        }
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        try {
            Map<String, Object> doc = buildDocument(collectionName, docId, chunk);
            client.index(i -> i
                    .index(collectionName)
                    .id(chunk.getChunkId())
                    .document(doc));
            log.debug("Updated chunk {} in index {}", chunk.getChunkId(), collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update chunk: " + chunk.getChunkId(), e);
        }
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId) {
        try {
            client.deleteByQuery(d -> d
                    .index(collectionName)
                    .query(q -> q
                            .term(t -> t
                                    .field("metadata.doc_id")
                                    .value(FieldValue.of(docId)))));
            log.info("Deleted vectors for doc {} from index {}", docId, collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete vectors for doc: " + docId, e);
        }
    }

    @Override
    public void deleteChunkById(String collectionName, String chunkId) {
        try {
            client.delete(d -> d.index(collectionName).id(chunkId));
            log.debug("Deleted chunk {} from index {}", chunkId, collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete chunk: " + chunkId, e);
        }
    }

    @Override
    public void deleteChunksByIds(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder().index(collectionName);
            for (String chunkId : chunkIds) {
                bulkBuilder.operations(op -> op
                        .delete(del -> del.id(chunkId)));
            }

            BulkResponse response = client.bulk(bulkBuilder.build());
            if (response.errors()) {
                log.warn("Bulk delete had errors for {} chunks in index {}", chunkIds.size(), collectionName);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to bulk delete chunks from index: " + collectionName, e);
        }
    }

    private Map<String, Object> buildDocument(String collectionName, String docId, VectorChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_id", docId);
        metadata.put("chunk_index", chunk.getIndex());

        if (chunk.getMetadata() != null) {
            chunk.getMetadata().forEach((k, v) -> {
                if (v != null) {
                    metadata.put(k, v);
                }
            });
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", chunk.getChunkId());
        doc.put("content", chunk.getContent() != null ? chunk.getContent() : "");

        if (chunk.getEmbedding() != null) {
            List<Float> embedding = new ArrayList<>(chunk.getEmbedding().length);
            for (float f : chunk.getEmbedding()) {
                embedding.add(f);
            }
            doc.put("embedding", embedding);
        }

        doc.put("metadata", metadata);
        return doc;
    }
}
