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

package com.knowledgebase.ai.ragent.infra.parser;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 远程解析器客户端的模板方法基类。
 *
 * <p>子类通过 {@link #execute(Request)} 把构造好的 {@link Request} 交给基类，基类统一负责：
 * <ul>
 *   <li>从共享的 {@link OkHttpClient} 派生子 client，覆盖 read/call timeout（共享连接池，不重新建 dispatcher）</li>
 *   <li>HTTP 状态码校验（>=400 抛 {@link IOException}）</li>
 *   <li>把响应体交给子类 {@link #parseResponse(ResponseBody)} 反序列化</li>
 *   <li>耗时埋点 + 失败原因日志</li>
 * </ul>
 *
 * <p>设计选择：
 * <ul>
 *   <li>不实现 retry / circuit-breaker——交给上层决定（{@code FallbackParserDecorator} 已经覆盖降级路径）。</li>
 *   <li>不持有自建 OkHttpClient——共享 {@code rag.config.HttpClientConfig} 暴露的 bean 以复用连接池。</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractRemoteParser<RESP> {

    private final OkHttpClient httpClient;
    private final Duration timeout;

    protected AbstractRemoteParser(OkHttpClient sharedClient, Duration timeout) {
        this.timeout = timeout;
        // 共享 dispatcher / connectionPool，仅覆盖 read / call timeout —— 共享 client 默认 readTimeout=ZERO 不适合远程解析
        this.httpClient = sharedClient
                .newBuilder()
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 执行请求并把响应体交给子类 {@link #parseResponse(ResponseBody)} 解析。
     *
     * @param request 子类构造的 OkHttp 请求
     * @return 子类反序列化后的响应对象
     * @throws IOException 网络错误 / HTTP 4xx-5xx / 反序列化失败
     */
    protected final RESP execute(Request request) throws IOException {
        long t0 = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            int code = response.code();
            if (code >= 400) {
                String preview = previewBody(response.body());
                throw new IOException("Remote parser HTTP " + code + ": " + preview);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Remote parser returned empty body (HTTP " + code + ")");
            }
            return parseResponse(body);
        } finally {
            log.info(
                    "Remote parser call took {} ms (timeout={}ms, url={})",
                    System.currentTimeMillis() - t0,
                    timeout.toMillis(),
                    request.url());
        }
    }

    /**
     * 子类实现：把成功响应的 body 反序列化为 {@code RESP}。
     *
     * @param body 非 null 响应体（基类已校验）
     * @throws IOException 反序列化失败
     */
    protected abstract RESP parseResponse(ResponseBody body) throws IOException;

    private static String previewBody(ResponseBody body) {
        if (body == null) return "<no body>";
        try {
            String s = body.string();
            return s.length() > 512 ? s.substring(0, 512) + "...(truncated)" : s;
        } catch (IOException e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }
}
