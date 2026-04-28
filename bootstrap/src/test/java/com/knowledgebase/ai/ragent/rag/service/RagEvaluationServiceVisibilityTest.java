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

import com.knowledgebase.ai.ragent.rag.dao.entity.RagEvaluationRecordDO;
import com.knowledgebase.ai.ragent.rag.dao.mapper.RagEvaluationRecordMapper;
import com.knowledgebase.ai.ragent.rag.service.impl.RagEvaluationServiceImpl;
import com.knowledgebase.ai.ragent.rag.service.support.TraceEvalAccessSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvaluationServiceVisibilityTest {

    private RagEvaluationRecordMapper evaluationMapper;
    private TraceEvalAccessSupport accessSupport;
    private RagEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        evaluationMapper = mock(RagEvaluationRecordMapper.class);
        accessSupport = mock(TraceEvalAccessSupport.class);
        service = new RagEvaluationServiceImpl(evaluationMapper, accessSupport);
    }

    @Test
    void detail_returnsNullWhenRecordIsNotVisible() {
        when(evaluationMapper.selectById("eval-1")).thenReturn(RagEvaluationRecordDO.builder().id("eval-1").userId("u-2").build());
        when(accessSupport.isVisible(anyString())).thenReturn(false);

        assertNull(service.detail("eval-1"));
    }
}
