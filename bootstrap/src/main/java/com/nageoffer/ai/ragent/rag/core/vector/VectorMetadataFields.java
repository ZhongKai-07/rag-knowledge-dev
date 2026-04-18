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

package com.nageoffer.ai.ragent.rag.core.vector;

/**
 * OpenSearch metadata 字段的规范化常量。单一真相源：写路径、读路径、
 * 过滤器必须引用同一常量，避免拼写漂移悄悄破坏 RBAC。
 *
 * <p>Mapping DDL 维护在 {@code OpenSearchVectorStoreAdmin#buildMappingJson} 的 JSON 文本块里
 * (text block 不能直接引用常量, 改字段名时需同步两处)。
 */
public final class VectorMetadataFields {

    /** 所属知识库 ID. AuthzPostProcessor 以此做白名单校验. */
    public static final String KB_ID = "kb_id";

    /** 文档安全等级 (0=PUBLIC … 3=RESTRICTED). AuthzPostProcessor 以此做天花板校验. */
    public static final String SECURITY_LEVEL = "security_level";

    private VectorMetadataFields() {
    }
}
