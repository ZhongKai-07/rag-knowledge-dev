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

import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;

import java.util.List;

public interface ConversationMessageService {

    /**
     * 新增对话消息
     *
     * @param conversationMessage 消息内容
     */
    String addMessage(ConversationMessageBO conversationMessage);

    /**
     * 获取对话消息列表（支持排序与数量限制）
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param limit          限制数量
     * @param order          排序方式
     * @return 对话消息列表
     */
    List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order);

    /**
     * 添加对话摘要
     *
     * @param conversationSummary 对话摘要内容
     */
    void addMessageSummary(ConversationSummaryBO conversationSummary);

    /**
     * 更新指定消息的 sources_json 列。messageId blank 时 no-op。
     * 其他异常由调用方 try/catch 降级处理。
     *
     * <p><b>契约限制</b>：当前实现基于 {@code mapper.update(entity, lambdaUpdate)} +
     * MyBatis Plus 默认 {@code NOT_NULL} 字段策略。传入 {@code json=null} 不会清空列，
     * 而会被 MP 视作"跳过该字段"，UPDATE 实际为 no-op。调用方保证 json 非空。
     *
     * @param messageId 消息 ID
     * @param json      SourceCard[] 的 JSON 序列化字符串；调用方保证非空
     */
    void updateSourcesJson(String messageId, String json);
}
