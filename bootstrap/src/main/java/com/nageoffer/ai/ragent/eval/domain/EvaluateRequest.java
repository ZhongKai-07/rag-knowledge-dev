/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * eval 域 - Python /evaluate 请求体（spec §7.3）。
 */
public record EvaluateRequest(List<Item> items) {

    public record Item(
            @JsonProperty("gold_item_id") String goldItemId,
            String question,
            List<String> contexts,
            String answer,
            @JsonProperty("ground_truth") String groundTruth
    ) {
    }
}
