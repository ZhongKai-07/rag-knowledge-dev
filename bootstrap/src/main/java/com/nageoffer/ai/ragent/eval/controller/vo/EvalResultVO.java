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

package com.nageoffer.ai.ragent.eval.controller.vo;

import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单条 result drill-down VO（review P1-3）。
 *
 * <p>仅 GET /admin/eval/runs/{runId}/results/{resultId} 单条端点返回此 VO；
 * list / 趋势 / 列表 result 端点都不暴露 retrievedChunks。
 */
public record EvalResultVO(
        String id,
        String runId,
        String goldItemId,
        String question,
        String groundTruthAnswer,
        String systemAnswer,
        List<RetrievedChunkSnapshot> retrievedChunks,
        BigDecimal faithfulness,
        BigDecimal answerRelevancy,
        BigDecimal contextPrecision,
        BigDecimal contextRecall,
        String error,
        Integer elapsedMs
) {}
