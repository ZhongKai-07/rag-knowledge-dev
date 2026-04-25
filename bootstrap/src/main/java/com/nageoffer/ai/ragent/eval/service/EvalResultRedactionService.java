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

import com.nageoffer.ai.ragent.eval.domain.RetrievedChunkSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * eval 域 - 结果脱敏服务（EVAL-3 硬合并门禁，spec §15.1）。
 *
 * <p>所有暴露 {@code retrieved_chunks} 的 API 必须经过此服务。
 * 列表/趋势 API 不返 retrieved_chunks（不需要 redact）；
 * 单 run 结果 / drill-down API 必经此 service。
 *
 * <p>SUPER_ADMIN 通常以 {@link Integer#MAX_VALUE} 调用 → 全读。
 * EVAL-3 落地前所有 controller @SaCheckRole("SUPER_ADMIN")，本 service 仍提供
 * 通用 ceiling 接口，便于未来放开 AnyAdmin 时无需改 service 调用方。
 *
 * <p>{@code securityLevel == null} 视为 0（最低密级），永远可读。
 */
@Slf4j
@Service
public class EvalResultRedactionService {

    public static final String REDACTED = "[REDACTED]";

    public List<RetrievedChunkSnapshot> redact(List<RetrievedChunkSnapshot> chunks, int principalCeiling) {
        if (chunks == null) return List.of();
        return chunks.stream()
                .map(c -> {
                    int level = c.securityLevel() == null ? 0 : c.securityLevel();
                    if (level > principalCeiling) {
                        return new RetrievedChunkSnapshot(c.chunkId(), c.docId(), c.docName(),
                                c.securityLevel(), REDACTED, c.score());
                    }
                    return c;
                })
                .toList();
    }
}
