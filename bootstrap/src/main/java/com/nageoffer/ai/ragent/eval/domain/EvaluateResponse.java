/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package com.nageoffer.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * eval 域 - Python /evaluate 响应体（spec §7.3）。
 *
 * <p>4 metric 字段在单条评估失败时为 null（Python 侧不抛异常，整批错误体现在 error 字段）。
 */
public record EvaluateResponse(List<MetricResult> results) {

    public record MetricResult(
            @JsonProperty("gold_item_id") String goldItemId,
            BigDecimal faithfulness,
            @JsonProperty("answer_relevancy") BigDecimal answerRelevancy,
            @JsonProperty("context_precision") BigDecimal contextPrecision,
            @JsonProperty("context_recall") BigDecimal contextRecall,
            String error
    ) {
    }
}
