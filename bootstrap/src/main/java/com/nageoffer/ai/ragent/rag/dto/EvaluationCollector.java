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
