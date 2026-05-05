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

package com.knowledgebase.ai.ragent.infra.parser.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgebase.ai.ragent.infra.parser.AbstractRemoteParser;
import com.knowledgebase.ai.ragent.infra.parser.docling.dto.DoclingConvertResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Docling Python 服务的 HTTP 客户端。
 *
 * <p>仅当 {@code docling.service.enabled=true} 时注册为 bean；否则下游
 * {@code DoclingDocumentParser} 同样不被注册，{@code DocumentParserSelector.buildEnhancedParser()}
 * 自动构造 degraded {@code FallbackParserDecorator}（primary == fallback == Tika）。
 */
@Slf4j
@Component
@EnableConfigurationProperties(DoclingClientProperties.class)
@ConditionalOnProperty(prefix = "docling.service", name = "enabled", havingValue = "true")
public class DoclingClient extends AbstractRemoteParser<DoclingConvertResponse> {

    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final long HEALTH_TIMEOUT_MS = 5_000L;

    private final DoclingClientProperties props;
    private final OkHttpClient healthClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DoclingClient(OkHttpClient sharedClient, DoclingClientProperties props) {
        super(sharedClient, Duration.ofMillis(props.getTimeoutMs()));
        this.props = props;
        // health 用独立短超时 client，避免被 convert 的长超时拖累；共享连接池
        this.healthClient = sharedClient
                .newBuilder()
                .readTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 上传文件给 Docling 转换。失败抛 {@link IOException}（由 {@code FallbackParserDecorator} 兜底回 Tika）。
     *
     * <p>关键 multipart 字段（v0.5.1 实测）：
     * <ul>
     *   <li>{@code files}：数组字段名，<strong>不</strong>是 {@code file}</li>
     *   <li>{@code to_formats=json} + {@code to_formats=text}：必须显式开启，否则 {@code json_content} 为 null，
     *       下游拿不到结构化版面</li>
     * </ul>
     */
    public DoclingConvertResponse convert(byte[] content, String fileName) throws IOException {
        RequestBody filePart = RequestBody.create(content, OCTET_STREAM);
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", fileName == null ? "input" : fileName, filePart)
                .addFormDataPart("to_formats", "json")
                .addFormDataPart("to_formats", "text")
                .build();
        Request request = new Request.Builder()
                .url(props.getHost() + props.getConvertEndpoint())
                .post(body)
                .build();
        return execute(request);
    }

    /**
     * 健康检查；任何异常都视为不健康并 WARN，不向上抛。
     *
     * <p>{@code /health} 返回 {@code {"status":"ok"}}，与 {@code convert} 响应结构不同 —— 不走父类
     * {@code execute()} 模板，直接用独立短超时 client 做一次 GET 看是否 2xx。
     */
    public boolean isHealthy() {
        Request request = new Request.Builder()
                .url(props.getHost() + props.getHealthEndpoint())
                .get()
                .build();
        try (Response resp = healthClient.newCall(request).execute()) {
            return resp.isSuccessful();
        } catch (Exception e) {
            log.warn("Docling health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected DoclingConvertResponse parseResponse(ResponseBody body) throws IOException {
        try {
            return objectMapper.readValue(body.bytes(), DoclingConvertResponse.class);
        } catch (Exception e) {
            throw new IOException("Failed to parse Docling response", e);
        }
    }
}
