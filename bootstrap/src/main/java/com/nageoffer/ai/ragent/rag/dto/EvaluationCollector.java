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

package com.nageoffer.ai.ragent.rag.dto;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import lombok.Data;

import java.util.List;

/**
 * RAG 评测数据采集器
 * 在 RAG 管线执行过程中逐步收集数据，最终落库用于评测（如 RAGAS）
 */
@Data
public class EvaluationCollector {

    private String originalQuery;

    private String rewrittenQuery;

    private List<String> subQuestions;

    private List<RetrievedChunkSnapshot> chunks;

    /**
     * 评测记录：实际喂给 LLM 的 chunk 数（dedup 后 distinctChunks.size()）。
     * <p>
     * 不等于全局 rag.retrieval.rerank-top-k 配置值——IntentNode.topK override、多 sub-question
     * 聚合 + dedup 会让实际数量与配置值分叉。评测口径采用"真值"：记录 prompt 阶段真实看到的
     * chunk 数，方便后续 recall/precision 分析。
     * <p>
     * 命名保留 "topK" 是为避免数据库 schema migration。若将来需要分别评测召回和 rerank 两个
     * 阶段，再在 t_rag_evaluation_record 加 recall_top_k 列。
     */
    private int topK;

    private List<IntentSnapshot> intents;

    private String answer;

    private String modelName;

    /**
     * 检索分块快照
     */
    public record RetrievedChunkSnapshot(String id, String text, double score) {

        public static RetrievedChunkSnapshot from(RetrievedChunk chunk) {
            return new RetrievedChunkSnapshot(chunk.getId(), chunk.getText(), chunk.getScore());
        }
    }

    /**
     * 意图识别结果快照
     */
    public record IntentSnapshot(String nodeId, String nodeName, double score, String kind) {
    }
}
