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

package com.nageoffer.ai.ragent.eval.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.controller.request.StartRunRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalResultSummaryVO;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalResultVO;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalRunSummaryVO;
import com.nageoffer.ai.ragent.eval.controller.vo.EvalRunVO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalResultDO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalResultMapper;
import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import com.nageoffer.ai.ragent.eval.service.EvalResultRedactionService;
import com.nageoffer.ai.ragent.eval.service.EvalRunService;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * eval 域 - 评测运行 REST 入口。
 *
 * <p>类级 {@link SaCheckRole} 锁定 SUPER_ADMIN（spec §15.1）。
 * EVAL-3 redaction：所有返回 retrieved_chunks 的端点必经 {@link EvalResultRedactionService}。
 */
@RestController
@RequestMapping("/admin/eval/runs")
@SaCheckRole("SUPER_ADMIN")
@RequiredArgsConstructor
public class EvalRunController {

    private final EvalRunService runService;
    private final EvalResultMapper resultMapper;
    private final EvalResultRedactionService redaction;

    @PostMapping
    public Result<String> startRun(@RequestBody StartRunRequest req) {
        String runId = runService.startRun(req.datasetId(), UserContext.getUserId());
        return Results.success(runId);
    }

    @GetMapping
    public Result<List<EvalRunSummaryVO>> listRuns(@RequestParam String datasetId) {
        List<EvalRunDO> runs = runService.listRuns(datasetId);
        return Results.success(runs.stream().map(EvalRunController::toSummary).toList());
    }

    @GetMapping("/{runId}")
    public Result<EvalRunVO> getRun(@PathVariable String runId) {
        EvalRunDO run = runService.getRun(runId);
        if (run == null) return Results.success(null);
        return Results.success(toDetail(run));
    }

    @GetMapping("/{runId}/results")
    public Result<List<EvalResultSummaryVO>> listResults(@PathVariable String runId) {
        // P1-3: list 端点 .select() projection 不拉 retrieved_chunks
        List<EvalResultDO> rows = resultMapper.selectList(new LambdaQueryWrapper<EvalResultDO>()
                .select(EvalResultDO::getId, EvalResultDO::getGoldItemId,
                        EvalResultDO::getQuestion,
                        EvalResultDO::getFaithfulness, EvalResultDO::getAnswerRelevancy,
                        EvalResultDO::getContextPrecision, EvalResultDO::getContextRecall,
                        EvalResultDO::getError, EvalResultDO::getElapsedMs)
                .eq(EvalResultDO::getRunId, runId));
        return Results.success(rows.stream().map(EvalRunController::toSummaryResult).toList());
    }

    @GetMapping("/{runId}/results/{resultId}")
    public Result<EvalResultVO> getResult(@PathVariable String runId, @PathVariable String resultId) {
        // P2 (round-2 review): runId MUST participate in query
        EvalResultDO r = resultMapper.selectOne(new LambdaQueryWrapper<EvalResultDO>()
                .eq(EvalResultDO::getId, resultId)
                .eq(EvalResultDO::getRunId, runId));
        if (r == null) return Results.success(null);
        // EVAL-3 ceiling: SUPER_ADMIN-only → MAX_VALUE; controller 不知 retrieved_chunks JSON 格式
        List<RetrievedChunkSnapshot> redacted =
                redaction.redactFromJson(r.getRetrievedChunks(), Integer.MAX_VALUE);
        return Results.success(new EvalResultVO(r.getId(), r.getRunId(), r.getGoldItemId(),
                r.getQuestion(), r.getGroundTruthAnswer(), r.getSystemAnswer(),
                redacted, r.getFaithfulness(), r.getAnswerRelevancy(),
                r.getContextPrecision(), r.getContextRecall(),
                r.getError(), r.getElapsedMs()));
    }

    private static EvalResultSummaryVO toSummaryResult(EvalResultDO r) {
        return new EvalResultSummaryVO(r.getId(), r.getGoldItemId(), r.getQuestion(),
                r.getFaithfulness(), r.getAnswerRelevancy(),
                r.getContextPrecision(), r.getContextRecall(),
                r.getError(), r.getElapsedMs());
    }

    private static EvalRunSummaryVO toSummary(EvalRunDO r) {
        return new EvalRunSummaryVO(r.getId(), r.getDatasetId(), r.getKbId(), r.getStatus(),
                r.getTotalItems(), r.getSucceededItems(), r.getFailedItems(),
                r.getMetricsSummary(), r.getStartedAt(), r.getFinishedAt(), r.getCreateTime());
    }

    private static EvalRunVO toDetail(EvalRunDO r) {
        return new EvalRunVO(r.getId(), r.getDatasetId(), r.getKbId(), r.getTriggeredBy(),
                r.getStatus(), r.getTotalItems(), r.getSucceededItems(), r.getFailedItems(),
                r.getMetricsSummary(), r.getSystemSnapshot(), r.getEvaluatorLlm(),
                r.getErrorMessage(), r.getStartedAt(), r.getFinishedAt(), r.getCreateTime());
    }
}
