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

package com.knowledgebase.ai.ragent.rag.service;

import com.knowledgebase.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.knowledgebase.ai.ragent.rag.dao.entity.RagTraceRunDO;

import java.util.Date;
import java.util.Map;

/**
 * RAG Trace 记录服务
 */
public interface RagTraceRecordService {

    void startRun(RagTraceRunDO run);

    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs, String extraData);

    void startNode(RagTraceNodeDO node);

    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);

    void updateRunExtraData(String traceId, String extraData);

    /**
     * 合并字段到 run 的 extra_data JSON
     *
     * <p>读取现有 extraData（可能为 null/空/合法 JSON），
     * 把 additions 里的键合并进去（覆盖同名键），然后写回。</p>
     *
     * @param traceId   run 对应的 traceId
     * @param additions 需要合并的键值对
     */
    void mergeRunExtraData(String traceId, Map<String, Object> additions);
}
