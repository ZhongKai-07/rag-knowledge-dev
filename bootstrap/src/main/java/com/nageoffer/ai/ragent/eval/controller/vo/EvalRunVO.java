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

import java.util.Date;

/**
 * Run 详情 VO（含 snapshot，无 retrieved_chunks）。
 */
public record EvalRunVO(
        String id,
        String datasetId,
        String kbId,
        String triggeredBy,
        String status,
        Integer totalItems,
        Integer succeededItems,
        Integer failedItems,
        String metricsSummary,
        String systemSnapshot,
        String evaluatorLlm,
        String errorMessage,
        Date startedAt,
        Date finishedAt,
        Date createTime
) {}
