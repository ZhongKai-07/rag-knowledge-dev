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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.impl.RagTraceRecordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagTraceRecordServiceMergeTest {

    private RagTraceRunMapper runMapper;
    private RagTraceNodeMapper nodeMapper;
    private RagTraceRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        runMapper = mock(RagTraceRunMapper.class);
        nodeMapper = mock(RagTraceNodeMapper.class);
        service = new RagTraceRecordServiceImpl(runMapper, nodeMapper);
    }

    @Test
    void merges_into_null_extra_data() {
        RagTraceRunDO existing = RagTraceRunDO.builder().traceId("t1").extraData(null).build();
        when(runMapper.selectOne(any())).thenReturn(existing);

        service.mergeRunExtraData("t1", Map.of("suggestedQuestions", List.of("a", "b")));

        ArgumentCaptor<RagTraceRunDO> captor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(runMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        String written = captor.getValue().getExtraData();

        Map<?, ?> parsed = new Gson().fromJson(written, Map.class);
        assertTrue(parsed.containsKey("suggestedQuestions"));
        assertEquals(2, ((List<?>) parsed.get("suggestedQuestions")).size());
    }

    @Test
    void merges_preserving_existing_keys() {
        RagTraceRunDO existing = RagTraceRunDO.builder()
                .traceId("t1")
                .extraData("{\"promptTokens\":100,\"totalTokens\":150}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(existing);

        service.mergeRunExtraData("t1", Map.of("suggestedQuestions", List.of("x")));

        ArgumentCaptor<RagTraceRunDO> captor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(runMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        Map<?, ?> parsed = new Gson().fromJson(captor.getValue().getExtraData(), Map.class);

        assertEquals(100.0, parsed.get("promptTokens"));
        assertEquals(150.0, parsed.get("totalTokens"));
        assertTrue(parsed.containsKey("suggestedQuestions"));
    }

    @Test
    void addition_keys_override_existing_same_key() {
        RagTraceRunDO existing = RagTraceRunDO.builder()
                .traceId("t1")
                .extraData("{\"foo\":\"old\"}")
                .build();
        when(runMapper.selectOne(any())).thenReturn(existing);

        service.mergeRunExtraData("t1", Map.of("foo", "new"));

        ArgumentCaptor<RagTraceRunDO> captor = ArgumentCaptor.forClass(RagTraceRunDO.class);
        verify(runMapper).update(captor.capture(), any(LambdaUpdateWrapper.class));
        Map<?, ?> parsed = new Gson().fromJson(captor.getValue().getExtraData(), Map.class);
        assertEquals("new", parsed.get("foo"));
    }
}
