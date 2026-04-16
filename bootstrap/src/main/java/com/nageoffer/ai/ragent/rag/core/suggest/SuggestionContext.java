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

package com.nageoffer.ai.ragent.rag.core.suggest;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 推荐问题生成的输入上下文
 *
 * @param question        用户当前问题（改写前的原始提问）
 * @param history         最近对话历史（由调用方裁剪）
 * @param topChunks       本轮检索的 top-N 片段（由调用方扁平化并排序）
 * @param shouldGenerate  是否触发生成（false 表示调用方已预判跳过）
 */
public record SuggestionContext(
        String question,
        List<ChatMessage> history,
        List<RetrievedChunk> topChunks,
        boolean shouldGenerate
) {

    /**
     * 生成一个 shouldGenerate=false 的占位上下文，
     * handler 在未被 orchestrator 更新时使用。
     */
    public static SuggestionContext skip() {
        return new SuggestionContext(null, List.of(), List.of(), false);
    }
}
