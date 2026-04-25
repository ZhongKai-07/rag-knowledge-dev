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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalResultDO;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalResultMapper;
import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import com.nageoffer.ai.ragent.eval.service.EvalResultRedactionService;
import com.nageoffer.ai.ragent.eval.service.EvalRunService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EVAL-3 hard merge gate verification (review P2-2)。
 *
 * <p>三条结构性断言：
 * <ol>
 *   <li>drill-down 端点 MUST 经过 {@link EvalResultRedactionService} —— sentinel 文本回显证明 passthrough</li>
 *   <li>list / detail 端点结构上不暴露 retrievedChunks（contract-level 闸门）</li>
 *   <li>drill-down 在 runId 不匹配时返回 null 且 redaction 从未被调</li>
 * </ol>
 *
 * <p>使用 {@link MockMvcBuilders#standaloneSetup} 绕过 Sa-Token 拦截器 ——
 * 与项目既有 controller 测试模式一致（参见 {@code KnowledgeBaseControllerScopeTest}）。
 * 该测试聚焦 EVAL-3 redaction 硬合并门禁，不验证授权链路。
 */
class EvalRunControllerRedactionTest {

    @BeforeAll
    static void initMpTableInfo() {
        // LambdaQueryWrapper.select(...) 走 method-handle 反射，需要 MyBatis-Plus TableInfo cache，
        // 否则抛 "can not find lambda cache for this entity"。沿用项目既有 Mockito 测试约定。
        initTableInfo(EvalResultDO.class);
        initTableInfo(EvalRunDO.class);
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    private EvalRunService runService;
    private EvalResultMapper resultMapper;
    private EvalResultRedactionService redaction;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runService = mock(EvalRunService.class);
        resultMapper = mock(EvalResultMapper.class);
        redaction = mock(EvalResultRedactionService.class);
        EvalRunController controller = new EvalRunController(runService, resultMapper, redaction);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void drilldown_endpoint_invokes_redaction_service_with_correct_args() throws Exception {
        EvalResultDO row = EvalResultDO.builder()
                .id("r1")
                .runId("run-1")
                .goldItemId("g1")
                .question("q")
                .groundTruthAnswer("gt")
                .systemAnswer("a")
                .retrievedChunks(
                        "[{\"chunk_id\":\"c1\",\"doc_id\":\"d1\",\"doc_name\":\"public.pdf\","
                                + "\"security_level\":0,\"text\":\"public text\",\"score\":0.9},"
                                + "{\"chunk_id\":\"c2\",\"doc_id\":\"d2\",\"doc_name\":\"secret.pdf\","
                                + "\"security_level\":3,\"text\":\"secret text\",\"score\":0.8}]")
                .build();
        when(resultMapper.selectOne(any())).thenReturn(row);
        when(redaction.redact(any(), anyInt())).thenReturn(List.of(
                new RetrievedChunkSnapshot("c1", "d1", "public.pdf", 0, "REDACTION_SENTINEL_TEXT", 0.9)));

        mvc.perform(get("/admin/eval/runs/run-1/results/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievedChunks[0].text").value("REDACTION_SENTINEL_TEXT"));

        verify(redaction, atLeastOnce()).redact(any(), anyInt());
    }

    @Test
    void drilldown_endpoint_returns_null_when_runId_does_not_match() throws Exception {
        when(resultMapper.selectOne(any())).thenReturn(null);

        mvc.perform(get("/admin/eval/runs/wrong-run/results/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(redaction, never()).redact(any(), anyInt());
    }

    @Test
    void list_results_endpoint_does_not_expose_retrieved_chunks() throws Exception {
        EvalResultDO row = EvalResultDO.builder()
                .id("r1")
                .runId("run-1")
                .goldItemId("g1")
                .question("q")
                .build();
        when(resultMapper.selectList(any())).thenReturn(List.of(row));

        mvc.perform(get("/admin/eval/runs/run-1/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].retrievedChunks").doesNotExist())
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].question").exists());
    }

    @Test
    void list_runs_endpoint_does_not_expose_retrieved_chunks() throws Exception {
        when(runService.listRuns(any())).thenReturn(List.of(EvalRunDO.builder().id("run-1").build()));

        mvc.perform(get("/admin/eval/runs?datasetId=d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].retrievedChunks").doesNotExist());
    }

    @Test
    void get_run_detail_endpoint_does_not_expose_retrieved_chunks() throws Exception {
        when(runService.getRun(any())).thenReturn(EvalRunDO.builder().id("run-1").build());

        mvc.perform(get("/admin/eval/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievedChunks").doesNotExist());
    }
}
