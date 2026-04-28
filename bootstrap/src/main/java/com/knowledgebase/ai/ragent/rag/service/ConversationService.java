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

import com.knowledgebase.ai.ragent.rag.controller.request.ConversationCreateRequest;
import com.knowledgebase.ai.ragent.rag.controller.request.ConversationUpdateRequest;
import com.knowledgebase.ai.ragent.rag.controller.vo.ConversationVO;

import java.util.List;

/**
 * 会话服务接口
 * 提供会话的创建、重命名和删除功能
 */
public interface ConversationService {

    /**
     * 根据用户ID获取会话列表，可选按知识库ID过滤
     *
     * @param userId 用户ID
     * @param kbId   知识库ID（可为空，为空时返回所有会话）
     * @return 会话视图对象列表
     */
    List<ConversationVO> listByUserId(String userId, String kbId);

    /**
     * 校验会话是否属于指定知识库
     * <p>
     * 旧会话 kb_id 为 NULL 视为不属于任何空间（fail-closed）
     * </p>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param kbId           知识库ID
     */
    void validateKbOwnership(String conversationId, String userId, String kbId);

    /**
     * 创建或更新会话
     * 如果 ConversationCreateRequest 里的会话 ID 存在则更新，不存在则创建
     *
     * @param request 创建请求对象
     */
    void createOrUpdate(ConversationCreateRequest request);

    /**
     * 重命名会话
     *
     * @param conversationId 会话 ID
     * @param request        更新请求对象
     */
    void rename(String conversationId, ConversationUpdateRequest request);

    /**
     * 删除会话
     *
     * @param conversationId 会话 ID
     */
    void delete(String conversationId);
}
