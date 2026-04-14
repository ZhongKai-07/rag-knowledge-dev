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

package com.nageoffer.ai.ragent.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.vo.RagEvaluationRecordVO;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RAG 评测记录查询接口
 */
@RestController
@RequiredArgsConstructor
public class RagEvaluationController {

    private final RagEvaluationService evaluationService;

    /**
     * 分页查询评测记录
     */
    @GetMapping("/rag/evaluations")
    public Result<IPage<RagEvaluationRecordVO>> page(
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "evalStatus", required = false) String evalStatus) {
        return Results.success(evaluationService.pageQuery(current, size, evalStatus));
    }

    /**
     * 查看单条评测详情
     */
    @GetMapping("/rag/evaluations/{id}")
    public Result<RagEvaluationRecordVO> detail(@PathVariable("id") String id) {
        return Results.success(evaluationService.detail(id));
    }

    /**
     * 导出 RAGAS 兼容 JSON
     */
    @GetMapping("/rag/evaluations/export")
    public Result<List<RagEvaluationService.RagasExportItem>> exportForRagas(
            @RequestParam(value = "evalStatus", required = false) String evalStatus,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return Results.success(evaluationService.exportForRagas(evalStatus, limit));
    }

    /**
     * 回填评测结果（由外部评测工具调用）
     */
    @PutMapping("/rag/evaluations/{id}/metrics")
    public Result<Void> updateMetrics(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        evaluationService.updateMetrics(id, body.get("evalMetrics"));
        return Results.success();
    }
}
