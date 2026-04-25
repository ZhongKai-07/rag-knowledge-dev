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

package com.nageoffer.ai.ragent.eval.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldDatasetServiceImplTest {

    private GoldDatasetMapper datasetMapper;
    private GoldItemMapper itemMapper;
    private GoldDatasetServiceImpl service;

    @BeforeEach
    void setUp() {
        // 预热 MyBatis Plus lambda 缓存——否则 LambdaQueryWrapper::getSqlSegment 抛 MybatisPlusException
        initTableInfo(GoldDatasetDO.class);
        initTableInfo(GoldItemDO.class);
        datasetMapper = mock(GoldDatasetMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        service = new GoldDatasetServiceImpl(datasetMapper, itemMapper);
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    @Test
    void create_persists_with_DRAFT_status_and_returns_id() {
        // mock mapper 不会像真 MyBatis Plus 那样填 @TableId(ASSIGN_ID)；用 doAnswer 模拟
        when(datasetMapper.insert(any(GoldDatasetDO.class))).thenAnswer(inv -> {
            GoldDatasetDO d = inv.getArgument(0);
            d.setId("mock-id-" + java.util.UUID.randomUUID());
            return 1;
        });

        CreateGoldDatasetRequest req = new CreateGoldDatasetRequest();
        req.setKbId("kb-1");
        req.setName("smoke-50");
        req.setDescription("d");

        String id = service.create(req, "user-sys");

        ArgumentCaptor<GoldDatasetDO> cap = ArgumentCaptor.forClass(GoldDatasetDO.class);
        verify(datasetMapper).insert(cap.capture());
        GoldDatasetDO saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getItemCount()).isEqualTo(0);
        assertThat(saved.getKbId()).isEqualTo("kb-1");
        assertThat(saved.getCreatedBy()).isEqualTo("user-sys");
        assertThat(id).isNotBlank();
    }

    @Test
    void activate_rejects_non_DRAFT() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("ACTIVE").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);

        assertThatThrownBy(() -> service.activate("d1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("status");
    }

    @Test
    void activate_DRAFT_moves_to_ACTIVE_when_no_pending() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        // impl 会按 review_status 分别查 APPROVED / PENDING；用 Answer 按 wrapper 绑定值区分
        // （getSqlSegment 里是 '?' 占位，真值在 paramNameValuePairs 里）
        when(itemMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            String sql = renderWrapper(inv.getArgument(0));
            if (sql.contains("APPROVED")) return 10L;
            if (sql.contains("PENDING")) return 0L;
            return 10L;
        });

        service.activate("d1");

        ArgumentCaptor<GoldDatasetDO> cap = ArgumentCaptor.forClass(GoldDatasetDO.class);
        verify(datasetMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(cap.getValue().getItemCount()).isEqualTo(10);
    }

    @Test
    void activate_rejects_when_no_approved_items() {
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.activate("d1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void activate_rejects_when_pending_items_remain() {
        // spec §10 gotcha #3：激活前必须审完；否则 PENDING 条目会被 requireDraftDataset 永久锁
        GoldDatasetDO ds = GoldDatasetDO.builder().id("d1").status("DRAFT").build();
        when(datasetMapper.selectById("d1")).thenReturn(ds);
        when(itemMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            String sql = renderWrapper(inv.getArgument(0));
            if (sql.contains("APPROVED")) return 5L;
            if (sql.contains("PENDING")) return 3L;
            return 8L;
        });

        assertThatThrownBy(() -> service.activate("d1"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("PENDING");
    }

    /**
     * 把 wrapper 的 SQL 片段与实际绑定参数拼回可检测的文本：
     * {@code getSqlSegment()} 里是 '?' 占位，绑定值单独在 {@code paramNameValuePairs} 里。
     */
    private static String renderWrapper(Wrapper<?> w) {
        String sql = w.getSqlSegment();
        if (w instanceof AbstractWrapper<?, ?, ?> aw) {
            return sql + " :: " + aw.getParamNameValuePairs().values();
        }
        return sql;
    }
}
