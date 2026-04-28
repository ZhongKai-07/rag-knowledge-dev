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

package com.knowledgebase.ai.ragent.eval.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.knowledgebase.ai.ragent.eval.config.EvalProperties;
import com.knowledgebase.ai.ragent.eval.dao.entity.EvalRunDO;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldItemDO;
import com.knowledgebase.ai.ragent.eval.dao.mapper.EvalRunMapper;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.knowledgebase.ai.ragent.eval.service.EvalRunExecutor;
import com.knowledgebase.ai.ragent.eval.service.SystemSnapshotBuilder;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EvalRunServiceImplTest {

    @BeforeAll
    static void initMpTableInfo() {
        // 预热 MyBatis Plus lambda 缓存——否则 LambdaQueryWrapper::getSqlSegment 抛 MybatisPlusException
        initTableInfo(GoldDatasetDO.class);
        initTableInfo(GoldItemDO.class);
        initTableInfo(EvalRunDO.class);
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    private GoldDatasetMapper datasetMapper;
    private GoldItemMapper itemMapper;
    private EvalRunMapper runMapper;
    private SystemSnapshotBuilder snapshot;
    private EvalRunExecutor executor;
    private ThreadPoolTaskExecutor evalExecutor;
    private EvalRunServiceImpl svc;
    private EvalProperties props;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(GoldDatasetMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        runMapper = mock(EvalRunMapper.class);
        snapshot = mock(SystemSnapshotBuilder.class);
        executor = mock(EvalRunExecutor.class);
        evalExecutor = mock(ThreadPoolTaskExecutor.class);
        props = new EvalProperties();
        svc = new EvalRunServiceImpl(datasetMapper, itemMapper, runMapper, snapshot, executor, evalExecutor, props);
        when(snapshot.build()).thenReturn("{\"recall_top_k\":30}");
        when(runMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void startRun_rejects_nonActive_dataset() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);

        assertThatThrownBy(() -> svc.startRun("d1", "user-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("ACTIVE");
        verifyNoInteractions(runMapper, evalExecutor, executor);
    }

    @Test
    void startRun_rejects_dataset_with_zero_approved_items() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> svc.startRun("d1", "user-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void startRun_rejects_when_active_run_exceeds_max_parallel_runs() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(20L);
        when(runMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> svc.startRun("d1", "user-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("max-parallel-runs");
        verifyNoInteractions(evalExecutor, executor);
    }

    @Test
    void startRun_concurrent_calls_yield_at_most_one_insert() throws Exception {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(20L);

        // active 计数随 insert 递增——一旦第一个线程占坑后续读到 active=1 即拒绝
        AtomicLong active = new AtomicLong(0L);
        when(runMapper.selectCount(any())).thenAnswer(inv -> active.get());
        when(runMapper.insert(any(EvalRunDO.class))).thenAnswer(inv -> {
            active.incrementAndGet();
            return 1;
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    svc.startRun("d1", "user-1");
                    succeeded.incrementAndGet();
                } catch (ClientException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(7);
        verify(runMapper, times(1)).insert(any(EvalRunDO.class));
        verify(evalExecutor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void startRun_creates_run_with_snapshot_and_submits_to_executor() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(20L);

        ArgumentCaptor<EvalRunDO> runCap = ArgumentCaptor.forClass(EvalRunDO.class);
        when(runMapper.insert(runCap.capture())).thenReturn(1);

        String runId = svc.startRun("d1", "user-1");

        EvalRunDO inserted = runCap.getValue();
        assertThat(inserted.getDatasetId()).isEqualTo("d1");
        assertThat(inserted.getKbId()).isEqualTo("kb1");
        assertThat(inserted.getStatus()).isEqualTo("PENDING");
        assertThat(inserted.getTriggeredBy()).isEqualTo("user-1");
        assertThat(inserted.getTotalItems()).isEqualTo(20);
        assertThat(inserted.getSystemSnapshot()).contains("recall_top_k");
        assertThat(inserted.getCreatedBy()).isEqualTo("user-1");
        assertThat(inserted.getUpdatedBy()).isEqualTo("user-1");
        assertThat(runId).isEqualTo(inserted.getId());

        verify(evalExecutor).execute(any(Runnable.class));
    }

    @Test
    void startRun_marks_inserted_run_failed_when_executor_rejects_task() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").kbId("kb1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any())).thenReturn(20L);

        ArgumentCaptor<EvalRunDO> insertedCap = ArgumentCaptor.forClass(EvalRunDO.class);
        when(runMapper.insert(insertedCap.capture())).thenReturn(1);
        doThrow(new TaskRejectedException("queue full"))
                .when(evalExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> svc.startRun("d1", "user-1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("eval task rejected");

        ArgumentCaptor<EvalRunDO> failedCap = ArgumentCaptor.forClass(EvalRunDO.class);
        verify(runMapper).updateById(failedCap.capture());
        assertThat(failedCap.getValue().getId()).isEqualTo(insertedCap.getValue().getId());
        assertThat(failedCap.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(failedCap.getValue().getErrorMessage()).contains("queue full");
    }
}
