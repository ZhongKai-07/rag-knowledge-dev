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

package com.knowledgebase.ai.ragent.core.parser.ocr;

import com.knowledgebase.ai.ragent.core.parser.DocumentParser;

/**
 * 扫描件 / 图片型 PDF 的 OCR fallback 端口（Phase 2.5 MVP 仅保留接口形态）。
 * <p>
 * 未实现也未接入任何 chain。存在的目的是：未来增加 OCR 路径时，
 * 不需要修改 upload controller / parser 选择器契约。
 * </p>
 */
public interface OcrFallbackParser extends DocumentParser {
}
