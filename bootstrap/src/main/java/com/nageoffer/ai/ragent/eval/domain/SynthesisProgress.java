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

package com.nageoffer.ai.ragent.eval.domain;

/**
 * 合成任务进度快照——进程内可读，不入库。
 * status: IDLE / RUNNING / COMPLETED / FAILED
 */
public record SynthesisProgress(
        String status,
        int total,
        int processed,
        int failed,
        String error
) {
    public static SynthesisProgress idle() {
        return new SynthesisProgress("IDLE", 0, 0, 0, null);
    }

    public static SynthesisProgress running(int total, int processed, int failed) {
        return new SynthesisProgress("RUNNING", total, processed, failed, null);
    }

    public static SynthesisProgress completed(int total, int processed, int failed) {
        return new SynthesisProgress("COMPLETED", total, processed, failed, null);
    }

    public static SynthesisProgress failed(int total, int processed, int failed, String error) {
        return new SynthesisProgress("FAILED", total, processed, failed, error);
    }
}
