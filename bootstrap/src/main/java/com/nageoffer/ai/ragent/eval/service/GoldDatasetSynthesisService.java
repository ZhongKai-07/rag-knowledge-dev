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

package com.nageoffer.ai.ragent.eval.service;

/**
 * Gold Set 合成服务。
 * 异步入口 trigger(datasetId, count) 提交到 evalExecutor；
 * 同步入口 runSynthesisSync 用于单测和 controller 显式阻塞场景（生产不用）。
 */
public interface GoldDatasetSynthesisService {

    /** 异步触发合成——立即返回，任务进 evalExecutor；通过 SynthesisProgressTracker 轮询进度。*/
    void trigger(String datasetId, int count, String principalUserId);

    /** 同步执行——测试或管理脚本用；生产 controller 不要直调，用 trigger。*/
    void runSynthesisSync(String datasetId, int count, String principalUserId);
}
