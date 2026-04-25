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

package com.nageoffer.ai.ragent.eval.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.eval.client.RagasEvalClient;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalResultDO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalResultMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.domain.EvaluateRequest;
import com.nageoffer.ai.ragent.eval.domain.EvaluateResponse;
import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.rag.core.AnswerResult;
import com.nageoffer.ai.ragent.rag.core.ChatForEvalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * eval 域 - 评测运行执行器（spec §6.2）。
 *
 * <p>在 evalExecutor 线程跑：for-each gold item → ChatForEvalService → 累积 batch
 * → 满 evaluateBatchSize 调 Python /evaluate → 落 t_eval_result。
 *
 * <p><b>系统级 AccessScope.all() 仅限 SUPER_ADMIN 手动触发离线评估场景</b>（spec §15.3）；
 * 扩展到任何其他场景前必须重新做权限模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunExecutor {

    private final ObjectMapper objectMapper;
    private final EvalRunMapper runMapper;
    private final GoldItemMapper itemMapper;
    private final EvalResultMapper resultMapper;
    private final ChatForEvalService chatForEvalService;
    private final RagasEvalClient ragasClient;
    private final EvalProperties props;

    public void runInternal(String runId) {
        MDC.put("evalRunId", runId);
        try {
            EvalRunDO run = runMapper.selectById(runId);
            if (run == null) {
                log.error("[eval-run] runId={} not found, abort", runId);
                return;
            }
            run.setStatus("RUNNING");
            run.setStartedAt(new Date());
            runMapper.updateById(run);

            List<GoldItemDO> items = itemMapper.selectList(new LambdaQueryWrapper<GoldItemDO>()
                    .eq(GoldItemDO::getDatasetId, run.getDatasetId())
                    .eq(GoldItemDO::getReviewStatus, "APPROVED"));

            int batchSize = Math.max(1, props.getRun().getEvaluateBatchSize());
            List<PendingResult> pending = new ArrayList<>();
            int succeeded = 0;
            int failed = 0;
            // P1-1: executor 是唯一合法持有 AccessScope.all() 的调用者
            AccessScope systemScope = AccessScope.all();

            for (GoldItemDO item : items) {
                long t0 = System.currentTimeMillis();
                AnswerResult ar;
                try {
                    ar = chatForEvalService.chatForEval(systemScope, run.getKbId(), item.getQuestion());
                } catch (Exception e) {
                    log.warn("[eval-run] runId={} chatForEval failed itemId={}", runId, item.getId(), e);
                    insertFailedResult(runId, item, "chatForEval: " + e.getMessage(),
                            (int) (System.currentTimeMillis() - t0));
                    failed++;
                    continue;
                }

                if (!(ar instanceof AnswerResult.Success success)) {
                    String reason = ar.getClass().getSimpleName();
                    insertFailedResult(runId, item, "skipped: " + reason,
                            (int) (System.currentTimeMillis() - t0));
                    failed++;
                    continue;
                }

                List<RetrievedChunkSnapshot> snaps = success.chunks().stream()
                        .map(EvalRunExecutor::toSnapshot)
                        .toList();

                EvalResultDO row = EvalResultDO.builder()
                        .id(IdUtil.getSnowflakeNextIdStr())
                        .runId(runId)
                        .goldItemId(item.getId())
                        .question(item.getQuestion())
                        .groundTruthAnswer(item.getGroundTruthAnswer())
                        .systemAnswer(success.answer())
                        .retrievedChunks(toJson(snaps))
                        .elapsedMs((int) (System.currentTimeMillis() - t0))
                        .build();
                resultMapper.insert(row);

                pending.add(new PendingResult(row.getId(), item.getId(), item.getQuestion(),
                        item.getGroundTruthAnswer(), success.answer(),
                        snaps.stream().map(RetrievedChunkSnapshot::text).toList()));

                if (pending.size() >= batchSize) {
                    BatchOutcome out = flushBatch(runId, pending);
                    succeeded += out.succeeded();
                    failed += out.failed();
                    pending.clear();
                }
            }
            if (!pending.isEmpty()) {
                BatchOutcome out = flushBatch(runId, pending);
                succeeded += out.succeeded();
                failed += out.failed();
                pending.clear();
            }

            run.setSucceededItems(succeeded);
            run.setFailedItems(failed);
            run.setMetricsSummary(computeMetricsSummary(runId));
            run.setFinishedAt(new Date());
            run.setStatus(decideStatus(succeeded, failed));
            runMapper.updateById(run);

            log.info("[eval-run] runId={} status={} succeeded={} failed={}",
                    runId, run.getStatus(), succeeded, failed);
        } finally {
            MDC.remove("evalRunId");
        }
    }

    /**
     * P1-2 三类失败拆分：
     * 1) HTTP 整批失败（catch 块）→ 整批 failed
     * 2) per-item error 非空 → 该条 failed
     * 3) per-item missing → 该条 failed
     */
    private BatchOutcome flushBatch(String runId, List<PendingResult> batch) {
        try {
            List<EvaluateRequest.Item> items = batch.stream()
                    .map(p -> new EvaluateRequest.Item(p.resultId, p.question, p.contexts, p.answer, p.groundTruth))
                    .toList();
            EvaluateResponse resp = ragasClient.evaluate(runId, new EvaluateRequest(items));
            Map<String, EvaluateResponse.MetricResult> byId = new HashMap<>();
            for (EvaluateResponse.MetricResult mr : resp.results()) {
                byId.put(mr.resultId(), mr);
            }
            int succ = 0;
            int fail = 0;
            for (PendingResult p : batch) {
                EvaluateResponse.MetricResult mr = byId.get(p.resultId);
                EvalResultDO upd = new EvalResultDO();
                upd.setId(p.resultId);
                if (mr == null) {
                    upd.setError("python returned no result for resultId=" + p.resultId);
                    fail++;
                } else if (mr.error() != null) {
                    upd.setError(mr.error());
                    fail++;
                } else {
                    upd.setFaithfulness(mr.faithfulness());
                    upd.setAnswerRelevancy(mr.answerRelevancy());
                    upd.setContextPrecision(mr.contextPrecision());
                    upd.setContextRecall(mr.contextRecall());
                    succ++;
                }
                resultMapper.updateById(upd);
            }
            return new BatchOutcome(succ, fail);
        } catch (Exception e) {
            log.warn("[eval-run] runId={} batch HTTP failed size={}", runId, batch.size(), e);
            for (PendingResult p : batch) {
                EvalResultDO upd = new EvalResultDO();
                upd.setId(p.resultId);
                upd.setError("evaluate batch failed: " + e.getMessage());
                resultMapper.updateById(upd);
            }
            return new BatchOutcome(0, batch.size());
        }
    }

    private record BatchOutcome(int succeeded, int failed) {}

    private void insertFailedResult(String runId, GoldItemDO item, String error, int elapsedMs) {
        EvalResultDO row = EvalResultDO.builder()
                .id(IdUtil.getSnowflakeNextIdStr())
                .runId(runId)
                .goldItemId(item.getId())
                .question(item.getQuestion())
                .groundTruthAnswer(item.getGroundTruthAnswer())
                .error(error)
                .elapsedMs(elapsedMs)
                .build();
        resultMapper.insert(row);
    }

    private String computeMetricsSummary(String runId) {
        // .select() 投影：只拉 4 个 metric 列，避免读回 retrieved_chunks (TEXT，可能 MB 级)
        List<EvalResultDO> rows = resultMapper.selectList(new LambdaQueryWrapper<EvalResultDO>()
                .select(EvalResultDO::getFaithfulness, EvalResultDO::getAnswerRelevancy,
                        EvalResultDO::getContextPrecision, EvalResultDO::getContextRecall)
                .eq(EvalResultDO::getRunId, runId));
        BigDecimal[] sum = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int[] count = new int[4];
        for (EvalResultDO r : rows) {
            accumulate(sum, count, 0, r.getFaithfulness());
            accumulate(sum, count, 1, r.getAnswerRelevancy());
            accumulate(sum, count, 2, r.getContextPrecision());
            accumulate(sum, count, 3, r.getContextRecall());
        }
        Map<String, BigDecimal> avg = new HashMap<>();
        avg.put("faithfulness", count[0] == 0 ? null : sum[0].divide(BigDecimal.valueOf(count[0]), 4, RoundingMode.HALF_UP));
        avg.put("answer_relevancy", count[1] == 0 ? null : sum[1].divide(BigDecimal.valueOf(count[1]), 4, RoundingMode.HALF_UP));
        avg.put("context_precision", count[2] == 0 ? null : sum[2].divide(BigDecimal.valueOf(count[2]), 4, RoundingMode.HALF_UP));
        avg.put("context_recall", count[3] == 0 ? null : sum[3].divide(BigDecimal.valueOf(count[3]), 4, RoundingMode.HALF_UP));
        return toJson(avg);
    }

    private static void accumulate(BigDecimal[] sum, int[] count, int idx, BigDecimal v) {
        if (v != null) {
            sum[idx] = sum[idx].add(v);
            count[idx]++;
        }
    }

    private static String decideStatus(int succeeded, int failed) {
        if (failed == 0 && succeeded > 0) return "SUCCESS";
        if (succeeded == 0) return "FAILED";
        return "PARTIAL_SUCCESS";
    }

    /**
     * Pre-fix-A：RetrievedChunk 直接 getter；docName 不在 RetrievedChunk → 填 null。
     * Score 是 Float → doubleValue()。
     */
    private static RetrievedChunkSnapshot toSnapshot(RetrievedChunk c) {
        Double score = c.getScore() == null ? null : c.getScore().doubleValue();
        return new RetrievedChunkSnapshot(
                c.getId(),
                c.getDocId(),
                null,
                c.getSecurityLevel(),
                c.getText(),
                score);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    private record PendingResult(String resultId, String goldItemId, String question, String groundTruth,
                                 String answer, List<String> contexts) {}
}
