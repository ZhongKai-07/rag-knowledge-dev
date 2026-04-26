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

package com.nageoffer.ai.ragent.eval.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.config.EvalProperties;
import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.EvalRunMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.service.EvalRunExecutor;
import com.nageoffer.ai.ragent.eval.service.EvalRunService;
import com.nageoffer.ai.ragent.eval.service.SystemSnapshotBuilder;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * eval 域 - EvalRunService 实现（spec §5 / review P1-4）。
 *
 * <p>编排 startRun = 三段流程（dataset 校验 + count + insert + 提交执行体），
 * 不直接执行 chat / RAGAS。下游执行体由 {@link EvalRunExecutor} 注入，
 * Task 9 落地真实实现，单测以 mock 替换。
 */
@Slf4j
@Service
public class EvalRunServiceImpl implements EvalRunService {

    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "RUNNING");

    private final GoldDatasetMapper datasetMapper;
    private final GoldItemMapper itemMapper;
    private final EvalRunMapper runMapper;
    private final SystemSnapshotBuilder snapshotBuilder;
    private final EvalRunExecutor runExecutor;
    private final ThreadPoolTaskExecutor evalExecutor;
    private final EvalProperties evalProps;

    /**
     * P1-4 硬 enforce：包住 SELECT COUNT + INSERT + executor.execute 三步。
     * 单 JVM 内排他互斥 max-parallel-runs，避免双击 / 并发 startRun 越过 count 检查。
     * 多实例部署需 t_eval_run 部分唯一索引或 Redisson 分布式锁；当前 dev 单实例。
     */
    private final ReentrantLock startRunLock = new ReentrantLock();

    @Autowired
    public EvalRunServiceImpl(GoldDatasetMapper datasetMapper,
                              GoldItemMapper itemMapper,
                              EvalRunMapper runMapper,
                              SystemSnapshotBuilder snapshotBuilder,
                              EvalRunExecutor runExecutor,
                              @Qualifier("evalExecutor") ThreadPoolTaskExecutor evalExecutor,
                              EvalProperties evalProps) {
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.runMapper = runMapper;
        this.snapshotBuilder = snapshotBuilder;
        this.runExecutor = runExecutor;
        this.evalExecutor = evalExecutor;
        this.evalProps = evalProps;
    }

    @Override
    public String startRun(String datasetId, String principalUserId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || Objects.equals(ds.getDeleted(), 1)) {
            throw new ClientException("dataset not found: " + datasetId, BaseErrorCode.CLIENT_ERROR);
        }
        if (!"ACTIVE".equals(ds.getStatus())) {
            throw new ClientException("dataset must be ACTIVE to evaluate, current=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        long approved = itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .eq(GoldItemDO::getReviewStatus, "APPROVED"));
        if (approved == 0) {
            throw new ClientException("dataset has zero APPROVED items, cannot evaluate",
                    BaseErrorCode.CLIENT_ERROR);
        }

        int maxParallel = Math.max(1, evalProps.getRun().getMaxParallelRuns());
        startRunLock.lock();
        try {
            long active = runMapper.selectCount(new LambdaQueryWrapper<EvalRunDO>()
                    .in(EvalRunDO::getStatus, ACTIVE_STATUSES));
            if (active >= maxParallel) {
                throw new ClientException(
                        "max-parallel-runs reached: " + active + "/" + maxParallel
                                + ", wait for active run to finish",
                        BaseErrorCode.CLIENT_ERROR);
            }

            String runId = IdUtil.getSnowflakeNextIdStr();
            EvalRunDO run = EvalRunDO.builder()
                    .id(runId)
                    .datasetId(datasetId)
                    .kbId(ds.getKbId())
                    .triggeredBy(principalUserId)
                    .status("PENDING")
                    .totalItems((int) approved)
                    .succeededItems(0)
                    .failedItems(0)
                    .systemSnapshot(snapshotBuilder.build())
                    .createdBy(principalUserId)
                    .updatedBy(principalUserId)
                    .build();
            runMapper.insert(run);

            try {
                evalExecutor.execute(() -> {
                    try {
                        runExecutor.runInternal(runId);
                    } catch (Exception e) {
                        log.error("[eval-run] runId={} crashed", runId, e);
                    }
                });
            } catch (TaskRejectedException e) {
                EvalRunDO failed = new EvalRunDO();
                failed.setId(runId);
                failed.setStatus("FAILED");
                failed.setFinishedAt(new Date());
                failed.setErrorMessage("eval task rejected: " + e.getMessage());
                failed.setUpdatedBy(principalUserId);
                runMapper.updateById(failed);
                throw new ClientException("eval task rejected: " + e.getMessage(), BaseErrorCode.CLIENT_ERROR);
            }
            return runId;
        } finally {
            startRunLock.unlock();
        }
    }

    @Override
    public EvalRunDO getRun(String runId) {
        return runMapper.selectById(runId);
    }

    @Override
    public List<EvalRunDO> listRuns(String datasetId) {
        return runMapper.selectList(new LambdaQueryWrapper<EvalRunDO>()
                .eq(EvalRunDO::getDatasetId, datasetId)
                .orderByDesc(EvalRunDO::getCreateTime));
    }
}
