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

package com.nageoffer.ai.ragent.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.rag.controller.vo.RagEvaluationRecordVO;
import com.nageoffer.ai.ragent.rag.dto.EvaluationCollector;

import java.util.List;

/**
 * RAG 评测记录服务
 */
public interface RagEvaluationService {

    /**
     * 保存评测记录（异步调用）
     */
    void saveRecord(EvaluationCollector collector, String conversationId, String messageId,
                    String traceId, String userId);

    /**
     * 分页查询评测记录
     */
    IPage<RagEvaluationRecordVO> pageQuery(int current, int size, String evalStatus);

    /**
     * 查看单条评测详情
     */
    RagEvaluationRecordVO detail(String id);

    /**
     * 导出 RAGAS 兼容 JSON
     */
    List<RagasExportItem> exportForRagas(String evalStatus, int limit);

    /**
     * 回填评测结果
     */
    void updateMetrics(String id, String evalMetrics);

    /**
     * RAGAS 导出格式
     */
    record RagasExportItem(String question, List<String> contexts, String answer, List<String> groundTruths) {
    }
}
