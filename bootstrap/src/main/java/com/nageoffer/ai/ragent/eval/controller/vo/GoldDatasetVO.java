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

package com.nageoffer.ai.ragent.eval.controller.vo;

import java.util.Date;

public record GoldDatasetVO(
        String id,
        String kbId,
        String name,
        String description,
        String status,           // DRAFT / ACTIVE / ARCHIVED
        int itemCount,           // 已 APPROVED 条数（合成后、审核前为 0）
        int pendingItemCount,    // PENDING 条数；UI 激活按钮 gate 用
        int totalItemCount,      // 合成产生的全部条数，含 PENDING/REJECTED
        Date createTime,
        Date updateTime
) {
}
