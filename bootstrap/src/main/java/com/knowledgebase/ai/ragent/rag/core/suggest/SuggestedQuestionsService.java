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

package com.knowledgebase.ai.ragent.rag.core.suggest;

import java.util.List;

/**
 * 推荐问题生成服务
 *
 * 约定：
 * - 任何异常（LLM 失败 / 解析失败 / 超时）都返回空 List，绝不向上抛
 * - 返回 List 可能少于 3 条，调用方按实际长度处理
 */
public interface SuggestedQuestionsService {

    /**
     * 基于本轮问答和检索片段生成后续问题
     *
     * @param context 输入上下文（调用方保证 shouldGenerate=true）
     * @param answer  本轮 assistant 回答全文
     * @return 推荐问题列表（0-3 条）
     */
    List<String> generate(SuggestionContext context, String answer);
}
