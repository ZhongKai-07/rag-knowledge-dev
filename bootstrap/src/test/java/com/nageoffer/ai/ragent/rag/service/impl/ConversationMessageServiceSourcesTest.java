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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ConversationMessageServiceSourcesTest {

    @Mock ConversationMessageMapper conversationMessageMapper;
    @Mock ConversationSummaryMapper conversationSummaryMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock MessageFeedbackService feedbackService;

    private ConversationMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        // Initialize MP lambda cache so LambdaUpdateWrapper can resolve column metadata without Spring
        initTableInfo(ConversationMessageDO.class);
        service = new ConversationMessageServiceImpl(
                conversationMessageMapper,
                conversationSummaryMapper,
                conversationMapper,
                feedbackService
        );
    }

    @Test
    void updateSourcesJson_withValidInputs_updatesOnlySourcesJsonColumnById() {
        String messageId = "msg-001";
        String json = "[{\"index\":1,\"docId\":\"d1\"}]";

        service.updateSourcesJson(messageId, json);

        ArgumentCaptor<ConversationMessageDO> entityCaptor =
                ArgumentCaptor.forClass(ConversationMessageDO.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<ConversationMessageDO>> wrapperCaptor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
        verify(conversationMessageMapper, times(1))
                .update(entityCaptor.capture(), wrapperCaptor.capture());

        // entity 只带 sourcesJson
        ConversationMessageDO captured = entityCaptor.getValue();
        assertEquals(json, captured.getSourcesJson());
        assertNull(captured.getId(), "id should not be set on update entity");
        assertNull(captured.getContent(), "content should not be touched");
        assertNull(captured.getRole(), "role should not be touched");

        // wrapper 形状：含 id 谓词（MP 生成占位符，不含 messageId 字面量）
        Wrapper<ConversationMessageDO> wrapper = wrapperCaptor.getValue();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("id ="),
                "wrapper should contain 'id =' predicate, got: " + sqlSegment);

        // wrapper 绑定值：实际绑定的 value 是 messageId（cast to AbstractWrapper to access paramNameValuePairs）
        // AbstractWrapper cast required: Wrapper<T> interface does not expose getParamNameValuePairs()
        // in MP 3.5.14 — this reaches an internal API. Revisit this assertion on MP major-version upgrades.
        @SuppressWarnings("rawtypes")
        Map<String, Object> params = ((AbstractWrapper) wrapper).getParamNameValuePairs();
        assertTrue(params.values().contains(messageId),
                "wrapper params should bind messageId=" + messageId + ", got: " + params);
    }

    @Test
    void updateSourcesJson_withBlankMessageId_isNoOp() {
        service.updateSourcesJson("", "[]");
        service.updateSourcesJson("  ", "[]");
        service.updateSourcesJson(null, "[]");

        verifyNoInteractions(conversationMessageMapper);
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
