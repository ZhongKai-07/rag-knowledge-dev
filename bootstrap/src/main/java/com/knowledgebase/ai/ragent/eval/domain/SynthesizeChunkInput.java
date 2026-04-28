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
 * 送给 Python /synthesize 的单条 chunk 输入。
 * Java 侧采样时已从 t_knowledge_chunk + t_knowledge_document join 出这些字段。
 */
public record SynthesizeChunkInput(
        String id,
        String text,
        @JsonProperty("doc_name") String docName
) {
}
