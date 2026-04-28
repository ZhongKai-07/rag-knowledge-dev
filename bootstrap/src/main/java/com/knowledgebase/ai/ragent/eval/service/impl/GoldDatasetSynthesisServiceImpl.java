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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowledgebase.ai.ragent.eval.client.RagasEvalClient;
import com.knowledgebase.ai.ragent.eval.config.EvalProperties;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldItemDO;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizeChunkInput;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizeRequest;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizeResponse;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizedItem;
import com.knowledgebase.ai.ragent.eval.service.GoldDatasetSynthesisService;
import com.knowledgebase.ai.ragent.eval.service.SynthesisProgressTracker;
import com.knowledgebase.ai.ragent.framework.errorcode.BaseErrorCode;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbChunkSamplerPort.ChunkSample;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class GoldDatasetSynthesisServiceImpl implements GoldDatasetSynthesisService {

    private final GoldDatasetMapper datasetMapper;
    private final GoldItemMapper itemMapper;
    private final KbChunkSamplerPort sampler;
    private final RagasEvalClient client;
    private final SynthesisProgressTracker tracker;
    private final EvalProperties props;
    private final ThreadPoolTaskExecutor evalExecutor;

    /**
     * 单一构造器——Spring 4.3+ 对单公共构造器自动注入，但显式 @Autowired 避免歧义。
     * 测试时传 evalExecutor=null 并走 runSynthesisSync（同步路径），trigger 会抛 IllegalStateException。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public GoldDatasetSynthesisServiceImpl(GoldDatasetMapper datasetMapper,
                                           GoldItemMapper itemMapper,
                                           KbChunkSamplerPort sampler,
                                           RagasEvalClient client,
                                           SynthesisProgressTracker tracker,
                                           EvalProperties props,
                                           @Qualifier("evalExecutor") ThreadPoolTaskExecutor evalExecutor) {
        this.datasetMapper = datasetMapper;
        this.itemMapper = itemMapper;
        this.sampler = sampler;
        this.client = client;
        this.tracker = tracker;
        this.props = props;
        this.evalExecutor = evalExecutor;
    }

    @Override
    public void trigger(String datasetId, int count, String principalUserId) {
        // 1. DB / tracker 前置校验——tracker 占坑前必须先验，失败时不留副作用
        validatePreconditions(datasetId);
        if (evalExecutor == null) {
            throw new IllegalStateException("evalExecutor not wired — test-only path must call runSynthesisSync");
        }
        // 2. 原子占坑——并发第二个请求会在这里被拒，防 validate race
        if (!tracker.tryBegin(datasetId)) {
            throw new ClientException("synthesis already in progress or completed for datasetId=" + datasetId,
                    BaseErrorCode.CLIENT_ERROR);
        }
        // 3. 提交异步任务——tracker 终态（complete/fail）由任务内更新
        evalExecutor.execute(() -> {
            try {
                runSynthesisSync(datasetId, count, principalUserId);
            } catch (Exception e) {
                log.error("[eval-synthesis] datasetId={} failed", datasetId, e);
                tracker.fail(datasetId, e.getMessage());
            }
        });
    }

    @Override
    public void runSynthesisSync(String datasetId, int count, String principalUserId) {
        GoldDatasetDO ds = validatePreconditions(datasetId);
        int target = count > 0 ? count : props.getSynthesis().getDefaultCount();

        List<ChunkSample> samples = sampler.sampleForSynthesis(ds.getKbId(), target, props.getSynthesis().getMaxPerDoc());
        if (samples.isEmpty()) {
            tracker.fail(datasetId, "no sampleable chunks for kbId=" + ds.getKbId());
            throw new ClientException("no sampleable chunks; check KB has success-status docs with enabled chunks",
                    BaseErrorCode.CLIENT_ERROR);
        }
        tracker.begin(datasetId, samples.size());

        int batchSize = Math.max(1, props.getSynthesis().getBatchSize());
        Map<String, ChunkSample> byId = new HashMap<>();
        for (ChunkSample s : samples) {
            byId.put(s.chunkId(), s);
        }

        int processed = 0;
        int failed = 0;
        for (int i = 0; i < samples.size(); i += batchSize) {
            List<ChunkSample> batch = samples.subList(i, Math.min(i + batchSize, samples.size()));
            List<SynthesizeChunkInput> inputs = batch.stream()
                    .map(s -> new SynthesizeChunkInput(s.chunkId(), s.chunkText(), s.docName()))
                    .toList();

            SynthesizeResponse resp;
            try {
                resp = client.synthesize(new SynthesizeRequest(inputs));
            } catch (Exception e) {
                log.warn("[eval-synthesis] batch HTTP failed datasetId={} batchStart={} size={}",
                        datasetId, i, batch.size(), e);
                failed += batch.size();
                tracker.update(datasetId, processed, failed);
                continue;
            }

            for (SynthesizedItem item : resp.items()) {
                ChunkSample origin = byId.get(item.sourceChunkId());
                if (origin == null) {
                    log.warn("[eval-synthesis] python returned unknown chunk_id={} for datasetId={}",
                            item.sourceChunkId(), datasetId);
                    continue;
                }
                // spec §10 gotcha #6：Java 入库前再做 blank 校验，双重 fail-closed。
                // Python 已有 pydantic 校验，但契约可能漂移；Java 侧不信任 Python 输出。
                if (item.question() == null || item.question().isBlank()
                        || item.answer() == null || item.answer().isBlank()) {
                    log.warn("[eval-synthesis] blank q/a from python chunk_id={} datasetId={}",
                            item.sourceChunkId(), datasetId);
                    failed++;
                    continue;
                }
                GoldItemDO row = GoldItemDO.builder()
                        .datasetId(datasetId)
                        .question(item.question())
                        .groundTruthAnswer(item.answer())
                        .sourceChunkId(origin.chunkId())
                        .sourceChunkText(origin.chunkText())
                        .sourceDocId(origin.docId())
                        .sourceDocName(origin.docName())
                        .reviewStatus("PENDING")
                        .synthesizedByModel(props.getSynthesis().getStrongModel())
                        .createdBy(principalUserId)
                        .updatedBy(principalUserId)
                        .build();
                itemMapper.insert(row);
                processed++;
            }
            if (resp.failedChunkIds() != null) {
                failed += resp.failedChunkIds().size();
            }
            tracker.update(datasetId, processed, failed);
        }

        tracker.complete(datasetId, processed, failed);
        log.info("[eval-synthesis] datasetId={} total={} processed={} failed={}",
                datasetId, samples.size(), processed, failed);
    }

    /**
     * 合成前置：DB 态 + 状态机校验。
     * ⚠️ 不在这里检查 tracker——并发占位由 trigger() 里 {@link SynthesisProgressTracker#tryBegin} 负责。
     * 两个 request 可能都通过 validatePreconditions（DB 视角 existing=0），
     * 但只有第一个能通过 tryBegin，第二个被拒。
     */
    private GoldDatasetDO validatePreconditions(String datasetId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || Objects.equals(ds.getDeleted(), 1)) {
            throw new ClientException("dataset not found: " + datasetId, BaseErrorCode.CLIENT_ERROR);
        }
        if (!"DRAFT".equals(ds.getStatus())) {
            throw new ClientException("can only synthesize into DRAFT dataset, current=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        long existing = itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId));
        if (existing > 0) {
            throw new ClientException("dataset already has " + existing + " items; delete dataset to retry",
                    BaseErrorCode.CLIENT_ERROR);
        }
        return ds;
    }
}
