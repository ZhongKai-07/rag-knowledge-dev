/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * eval 域 - {@code t_eval_result.retrieved_chunks} 的 JSON 数组元素。
 *
 * <p>{@code securityLevel} 是 EVAL-3 redaction 的关键字段——按调用 principal
 * 的 {@code maxSecurityLevel} 天花板做替换。
 */
public record RetrievedChunkSnapshot(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("doc_name") String docName,
        @JsonProperty("security_level") Integer securityLevel,
        String text,
        Double score
) {
}
