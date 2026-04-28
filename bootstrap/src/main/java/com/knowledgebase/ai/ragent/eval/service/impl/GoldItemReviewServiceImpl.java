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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowledgebase.ai.ragent.eval.controller.request.EditGoldItemRequest;
import com.knowledgebase.ai.ragent.eval.controller.request.ReviewGoldItemRequest;
import com.knowledgebase.ai.ragent.eval.controller.vo.GoldItemVO;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldItemDO;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.knowledgebase.ai.ragent.eval.service.GoldItemReviewService;
import com.knowledgebase.ai.ragent.framework.errorcode.BaseErrorCode;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GoldItemReviewServiceImpl implements GoldItemReviewService {

    private final GoldItemMapper itemMapper;
    private final GoldDatasetMapper datasetMapper;

    @Override
    public List<GoldItemVO> list(String datasetId, String reviewStatus) {
        LambdaQueryWrapper<GoldItemDO> q = new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .orderByAsc(GoldItemDO::getCreateTime);
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            q.eq(GoldItemDO::getReviewStatus, reviewStatus);
        }
        return itemMapper.selectList(q).stream().map(this::toVO).toList();
    }

    @Override
    public void review(String itemId, ReviewGoldItemRequest req, String operatorUserId) {
        GoldItemDO item = requiredItem(itemId);
        requireDraftDataset(item.getDatasetId());
        String action = req.getAction();
        String newStatus = switch (action == null ? "" : action.toUpperCase()) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw new ClientException("action must be APPROVE or REJECT", BaseErrorCode.CLIENT_ERROR);
        };
        item.setReviewStatus(newStatus);
        item.setReviewNote(req.getNote());
        item.setUpdatedBy(operatorUserId);
        itemMapper.updateById(item);
    }

    @Override
    public void edit(String itemId, EditGoldItemRequest req, String operatorUserId) {
        GoldItemDO item = requiredItem(itemId);
        requireDraftDataset(item.getDatasetId());
        if (req.getQuestion() != null && !req.getQuestion().isBlank()) {
            item.setQuestion(req.getQuestion());
        }
        if (req.getGroundTruthAnswer() != null && !req.getGroundTruthAnswer().isBlank()) {
            item.setGroundTruthAnswer(req.getGroundTruthAnswer());
        }
        item.setUpdatedBy(operatorUserId);
        itemMapper.updateById(item);
    }

    private GoldItemDO requiredItem(String itemId) {
        GoldItemDO item = itemMapper.selectById(itemId);
        if (item == null || Objects.equals(item.getDeleted(), 1)) {
            throw new ClientException("item not found: " + itemId, BaseErrorCode.CLIENT_ERROR);
        }
        return item;
    }

    private void requireDraftDataset(String datasetId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || !"DRAFT".equals(ds.getStatus())) {
            throw new ClientException("item can only be reviewed while dataset is DRAFT", BaseErrorCode.CLIENT_ERROR);
        }
    }

    private GoldItemVO toVO(GoldItemDO d) {
        return new GoldItemVO(
                d.getId(),
                d.getDatasetId(),
                d.getQuestion(),
                d.getGroundTruthAnswer(),
                d.getSourceChunkId(),
                d.getSourceChunkText(),
                d.getSourceDocId(),
                d.getSourceDocName(),
                d.getReviewStatus(),
                d.getReviewNote(),
                d.getSynthesizedByModel()
        );
    }
}
