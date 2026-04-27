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

package com.nageoffer.ai.ragent.user.service.support;

/**
 * 项目内**唯一**把 {@code UserContext} / {@code UserProfileLoader} 转成
 * {@link KbAccessSubject} 的入口。所有权限决策路径取 subject 必须经此。
 *
 * <p>PR3 不变量：calculator 类禁止 import UserContext —— 通过 factory
 * 把 ThreadLocal 触点收敛到一个实现类内。
 */
public interface KbAccessSubjectFactory {

    /**
     * 构造当前登录主体的 subject。
     *
     * @throws IllegalStateException 当 {@code UserContext.isSystem() == true};
     *         系统态走 {@code bypassIfSystemOrAssertActor} 早返回,不应到达 calculator 层
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException
     *         当未登录或 userId 缺失（fail-closed,与 PR1 invariant B 一致）
     */
    KbAccessSubject currentOrThrow();

    /**
     * 构造指定目标用户的 subject。用于 admin-views-target 路径
     * （如 {@code AccessServiceImpl.listUserKbGrants}）。
     *
     * @throws com.nageoffer.ai.ragent.framework.exception.ClientException
     *         当目标用户不存在
     */
    KbAccessSubject forTargetUser(String userId);
}
