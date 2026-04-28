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

package com.knowledgebase.ai.ragent.rag.core;

import com.knowledgebase.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * RAG 链路的同步结果产物。
 *
 * <p>由 {@link ChatForEvalService} 返回，由 eval 域消费。
 * streamChat 路径不消费此类型（保持其原有 SSE 行为）。
 */
public sealed interface AnswerResult
        permits AnswerResult.Success,
                AnswerResult.EmptyContext,
                AnswerResult.SystemOnlySkipped,
                AnswerResult.AmbiguousIntentSkipped {

    record Success(String answer, List<RetrievedChunk> chunks) implements AnswerResult {}

    record EmptyContext() implements AnswerResult {}

    record SystemOnlySkipped() implements AnswerResult {}

    record AmbiguousIntentSkipped() implements AnswerResult {}

    static AnswerResult success(String answer, List<RetrievedChunk> chunks) {
        return new Success(answer, chunks);
    }

    static AnswerResult emptyContext() {
        return new EmptyContext();
    }

    static AnswerResult systemOnlySkipped() {
        return new SystemOnlySkipped();
    }

    static AnswerResult ambiguousIntentSkipped() {
        return new AmbiguousIntentSkipped();
    }
}
