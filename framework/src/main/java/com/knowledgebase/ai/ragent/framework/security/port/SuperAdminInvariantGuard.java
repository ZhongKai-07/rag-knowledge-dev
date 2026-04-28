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

package com.knowledgebase.ai.ragent.framework.security.port;

/**
 * Last SUPER_ADMIN 系统级硬不变量守卫（Decision 3-M）。
 * 所有改变 SUPER_ADMIN 数量的 mutation 路径在执行前必须调用此守卫。
 */
public interface SuperAdminInvariantGuard {

    /** 当前系统内有效 SUPER_ADMIN 用户数量。*/
    int countActiveSuperAdmins();

    /**
     * Post-mutation 模拟器：返回 mutation 执行后剩余的有效 SUPER_ADMIN 用户数量。
     * 调用方用 {@code simulateActiveSuperAdminCountAfter(intent) < 1} 判断是否拒绝。
     */
    int simulateActiveSuperAdminCountAfter(SuperAdminMutationIntent intent);
}
