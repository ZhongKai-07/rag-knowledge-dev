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

package com.nageoffer.ai.ragent.infra.chat;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器
 * <p>
 * 探测阶段将所有回调事件缓存为 Runnable，避免失败模型的内容污染下游输出。
 * 首包成功后自动提交（commit），按原始顺序回放缓存并切换为实时转发。
 * <p>
 * 使用 Runnable lambda 而非类型化事件记录，天然兼容 StreamCallback 的所有方法
 * （包括 onTokenUsage 等扩展方法），无需维护事件类型枚举。
 */
final class ProbeStreamBridge implements StreamCallback {

    private final StreamCallback downstream;
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    private final Object lock = new Object();
    private final List<Runnable> buffer = new ArrayList<>();
    private volatile boolean committed;

    ProbeStreamBridge(StreamCallback downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onContent(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }

    @Override
    public void onThinking(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    @Override
    public void onTokenUsage(TokenUsage usage) {
        bufferOrDispatch(() -> downstream.onTokenUsage(usage));
    }

    @Override
    public void onComplete() {
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    @Override
    public void onError(Throwable t) {
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        ProbeResult result;
        try {
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        if (result.isSuccess()) {
            commit();
        }
        return result;
    }

    private void commit() {
        synchronized (lock) {
            if (committed) {
                return;
            }
            committed = true;
            buffer.forEach(Runnable::run);
        }
    }

    private void bufferOrDispatch(Runnable action) {
        boolean dispatchNow;
        synchronized (lock) {
            dispatchNow = committed;
            if (!dispatchNow) {
                buffer.add(action);
            }
        }
        if (dispatchNow) {
            action.run();
        }
    }

    @Getter
    static class ProbeResult {

        enum Type { SUCCESS, ERROR, TIMEOUT, NO_CONTENT }

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        static ProbeResult error(Throwable t) {
            return new ProbeResult(Type.ERROR, t);
        }

        static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
