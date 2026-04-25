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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.AnswerResult;
import com.nageoffer.ai.ragent.rag.core.ChatForEvalService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EvalRunExecutorTest {

    @BeforeAll
    static void initMpTableInfo() {
        initTableInfo(EvalRunDO.class);
        initTableInfo(GoldItemDO.class);
        initTableInfo(EvalResultDO.class);
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    private EvalRunMapper runMapper;
    private GoldItemMapper itemMapper;
    private EvalResultMapper resultMapper;
    private ChatForEvalService chat;
    private RagasEvalClient ragas;
    private EvalProperties props;
    private EvalRunExecutor exec;

    @BeforeEach
    void setUp() {
        runMapper = mock(EvalRunMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        resultMapper = mock(EvalResultMapper.class);
        chat = mock(ChatForEvalService.class);
        ragas = mock(RagasEvalClient.class);
        props = new EvalProperties();
        props.getRun().setEvaluateBatchSize(2);
        ObjectMapper objectMapper = new ObjectMapper();
        exec = new EvalRunExecutor(objectMapper, runMapper, itemMapper, resultMapper, chat, ragas, props);
    }

    private GoldItemDO item(String id, String q) {
        return GoldItemDO.builder().id(id).datasetId("d1").question(q).groundTruthAnswer("gt-" + id).reviewStatus("APPROVED").build();
    }

    private RetrievedChunk chunk(String id) {
        RetrievedChunk c = new RetrievedChunk();
        c.setId(id);
        c.setText("text-" + id);
        return c;
    }

    @Test
    void all_success_marks_run_SUCCESS() {
        EvalRunDO run = EvalRunDO.builder().id("run-1").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-1")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));
        when(chat.chatForEval(any(), any(), any())).thenReturn(AnswerResult.success("ans", List.of(chunk("c1"))));

        when(ragas.evaluate(eq("run-1"), any())).thenAnswer(inv -> {
            EvaluateRequest req = inv.getArgument(1);
            List<EvaluateResponse.MetricResult> mrs = req.items().stream()
                    .map(it -> new EvaluateResponse.MetricResult(it.resultId(),
                            new BigDecimal("0.9"), new BigDecimal("0.8"),
                            new BigDecimal("0.7"), new BigDecimal("0.6"), null))
                    .toList();
            return new EvaluateResponse(mrs);
        });
        when(resultMapper.selectList(any())).thenReturn(List.of(
                EvalResultDO.builder().faithfulness(new BigDecimal("0.9"))
                        .answerRelevancy(new BigDecimal("0.8"))
                        .contextPrecision(new BigDecimal("0.7"))
                        .contextRecall(new BigDecimal("0.6")).build(),
                EvalResultDO.builder().faithfulness(new BigDecimal("0.9"))
                        .answerRelevancy(new BigDecimal("0.8"))
                        .contextPrecision(new BigDecimal("0.7"))
                        .contextRecall(new BigDecimal("0.6")).build()));

        exec.runInternal("run-1");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("SUCCESS");
        assertThat(last.getSucceededItems()).isEqualTo(2);
        assertThat(last.getFailedItems()).isEqualTo(0);
    }

    @Test
    void all_failed_marks_run_FAILED() {
        EvalRunDO run = EvalRunDO.builder().id("run-2").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-2")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));
        when(chat.chatForEval(any(), any(), any())).thenThrow(new RuntimeException("LLM 503"));

        exec.runInternal("run-2");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("FAILED");
        assertThat(last.getSucceededItems()).isEqualTo(0);
        assertThat(last.getFailedItems()).isEqualTo(2);
    }

    @Test
    void rag_success_but_python_evaluate_fails_counts_as_failed() {
        EvalRunDO run = EvalRunDO.builder().id("run-eval-fail").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-eval-fail")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));
        when(chat.chatForEval(any(), any(), any())).thenReturn(AnswerResult.success("ans", List.of(chunk("c1"))));
        when(ragas.evaluate(any(), any())).thenThrow(new RuntimeException("python 503"));
        when(resultMapper.selectList(any())).thenReturn(List.of());

        exec.runInternal("run-eval-fail");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("FAILED");
        assertThat(last.getSucceededItems()).isEqualTo(0);
        assertThat(last.getFailedItems()).isEqualTo(2);
    }

    @Test
    void python_per_item_error_counts_as_failed() {
        EvalRunDO run = EvalRunDO.builder().id("run-per-item").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-per-item")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));
        when(chat.chatForEval(any(), any(), any())).thenReturn(AnswerResult.success("ans", List.of(chunk("c1"))));

        when(ragas.evaluate(any(), any())).thenAnswer(inv -> {
            EvaluateRequest req = inv.getArgument(1);
            List<EvaluateResponse.MetricResult> mrs = new ArrayList<>();
            mrs.add(new EvaluateResponse.MetricResult(req.items().get(0).resultId(),
                    new BigDecimal("0.9"), new BigDecimal("0.8"),
                    new BigDecimal("0.7"), new BigDecimal("0.6"), null));
            mrs.add(new EvaluateResponse.MetricResult(req.items().get(1).resultId(),
                    null, null, null, null, "openai 429"));
            return new EvaluateResponse(mrs);
        });
        when(resultMapper.selectList(any())).thenReturn(List.of());

        exec.runInternal("run-per-item");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(last.getSucceededItems()).isEqualTo(1);
        assertThat(last.getFailedItems()).isEqualTo(1);
    }

    @Test
    void mixed_outcome_marks_run_PARTIAL_SUCCESS() {
        EvalRunDO run = EvalRunDO.builder().id("run-3").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-3")).thenReturn(run);
        when(itemMapper.selectList(any())).thenReturn(List.of(item("i1", "q1"), item("i2", "q2")));

        when(chat.chatForEval(any(), any(), eq("q1"))).thenReturn(AnswerResult.success("a1", List.of(chunk("c1"))));
        when(chat.chatForEval(any(), any(), eq("q2"))).thenReturn(AnswerResult.emptyContext());
        when(ragas.evaluate(eq("run-3"), any())).thenAnswer(inv -> {
            EvaluateRequest req = inv.getArgument(1);
            List<EvaluateResponse.MetricResult> mrs = req.items().stream()
                    .map(it -> new EvaluateResponse.MetricResult(it.resultId(),
                            new BigDecimal("0.9"), new BigDecimal("0.8"),
                            new BigDecimal("0.7"), new BigDecimal("0.6"), null))
                    .toList();
            return new EvaluateResponse(mrs);
        });
        when(resultMapper.selectList(any())).thenReturn(List.of());

        exec.runInternal("run-3");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(last.getSucceededItems()).isEqualTo(1);
        assertThat(last.getFailedItems()).isEqualTo(1);
    }

    @Test
    void uncaught_exception_in_run_marks_status_FAILED_via_recovery() {
        // 模拟收尾阶段抛 (e.g., computeMetricsSummary 选库异常)；外层 catch 必须把
        // status 标 FAILED 否则 t_eval_run 卡 RUNNING 永远不释放 max-parallel-runs
        EvalRunDO run = EvalRunDO.builder().id("run-crash").datasetId("d1").kbId("kb1").build();
        when(runMapper.selectById("run-crash")).thenReturn(run);
        when(itemMapper.selectList(any())).thenThrow(new RuntimeException("DB connection lost"));

        // 触发 (不应抛到调用方)
        exec.runInternal("run-crash");

        ArgumentCaptor<EvalRunDO> upd = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper, atLeast(2)).updateById(upd.capture());
        EvalRunDO last = upd.getAllValues().get(upd.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo("FAILED");
        assertThat(last.getErrorMessage()).contains("DB connection lost");
    }
}
