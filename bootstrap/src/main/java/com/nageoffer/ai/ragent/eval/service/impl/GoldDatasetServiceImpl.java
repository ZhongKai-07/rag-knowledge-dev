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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldDatasetVO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.nageoffer.ai.ragent.eval.dao.entity.GoldItemDO;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.nageoffer.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.nageoffer.ai.ragent.eval.service.GoldDatasetService;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GoldDatasetServiceImpl implements GoldDatasetService {

    private final GoldDatasetMapper datasetMapper;
    private final GoldItemMapper itemMapper;

    @Override
    public String create(CreateGoldDatasetRequest req, String createdBy) {
        if (req.getKbId() == null || req.getKbId().isBlank()) {
            throw new ClientException("kbId required", BaseErrorCode.CLIENT_ERROR);
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new ClientException("name required", BaseErrorCode.CLIENT_ERROR);
        }
        GoldDatasetDO ds = GoldDatasetDO.builder()
                .kbId(req.getKbId())
                .name(req.getName().trim())
                .description(req.getDescription())
                .status("DRAFT")
                .itemCount(0)
                .createdBy(createdBy)
                .updatedBy(createdBy)
                .build();
        datasetMapper.insert(ds);
        return ds.getId();
    }

    @Override
    public List<GoldDatasetVO> list(String kbId, String status) {
        LambdaQueryWrapper<GoldDatasetDO> q = new LambdaQueryWrapper<>();
        if (kbId != null && !kbId.isBlank()) {
            q.eq(GoldDatasetDO::getKbId, kbId);
        }
        if (status != null && !status.isBlank()) {
            q.eq(GoldDatasetDO::getStatus, status);
        }
        q.orderByDesc(GoldDatasetDO::getCreateTime);
        return datasetMapper.selectList(q).stream().map(this::toVO).toList();
    }

    @Override
    public GoldDatasetVO detail(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        return toVO(ds);
    }

    @Override
    public void activate(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        if (!"DRAFT".equals(ds.getStatus())) {
            throw new ClientException("can only activate DRAFT dataset, current status=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        long approved = countApproved(datasetId);
        if (approved == 0) {
            throw new ClientException("no APPROVED items; cannot activate", BaseErrorCode.CLIENT_ERROR);
        }
        long pending = countPending(datasetId);
        if (pending > 0) {
            // spec §10 gotcha #3：ACTIVE 下不允许增删/改 item。若激活时还有 PENDING，这些条目会被永久锁死
            throw new ClientException(pending + " items still PENDING; review all before activating",
                    BaseErrorCode.CLIENT_ERROR);
        }
        ds.setStatus("ACTIVE");
        ds.setItemCount((int) approved);
        datasetMapper.updateById(ds);
    }

    @Override
    public void archive(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        if (!"ACTIVE".equals(ds.getStatus())) {
            throw new ClientException("can only archive ACTIVE dataset, current status=" + ds.getStatus(),
                    BaseErrorCode.CLIENT_ERROR);
        }
        ds.setStatus("ARCHIVED");
        datasetMapper.updateById(ds);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void delete(String datasetId) {
        GoldDatasetDO ds = required(datasetId);
        if ("ACTIVE".equals(ds.getStatus())) {
            throw new ClientException("ACTIVE dataset must be archived before delete", BaseErrorCode.CLIENT_ERROR);
        }
        // 级联软删子 item——避免 orphan 数据；两个 @TableLogic 都会命中
        itemMapper.delete(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId));
        datasetMapper.deleteById(datasetId);
    }

    private GoldDatasetDO required(String datasetId) {
        GoldDatasetDO ds = datasetMapper.selectById(datasetId);
        if (ds == null || Objects.equals(ds.getDeleted(), 1)) {
            throw new ClientException("dataset not found: " + datasetId, BaseErrorCode.CLIENT_ERROR);
        }
        return ds;
    }

    private long countApproved(String datasetId) {
        return itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .eq(GoldItemDO::getReviewStatus, "APPROVED"));
    }

    private long countPending(String datasetId) {
        return itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId)
                .eq(GoldItemDO::getReviewStatus, "PENDING"));
    }

    private long countAll(String datasetId) {
        return itemMapper.selectCount(new LambdaQueryWrapper<GoldItemDO>()
                .eq(GoldItemDO::getDatasetId, datasetId));
    }

    private GoldDatasetVO toVO(GoldDatasetDO ds) {
        long total = countAll(ds.getId());
        long approved = countApproved(ds.getId());
        long pending = countPending(ds.getId());
        return new GoldDatasetVO(
                ds.getId(),
                ds.getKbId(),
                ds.getName(),
                ds.getDescription(),
                ds.getStatus(),
                (int) approved,
                (int) pending,
                (int) total,
                ds.getCreateTime(),
                ds.getUpdateTime()
        );
    }
}
