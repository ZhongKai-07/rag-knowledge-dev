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

package com.knowledgebase.ai.ragent.knowledge.service.support;

import com.knowledgebase.ai.ragent.core.parser.ParseMode;
import org.springframework.stereotype.Component;

/**
 * 解析模式决策（Phase 2.5 MVP）。
 * <p>
 * MVP 行为：完全尊重用户在上传 dialog 中的选择；缺省 / 空值 → BASIC。
 * 不在此处对任何 KB 强制 ENHANCED：Collateral 等"高保真场景"由用户主动勾选 增强解析，
 * 后端不替用户决策。该约束是 PR 4 的产品决定，避免在 KB 上隐式产生处理路径分歧。
 * </p>
 * <p>
 * 该类作为决策 seam 保留，未来可在不动 upload controller 的情况下接入：
 * <ul>
 *   <li>{@code TextLayerQualityDetector} — 自动建议 ENHANCED；</li>
 *   <li>{@code OcrFallbackParser} — 扫描件路由；</li>
 *   <li>KB 类型 / dept 策略 — 强制或推荐 ENHANCED。</li>
 * </ul>
 * </p>
 */
@Component
public class ParseModePolicy {

    public ParseModeDecision decide(String kbId, ParseMode requested) {
        ParseMode effective = requested == null ? ParseMode.BASIC : requested;
        String reason = requested == null ? "default_basic" : "user_choice";
        return new ParseModeDecision(effective, reason);
    }
}
