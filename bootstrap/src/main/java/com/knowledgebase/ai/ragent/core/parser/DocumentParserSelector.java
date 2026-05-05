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

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档解析器选择器（策略模式）
 * <p>
 * 负责管理和选择合适的文档解析策略。根据解析器类型或 MIME 类型，
 * 动态选择最合适的解析器实现，体现了策略模式的核心思想
 * </p>
 * <p>
 * 支持两种选择方式：
 * <ul>
 *   <li>按类型选择：通过 {@link #select(String)} 指定解析器类型（如 {@link ParserType#TIKA}, {@link ParserType#MARKDOWN}）</li>
 *   <li>按 MIME 类型选择：通过 {@link #selectByMimeType(String)} 自动匹配支持该 MIME 类型的解析器</li>
 * </ul>
 * </p>
 */
@Component
public class DocumentParserSelector {

    private final List<DocumentParser> strategies;
    private final Map<String, DocumentParser> strategyMap;
    /** ENHANCED 路径返回的实例（singleton，构造期一次性建好）。见 {@link #buildEnhancedParser()}。*/
    private final DocumentParser enhancedParser;

    public DocumentParserSelector(List<DocumentParser> parsers) {
        // 显式禁止把 FallbackParserDecorator 当 DocumentParser bean 注入：decorator 的
        // getParserType() 返回 primaryName（如 "Docling"），如果 Spring 把 decorator 也收进
        // parsers 列表，会与真正的 Docling parser 冲突 / 顺序不确定地相互遮蔽。
        for (DocumentParser p : parsers) {
            if (p instanceof FallbackParserDecorator) {
                throw new IllegalArgumentException(
                        "FallbackParserDecorator must not be registered as a DocumentParser bean — it is constructed "
                                + "internally by selectByParseMode(ENHANCED). Found: " + p.getClass().getName());
            }
        }
        this.strategies = parsers;
        this.strategyMap = parsers.stream()
                .collect(Collectors.toMap(
                        DocumentParser::getParserType, Function.identity(), (existing, replacement) -> existing));
        this.enhancedParser = buildEnhancedParser();
    }

    /**
     * 根据解析器类型选择解析策略
     *
     * @param parserType 解析器类型（如 {@link ParserType#TIKA}, {@link ParserType#MARKDOWN}）
     * @return 解析器实例，如果不存在则返回 null
     */
    public DocumentParser select(String parserType) {
        return strategyMap.get(parserType);
    }

    /**
     * 按解析模式选择解析器。
     * <ul>
     *   <li>{@link ParseMode#BASIC} → Tika</li>
     *   <li>{@link ParseMode#ENHANCED} → 始终返回 {@link FallbackParserDecorator} 包装的解析器，
     *       primary = Docling（若已注册）/ 否则 Tika，fallback 始终是 Tika。这保证：
     *       <ul>
     *         <li>Docling 不可用时 ingestion 不会整链路失败；</li>
     *         <li>{@code parse_engine_requested = "Docling"} 与 {@code parse_engine_actual}
     *             metadata 永远存在，前端可读以决定是否提示「增强解析尚未启用，已使用基础解析」。</li>
     *       </ul>
     *   </li>
     * </ul>
     * PR 5 起注册真正的 {@code DoclingDocumentParser} 后，primary 自动切换为 Docling，
     * 失败时 decorator 兜底回 Tika 并 stamp {@code parse_fallback_reason=primary_failed}。
     */
    public DocumentParser selectByParseMode(ParseMode mode) {
        return switch (mode) {
            case BASIC -> select(ParserType.TIKA.getType());
            case ENHANCED -> enhancedParser;
        };
    }

    /**
     * 构造期一次性建好 ENHANCED 路径对应的 decorator。Selector 是单例 Spring bean，
     * decorator 自身无可变状态（{@code stamp()} 内 HashMap 是方法局部变量），多线程并发调用安全。
     * 这样可避免每次 ingestion 调用 {@link #selectByParseMode(ParseMode)} 都重新分配
     * decorator + 在 degraded 模式下重复打 INFO 日志。
     */
    private DocumentParser buildEnhancedParser() {
        DocumentParser tika = select(ParserType.TIKA.getType());
        DocumentParser docling = strategyMap.get(ParserType.DOCLING.getType());
        if (docling == null) {
            return FallbackParserDecorator.degraded(
                    tika,
                    ParserType.DOCLING.getType(),
                    ParserType.TIKA.getType(),
                    FallbackParserDecorator.REASON_PRIMARY_UNAVAILABLE);
        }
        return new FallbackParserDecorator(docling, tika, ParserType.DOCLING.getType(), ParserType.TIKA.getType());
    }

    /**
     * 根据 MIME 类型自动选择合适的解析策略
     * <p>
     * 遍历所有可用的解析器，返回第一个支持该 MIME 类型的解析器。
     * 如果没有找到匹配的解析器，则返回默认的 Tika 解析器。
     * </p>
     *
     * @param mimeType MIME 类型（如 "application/pdf", "text/markdown"）
     * @return 支持该 MIME 类型的解析器，如果没有则返回默认的 Tika 解析器
     */
    public DocumentParser selectByMimeType(String mimeType) {
        return strategies.stream()
                .filter(parser -> parser.supports(mimeType))
                .findFirst()
                .orElseGet(() -> select(ParserType.TIKA.getType()));
    }

    /**
     * 获取所有可用的解析策略
     *
     * @return 解析器列表
     */
    public List<DocumentParser> getAllStrategies() {
        return List.copyOf(strategies);
    }

    /**
     * 获取所有解析器类型
     *
     * @return 解析器类型列表
     */
    public List<String> getAvailableTypes() {
        return strategies.stream()
                .map(DocumentParser::getParserType)
                .toList();
    }
}
