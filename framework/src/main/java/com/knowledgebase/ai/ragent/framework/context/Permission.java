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

package com.knowledgebase.ai.ragent.framework.context;

/**
 * 知识库访问权限级别。
 *
 * <p>按照权限大小单调递增：
 * <ol start="0">
 *     <li>{@link #READ}：可对 KB 做问答/读取</li>
 *     <li>{@link #WRITE}：可上传/删除文档（DEPT_ADMIN 默认拥有）</li>
 *     <li>{@link #MANAGE}：可删除 KB 本身、管理 KB 的角色授权（SUPER_ADMIN 默认拥有）</li>
 * </ol>
 *
 * <p>{@link #ordinal()} 的顺序反映权限大小，可用于 {@code minPermission} 比较。
 */
public enum Permission {
    READ,
    WRITE,
    MANAGE
}
