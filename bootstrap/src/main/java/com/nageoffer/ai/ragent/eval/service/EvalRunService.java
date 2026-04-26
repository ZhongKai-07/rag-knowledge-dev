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

import com.nageoffer.ai.ragent.eval.dao.entity.EvalRunDO;

import java.util.List;

/**
 * eval 域 - 评估运行编排服务（spec §5）。
 */
public interface EvalRunService {

    /**
     * 触发新评估运行。
     *
     * @param datasetId        gold dataset id（必须 status=ACTIVE）
     * @param principalUserId  当前 SUPER_ADMIN 用户（审计字段）
     * @return runId 雪花
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException 校验失败
     */
    String startRun(String datasetId, String principalUserId);

    EvalRunDO getRun(String runId);

    List<EvalRunDO> listRuns(String datasetId);
}
