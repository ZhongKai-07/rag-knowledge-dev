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

package com.knowledgebase.ai.ragent.eval.service;

import com.knowledgebase.ai.ragent.eval.domain.SynthesisProgress;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内合成进度追踪器。
 * 不持久化——重启后所有 RUNNING 状态丢失；重启恢复策略见本 PR 文档中的"已知边界"段（backlog EVAL-4）。
 */
@Component
public class SynthesisProgressTracker {

    private final ConcurrentHashMap<String, SynthesisProgress> map = new ConcurrentHashMap<>();

    public void begin(String datasetId, int total) {
        map.put(datasetId, SynthesisProgress.running(total, 0, 0));
    }

    /**
     * 原子占坑——trigger 入口调用；并发第二个请求会 return false 被拒。
     * 使用 putIfAbsent 防"两个请求都看到 existing=0 就各自 execute"的 race。
     * 占坑后 total=0；真正采样完再调 {@link #begin(String, int)} 覆盖 total。
     *
     * <p>一旦占坑，tracker 保留 RUNNING/COMPLETED/FAILED 终态直到重启或显式 clear；
     * 同一 datasetId 第二次 trigger 会被此方法挡住——合成只跑一次，失败后 delete dataset 重建。
     */
    public boolean tryBegin(String datasetId) {
        return map.putIfAbsent(datasetId, SynthesisProgress.running(0, 0, 0)) == null;
    }

    public void update(String datasetId, int processed, int failed) {
        SynthesisProgress prev = map.get(datasetId);
        int total = prev != null ? prev.total() : processed + failed;
        map.put(datasetId, SynthesisProgress.running(total, processed, failed));
    }

    public void complete(String datasetId, int processed, int failed) {
        SynthesisProgress prev = map.get(datasetId);
        int total = prev != null ? prev.total() : processed + failed;
        map.put(datasetId, SynthesisProgress.completed(total, processed, failed));
    }

    public void fail(String datasetId, String error) {
        SynthesisProgress prev = map.get(datasetId);
        int total = prev != null ? prev.total() : 0;
        int processed = prev != null ? prev.processed() : 0;
        int failed = prev != null ? prev.failed() : 0;
        map.put(datasetId, SynthesisProgress.failed(total, processed, failed, error));
    }

    public SynthesisProgress get(String datasetId) {
        return map.getOrDefault(datasetId, SynthesisProgress.idle());
    }

    public boolean isRunning(String datasetId) {
        SynthesisProgress p = map.get(datasetId);
        return p != null && "RUNNING".equals(p.status());
    }
}
