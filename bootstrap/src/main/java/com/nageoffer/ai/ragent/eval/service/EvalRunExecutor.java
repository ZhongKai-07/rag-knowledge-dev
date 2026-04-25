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
 * 占位接口 — Task 9 改为 class 实现，签名不变。
 *
 * <p>分离 startRun（编排）与 runInternal（执行）的目的是单测可用 mock 替换执行体，
 * 让 EvalRunServiceImplTest 只验证 startRun 的入参校验、并发互斥与提交语义，
 * 不耦合 chat / RAGAS / 落库等下游真实路径。
 */
public interface EvalRunExecutor {

    /**
     * 执行已 INSERT 的 run（status=PENDING）：调 chat、调 RAGAS、写 result、收尾。
     *
     * @param runId            雪花，已存在于 t_eval_run
     * @param principalUserId  触发人，审计 / system AccessScope 兜底
     */
    void runInternal(String runId, String principalUserId);
}
