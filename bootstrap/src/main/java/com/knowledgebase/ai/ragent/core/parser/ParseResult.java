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

package com.knowledgebase.ai.ragent.core.parser;

import com.knowledgebase.ai.ragent.core.parser.layout.DocumentPageText;
import com.knowledgebase.ai.ragent.core.parser.layout.LayoutTable;

import java.util.List;
import java.util.Map;

/**
 * 文档解析结果。
 *
 * <p>{@code pages} / {@code tables} 在不产出版面信息的引擎（例如 Tika）下为空集合；
 * 产出版面的引擎（例如 Docling）会填充页级文本与版面块/表格，下游阶段可据此引用页/块/bbox。
 *
 * @param text     解析后的文本内容
 * @param metadata 文档元数据（可选）
 * @param pages    页级文本与版面块（可选）
 * @param tables   页级版面表格（可选）
 */
public record ParseResult(
        String text, Map<String, Object> metadata, List<DocumentPageText> pages, List<LayoutTable> tables) {

    public ParseResult {
        if (metadata == null) metadata = Map.of();
        if (pages == null) pages = List.of();
        if (tables == null) tables = List.of();
    }

    /** 向后兼容构造器 —— Tika 与既有调用方保持不变。 */
    public ParseResult(String text, Map<String, Object> metadata) {
        this(text, metadata, List.of(), List.of());
    }

    /**
     * 创建只包含文本的解析结果
     */
    public static ParseResult ofText(String text) {
        return new ParseResult(text == null ? "" : text, Map.of());
    }

    /**
     * 创建包含文本和元数据的解析结果
     */
    public static ParseResult of(String text, Map<String, Object> metadata) {
        return new ParseResult(text == null ? "" : text, metadata == null ? Map.of() : metadata);
    }
}
