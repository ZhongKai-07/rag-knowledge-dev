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
 * 文本层质量检测端口（Phase 2.5 MVP 仅保留接口形态）。
 * <p>
 * 未来实现可在 {@code ParseModePolicy} 决策前注入：扫描件 / 低质量文本层
 * 自动建议升级到 ENHANCED 或路由到 {@code OcrFallbackParser}。
 * </p>
 */
public interface TextLayerQualityDetector {

    TextLayerQuality detect(byte[] content, String mimeType);
}
