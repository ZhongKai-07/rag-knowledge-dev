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
import com.knowledgebase.ai.ragent.knowledge.enums.ProcessMode;
import org.springframework.stereotype.Component;

/**
 * 把用户面 {@link ParseMode} 映射到内部 {@link ProcessMode}。
 * <ul>
 *   <li>{@code BASIC}    → {@code CHUNK}（快路径，Tika）</li>
 *   <li>{@code ENHANCED} → {@code PIPELINE}（Docling + 结构化分块 + layout metadata）</li>
 * </ul>
 * <p>
 * 这把"chunk vs pipeline + tika vs docling"两个技术轴折叠成一个产品面 toggle，
 * 让前端只需关心"基础 / 增强"两档。
 * </p>
 */
@Component
public final class ParseModeRouter {

    public ProcessMode resolve(ParseMode parseMode) {
        return switch (parseMode) {
            case BASIC -> ProcessMode.CHUNK;
            case ENHANCED -> ProcessMode.PIPELINE;
        };
    }
}
