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

package com.nageoffer.ai.ragent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.nageoffer.ai.ragent.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.nageoffer.ai.ragent.test.support.TestServiceBuilders;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessScopeServiceContractTest {

    @BeforeAll
    static void beforeAll() {
        initTableInfo(KnowledgeBaseDO.class);
        initTableInfo(KnowledgeDocumentDO.class);
    }

    @Test
    void knowledgeBasePageQuery_allScope_doesNotAddInClause() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseServiceImpl service = TestServiceBuilders.knowledgeBaseService(mock(KbAccessService.class), mapper);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<KnowledgeBaseDO>(1, 10, 0));

        service.pageQuery(pageRequest(), AccessScope.all());

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        assertFalse(sqlSegment(captor.getValue()).contains(" IN "));
    }

    @Test
    void knowledgeBasePageQuery_emptyIdsScope_shortCircuitsBeforeMapper() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseServiceImpl service = TestServiceBuilders.knowledgeBaseService(mock(KbAccessService.class), mapper);

        var result = service.pageQuery(pageRequest(), AccessScope.empty());

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        verify(mapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void knowledgeBasePageQuery_nonEmptyIdsScope_addsInClauseWithKbIds() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseServiceImpl service = TestServiceBuilders.knowledgeBaseService(mock(KbAccessService.class), mapper);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<KnowledgeBaseDO>(1, 10, 0));

        service.pageQuery(pageRequest(), AccessScope.ids(Set.of("kb-a", "kb-b")));

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeBaseDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        LambdaQueryWrapper<KnowledgeBaseDO> wrapper = captor.getValue();
        assertTrue(sqlSegment(wrapper).contains(" IN "));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("kb-a"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("kb-b"));
    }

    @Test
    void knowledgeDocumentSearch_allScope_doesNotAddInClause() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentServiceImpl service = TestServiceBuilders.knowledgeDocumentService(mock(KbAccessService.class), mapper);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<KnowledgeDocumentDO>(1, 8, 0));

        service.search("doc", 8, AccessScope.all());

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeDocumentDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        assertFalse(sqlSegment(captor.getValue()).contains(" IN "));
    }

    @Test
    void knowledgeDocumentSearch_emptyIdsScope_shortCircuitsBeforeMapper() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentServiceImpl service = TestServiceBuilders.knowledgeDocumentService(mock(KbAccessService.class), mapper);

        List<KnowledgeDocumentSearchVO> result = service.search("doc", 8, AccessScope.empty());

        assertTrue(result.isEmpty());
        verify(mapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    void knowledgeDocumentSearch_nonEmptyIdsScope_addsInClauseWithKbIds() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocumentServiceImpl service = TestServiceBuilders.knowledgeDocumentService(mock(KbAccessService.class), mapper);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<KnowledgeDocumentDO>(1, 8, 0));

        service.search("doc", 8, AccessScope.ids(Set.of("kb-a", "kb-b")));

        ArgumentCaptor<LambdaQueryWrapper<KnowledgeDocumentDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        LambdaQueryWrapper<KnowledgeDocumentDO> wrapper = captor.getValue();
        assertTrue(sqlSegment(wrapper).contains(" IN "));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("kb-a"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("kb-b"));
    }

    private static KnowledgeBasePageRequest pageRequest() {
        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        return request;
    }

    private static String sqlSegment(LambdaQueryWrapper<?> wrapper) {
        return wrapper.getSqlSegment().toUpperCase();
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
