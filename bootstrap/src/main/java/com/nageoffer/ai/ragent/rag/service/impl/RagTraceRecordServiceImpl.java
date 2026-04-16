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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG Trace 记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagTraceRecordServiceImpl implements RagTraceRecordService {

    // Jackson 保留整数类型：Integer/Long 不会被 round-trip 成 Double。
    // 这很重要，extra_data 里的 totalTokens 会被 Dashboard SQL 的 CAST(... AS INTEGER) 使用，
    // 若写成 "5228.0" 会导致 PSQLException。
    private static final ObjectMapper EXTRA_DATA_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;

    @Override
    public void startRun(RagTraceRunDO run) {
        runMapper.insert(run);
    }

    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        finishRun(traceId, status, errorMessage, endTime, durationMs, null);
    }

    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs, String extraData) {
        RagTraceRunDO update = RagTraceRunDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .extraData(extraData)
                .build();
        runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
    }

    @Override
    public void updateRunExtraData(String traceId, String extraData) {
        RagTraceRunDO update = RagTraceRunDO.builder()
                .extraData(extraData)
                .build();
        runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
    }

    @Override
    public void mergeRunExtraData(String traceId, Map<String, Object> additions) {
        RagTraceRunDO existing = runMapper.selectOne(
                Wrappers.lambdaQuery(RagTraceRunDO.class)
                        .eq(RagTraceRunDO::getTraceId, traceId));

        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null && StrUtil.isNotBlank(existing.getExtraData())) {
            try {
                Map<String, Object> parsed = EXTRA_DATA_MAPPER.readValue(existing.getExtraData(), MAP_TYPE);
                if (parsed != null) {
                    merged.putAll(parsed);
                }
            } catch (Exception e) {
                log.warn("解析 extra_data 失败，将丢弃并覆盖，traceId={}", traceId, e);
            }
        }
        merged.putAll(additions);

        String written;
        try {
            written = EXTRA_DATA_MAPPER.writeValueAsString(merged);
        } catch (Exception e) {
            log.warn("序列化 extra_data 失败，放弃合并，traceId={}", traceId, e);
            return;
        }

        RagTraceRunDO update = RagTraceRunDO.builder()
                .extraData(written)
                .build();
        runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
    }

    @Override
    public void startNode(RagTraceNodeDO node) {
        nodeMapper.insert(node);
    }

    @Override
    public void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs) {
        RagTraceNodeDO update = RagTraceNodeDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .eq(RagTraceNodeDO::getNodeId, nodeId));
    }
}
