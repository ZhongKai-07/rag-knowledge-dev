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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nageoffer.ai.ragent.rag.controller.vo.RagEvaluationRecordVO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagEvaluationRecordDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagEvaluationRecordMapper;
import com.nageoffer.ai.ragent.rag.dto.EvaluationCollector;
import com.nageoffer.ai.ragent.rag.service.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 评测记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluationServiceImpl implements RagEvaluationService {

    private final RagEvaluationRecordMapper evaluationMapper;
    private final Gson gson = new Gson();

    @Async
    @Override
    public void saveRecord(EvaluationCollector collector, String conversationId, String messageId,
                           String traceId, String userId) {
        try {
            RagEvaluationRecordDO record = RagEvaluationRecordDO.builder()
                    .traceId(traceId)
                    .conversationId(conversationId)
                    .messageId(messageId)
                    .userId(userId)
                    .originalQuery(collector.getOriginalQuery())
                    .rewrittenQuery(collector.getRewrittenQuery())
                    .subQuestions(collector.getSubQuestions() != null ? gson.toJson(collector.getSubQuestions()) : null)
                    .retrievedChunks(collector.getChunks() != null ? gson.toJson(collector.getChunks()) : null)
                    .retrievalTopK(collector.getTopK())
                    .answer(collector.getAnswer())
                    .modelName(collector.getModelName())
                    .intentResults(collector.getIntents() != null ? gson.toJson(collector.getIntents()) : null)
                    .evalStatus("PENDING")
                    .build();
            evaluationMapper.insert(record);
        } catch (Exception e) {
            log.warn("保存 RAG 评测记录失败", e);
        }
    }

    @Override
    public IPage<RagEvaluationRecordVO> pageQuery(int current, int size, String evalStatus) {
        Page<RagEvaluationRecordDO> page = new Page<>(current, size);
        var wrapper = Wrappers.lambdaQuery(RagEvaluationRecordDO.class)
                .eq(StrUtil.isNotBlank(evalStatus), RagEvaluationRecordDO::getEvalStatus, evalStatus)
                .orderByDesc(RagEvaluationRecordDO::getCreateTime);
        return evaluationMapper.selectPage(page, wrapper).convert(this::toVO);
    }

    @Override
    public RagEvaluationRecordVO detail(String id) {
        RagEvaluationRecordDO record = evaluationMapper.selectById(id);
        return record != null ? toVO(record) : null;
    }

    @Override
    public List<RagasExportItem> exportForRagas(String evalStatus, int limit) {
        var wrapper = Wrappers.lambdaQuery(RagEvaluationRecordDO.class)
                .eq(StrUtil.isNotBlank(evalStatus), RagEvaluationRecordDO::getEvalStatus, evalStatus)
                .isNotNull(RagEvaluationRecordDO::getAnswer)
                .isNotNull(RagEvaluationRecordDO::getRetrievedChunks)
                .orderByDesc(RagEvaluationRecordDO::getCreateTime)
                .last("limit " + Math.min(limit, 1000));
        List<RagEvaluationRecordDO> records = evaluationMapper.selectList(wrapper);

        return records.stream().map(record -> {
            List<String> contexts = extractChunkTexts(record.getRetrievedChunks());
            return new RagasExportItem(
                    record.getOriginalQuery(),
                    contexts,
                    record.getAnswer(),
                    Collections.emptyList()
            );
        }).toList();
    }

    @Override
    public void updateMetrics(String id, String evalMetrics) {
        RagEvaluationRecordDO update = RagEvaluationRecordDO.builder()
                .evalStatus("COMPLETED")
                .evalMetrics(evalMetrics)
                .build();
        evaluationMapper.update(update, Wrappers.lambdaUpdate(RagEvaluationRecordDO.class)
                .eq(RagEvaluationRecordDO::getId, id));
    }

    private RagEvaluationRecordVO toVO(RagEvaluationRecordDO record) {
        return RagEvaluationRecordVO.builder()
                .id(record.getId())
                .traceId(record.getTraceId())
                .conversationId(record.getConversationId())
                .messageId(record.getMessageId())
                .userId(record.getUserId())
                .originalQuery(record.getOriginalQuery())
                .rewrittenQuery(record.getRewrittenQuery())
                .subQuestions(record.getSubQuestions())
                .retrievedChunks(record.getRetrievedChunks())
                .retrievalTopK(record.getRetrievalTopK())
                .answer(record.getAnswer())
                .modelName(record.getModelName())
                .intentResults(record.getIntentResults())
                .evalStatus(record.getEvalStatus())
                .evalMetrics(record.getEvalMetrics())
                .createTime(record.getCreateTime())
                .build();
    }

    private List<String> extractChunkTexts(String retrievedChunksJson) {
        if (StrUtil.isBlank(retrievedChunksJson)) {
            return Collections.emptyList();
        }
        try {
            List<EvaluationCollector.RetrievedChunkSnapshot> chunks = gson.fromJson(
                    retrievedChunksJson,
                    new TypeToken<List<EvaluationCollector.RetrievedChunkSnapshot>>() {}.getType()
            );
            return chunks.stream()
                    .map(EvaluationCollector.RetrievedChunkSnapshot::text)
                    .toList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
