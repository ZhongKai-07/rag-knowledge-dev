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

package com.knowledgebase.ai.ragent.core.parser.quality;

/**
 * 文本层质量检测结果（Phase 2.5 仅保留接口形态，不参与决策）。
 *
 * @param textLayerType        NATIVE_TEXT / OCR / MIXED / UNKNOWN
 * @param recommendsEnhanced   是否建议升级到 ENHANCED 解析
 * @param recommendsOcrFallback 是否建议走 OCR fallback
 * @param confidence           置信度（可空）
 * @param reason               诊断原因
 */
public record TextLayerQuality(
        String textLayerType,
        boolean recommendsEnhanced,
        boolean recommendsOcrFallback,
        Double confidence,
        String reason) {

    public static TextLayerQuality unknown() {
        return new TextLayerQuality("UNKNOWN", false, false, null, "mvp_not_evaluated");
    }
}
