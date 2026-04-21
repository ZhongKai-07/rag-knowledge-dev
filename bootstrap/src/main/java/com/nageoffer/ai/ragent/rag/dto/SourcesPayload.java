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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SSE {@code sources} 事件载荷。
 * <p>
 * {@code messageId} 在流式阶段为 {@code null}（DB id 尚未分配）；
 * 前端按 {@code streamingMessageId} 定位消息，不依赖此字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcesPayload {
    private String conversationId;
    private String messageId;
    private List<SourceCard> cards;
}
