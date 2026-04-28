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

package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

/**
 * 生产请求路径 UserContext → RetrievalScope 唯一构造入口 (PR4-1 不变量).
 *
 * <p>review P1#1: 接口 <b>不接受</b> LoginUser 参数 — builder 内部读 UserContext,
 * 单一 ThreadLocal 触点, 与 PR3 KbAccessSubjectFactory.currentOrThrow() 同模式.
 * 双参 build(LoginUser, String) 让 caller 有两种身份来源, 任何漂移都会让内部
 * KbReadAccessPort (current-user-only) 与外部 LoginUser 算出不一致 scope.
 *
 * <p>异步/系统态路径 (如 {@code EvalRunExecutor}) 不走此 builder,
 * 直接用 {@link RetrievalScope#all(String)} sentinel.
 */
public interface RetrievalScopeBuilder {

    /**
     * 构建当前请求的检索 scope.
     *
     * <p>决策顺序 (fail-closed 优先):
     * <ol>
     *   <li>{@code requestedKbId != null} → 先调 {@code kbReadAccess.checkReadAccess(kbId)}:
     *       无 user → 抛 {@code ClientException("missing user context")} (PR1 不变量 B);
     *       无权限 → 抛 {@code ClientException("无权访问")}.
     *       <b>不</b>因 user==null 短路为 empty (review P2#1, PR1 不变量 C HTTP 语义不变)</li>
     *   <li>requestedKbId == null 且 user/userId == null → {@link RetrievalScope#empty()}</li>
     *   <li>SUPER_ADMIN → {@link RetrievalScope#all(String)} (kbSecurityLevels 留空)</li>
     *   <li>其他 → AccessScope.ids(...) + 一次性算齐 getMaxSecurityLevelsForKbs(ids)</li>
     * </ol>
     *
     * @param requestedKbId nullable, 单 KB 定向时非空
     * @return 不可变 RetrievalScope; 越权抛 ClientException
     */
    RetrievalScope build(String requestedKbId);
}
