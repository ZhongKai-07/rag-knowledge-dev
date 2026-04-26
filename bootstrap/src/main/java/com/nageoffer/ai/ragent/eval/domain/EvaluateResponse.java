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
            @JsonProperty("result_id") String resultId,
            BigDecimal faithfulness,
            @JsonProperty("answer_relevancy") BigDecimal answerRelevancy,
            @JsonProperty("context_precision") BigDecimal contextPrecision,
            @JsonProperty("context_recall") BigDecimal contextRecall,
            String error
    ) {
    }
}
