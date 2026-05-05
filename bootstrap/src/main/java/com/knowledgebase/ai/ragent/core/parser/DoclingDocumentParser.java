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

import com.knowledgebase.ai.ragent.framework.exception.ServiceException;
import com.knowledgebase.ai.ragent.infra.parser.docling.DoclingClient;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Docling 解析器（Strategy）。
 *
 * <p>仅当 {@code docling.service.enabled=true} 时注册为 bean——下游 {@link DocumentParserSelector}
 * 在 {@code @PostConstruct} / 构造期发现 {@link ParserType#DOCLING} 已存在，
 * 自动把 ENHANCED 路径的 primary 从 Tika 切到本类，fallback 仍是 Tika。
 *
 * <p>失败语义：把任何 IO / 远端异常包成 {@link RuntimeException}，由
 * {@link FallbackParserDecorator} 捕获后 stamp {@code parse_fallback_reason=primary_failed}
 * 并兜底回 Tika。本类不做 retry，也不读 fallback engine。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "docling.service", name = "enabled", havingValue = "true")
public class DoclingDocumentParser implements DocumentParser {

    private final DoclingClient client;
    private final DoclingResponseAdapter adapter;

    @Override
    public String getParserType() {
        return ParserType.DOCLING.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }
        String fileName = resolveFileName(options);
        try {
            DoclingConvertResponse resp = client.convert(content, fileName);
            return adapter.toParseResult(resp);
        } catch (IOException e) {
            // 包成 RuntimeException 让 FallbackParserDecorator 捕获、降级回 Tika 并 stamp metadata
            throw new ServiceException("Docling 解析失败: " + e.getMessage());
        }
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try {
            byte[] bytes = stream.readAllBytes();
            return parse(bytes, null, Map.of("fileName", fileName == null ? "input" : fileName))
                    .text();
        } catch (IOException e) {
            throw new ServiceException("读取文件失败: " + fileName);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        if (mimeType == null) return true;
        // Docling 适合结构化文档（PDF/Word/Excel/PPT/HTML/图片）；纯文本 / Markdown 用 Tika 更快。
        return !mimeType.startsWith("text/plain") && !mimeType.equals("text/markdown");
    }

    private static String resolveFileName(Map<String, Object> options) {
        if (options == null) return "input";
        Object v = options.get("fileName");
        return v instanceof String s && !s.isBlank() ? s : "input";
    }
}
