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

package com.nageoffer.ai.ragent.eval.service;

import com.nageoffer.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldDatasetVO;

import java.util.List;

public interface GoldDatasetService {
    /** 创建空数据集（DRAFT / item_count=0），返回 datasetId。*/
    String create(CreateGoldDatasetRequest req, String createdBy);

    /** 列出数据集。kbId 可选筛选；status 可选筛选（DRAFT/ACTIVE/ARCHIVED）。*/
    List<GoldDatasetVO> list(String kbId, String status);

    /** 详情（含 totalItemCount 聚合，方便前端判断是否合成过）。*/
    GoldDatasetVO detail(String datasetId);

    /** DRAFT → ACTIVE；前置：存在 ≥1 条 APPROVED item 且 PENDING==0。*/
    void activate(String datasetId);

    /** ACTIVE → ARCHIVED。*/
    void archive(String datasetId);

    /** 软删——仅允许 DRAFT 或 ARCHIVED；ACTIVE 态必须先 archive。*/
    void delete(String datasetId);
}
