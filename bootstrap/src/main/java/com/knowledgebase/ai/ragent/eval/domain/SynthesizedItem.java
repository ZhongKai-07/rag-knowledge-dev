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

package com.knowledgebase.ai.ragent.eval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Python /synthesize 返回的单条合成结果。
 * 仅包含 LLM 产出的三件套；chunk_text / doc_name 等由 Java 侧冻结。
 */
public record SynthesizedItem(
        @JsonProperty("source_chunk_id") String sourceChunkId,
        String question,
        String answer
) {
}
