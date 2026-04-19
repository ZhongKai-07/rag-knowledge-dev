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

import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.impl.RagTraceQueryServiceImpl;
import com.nageoffer.ai.ragent.rag.service.support.TraceEvalAccessSupport;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagTraceQueryServiceVisibilityTest {

    private RagTraceRunMapper runMapper;
    private RagTraceNodeMapper nodeMapper;
    private UserMapper userMapper;
    private TraceEvalAccessSupport accessSupport;
    private RagTraceQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        runMapper = mock(RagTraceRunMapper.class);
        nodeMapper = mock(RagTraceNodeMapper.class);
        userMapper = mock(UserMapper.class);
        accessSupport = mock(TraceEvalAccessSupport.class);
        service = new RagTraceQueryServiceImpl(runMapper, nodeMapper, userMapper, accessSupport);
    }

    @Test
    void detail_returnsNullWhenTraceIsNotVisible() {
        when(runMapper.selectOne(any())).thenReturn(RagTraceRunDO.builder().traceId("t-1").userId("u-2").build());
        when(accessSupport.isVisible("u-2")).thenReturn(false);

        assertNull(service.detail("t-1"));
    }

    @Test
    void listNodes_returnsEmptyWhenTraceIsNotVisible() {
        when(runMapper.selectOne(any())).thenReturn(RagTraceRunDO.builder().traceId("t-1").userId("u-2").build());
        when(accessSupport.isVisible("u-2")).thenReturn(false);

        assertTrue(service.listNodes("t-1").isEmpty());
    }
}
