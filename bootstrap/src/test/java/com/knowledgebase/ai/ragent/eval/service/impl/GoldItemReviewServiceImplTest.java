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

package com.knowledgebase.ai.ragent.eval.service.impl;

import com.knowledgebase.ai.ragent.eval.controller.request.EditGoldItemRequest;
import com.knowledgebase.ai.ragent.eval.controller.request.ReviewGoldItemRequest;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldItemDO;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldItemReviewServiceImplTest {

    private GoldItemMapper itemMapper;
    private GoldDatasetMapper datasetMapper;
    private GoldItemReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        itemMapper = mock(GoldItemMapper.class);
        datasetMapper = mock(GoldDatasetMapper.class);
        service = new GoldItemReviewServiceImpl(itemMapper, datasetMapper);
    }

    @Test
    void approve_sets_review_status_APPROVED() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").reviewStatus("PENDING").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").build());

        ReviewGoldItemRequest req = new ReviewGoldItemRequest();
        req.setAction("APPROVE");
        req.setNote("ok");
        service.review("i1", req, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).updateById(cap.capture());
        assertThat(cap.getValue().getReviewStatus()).isEqualTo("APPROVED");
        assertThat(cap.getValue().getReviewNote()).isEqualTo("ok");
    }

    @Test
    void reject_sets_review_status_REJECTED() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").reviewStatus("PENDING").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").build());

        ReviewGoldItemRequest req = new ReviewGoldItemRequest();
        req.setAction("REJECT");
        service.review("i1", req, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).updateById(cap.capture());
        assertThat(cap.getValue().getReviewStatus()).isEqualTo("REJECTED");
    }

    @Test
    void review_rejects_when_dataset_ACTIVE() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").reviewStatus("PENDING").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("ACTIVE").build());

        ReviewGoldItemRequest req = new ReviewGoldItemRequest();
        req.setAction("APPROVE");

        assertThatThrownBy(() -> service.review("i1", req, "user-sys"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    void edit_updates_question_and_answer() {
        GoldItemDO item = GoldItemDO.builder().id("i1").datasetId("d1").build();
        when(itemMapper.selectById("i1")).thenReturn(item);
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").build());

        EditGoldItemRequest req = new EditGoldItemRequest();
        req.setQuestion("new Q");
        req.setGroundTruthAnswer("new A");
        service.edit("i1", req, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).updateById(cap.capture());
        assertThat(cap.getValue().getQuestion()).isEqualTo("new Q");
        assertThat(cap.getValue().getGroundTruthAnswer()).isEqualTo("new A");
    }
}
