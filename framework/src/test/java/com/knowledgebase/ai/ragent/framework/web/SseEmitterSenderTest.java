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

package com.knowledgebase.ai.ragent.framework.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SseEmitterSenderTest {

    @Test
    void sendEventAfterCompleteSilentlyReturns() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseEmitterSender sender = new SseEmitterSender(emitter);

        sender.complete();

        assertDoesNotThrow(() -> sender.sendEvent("message", "ignored"));
        assertEquals(0, emitter.sendCount);
    }

    @Test
    void constructorTracksEmitterLifecycleCallbacks() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseEmitterSender sender = new SseEmitterSender(emitter);

        assertNotNull(emitter.completionCallback);
        assertNotNull(emitter.timeoutCallback);
        assertNotNull(emitter.errorCallback);

        emitter.completionCallback.run();
        assertDoesNotThrow(() -> sender.sendEvent("message", "ignored-after-complete"));

        RecordingEmitter timeoutEmitter = new RecordingEmitter();
        SseEmitterSender timeoutSender = new SseEmitterSender(timeoutEmitter);
        timeoutEmitter.timeoutCallback.run();
        assertDoesNotThrow(() -> timeoutSender.sendEvent("message", "ignored-after-timeout"));

        RecordingEmitter errorEmitter = new RecordingEmitter();
        SseEmitterSender errorSender = new SseEmitterSender(errorEmitter);
        errorEmitter.errorCallback.accept(new RuntimeException("client gone"));
        assertDoesNotThrow(() -> errorSender.sendEvent("message", "ignored-after-error"));
    }

    @Test
    void timeoutCallbackRunsOnceWhenEmitterTimesOut() {
        RecordingEmitter emitter = new RecordingEmitter();
        AtomicInteger timeoutCalls = new AtomicInteger();
        new SseEmitterSender(emitter, timeoutCalls::incrementAndGet);

        assertNotNull(emitter.timeoutCallback);
        emitter.timeoutCallback.run();
        emitter.timeoutCallback.run();

        assertEquals(1, timeoutCalls.get());
    }

    private static final class RecordingEmitter extends SseEmitter {
        private Runnable completionCallback;
        private Runnable timeoutCallback;
        private Consumer<Throwable> errorCallback;
        private int sendCount;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            sendCount++;
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            this.completionCallback = callback;
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            this.timeoutCallback = callback;
        }

        @Override
        public synchronized void onError(Consumer<Throwable> callback) {
            this.errorCallback = callback;
        }

        @Override
        public synchronized void complete() {
            // noop
        }
    }
}
