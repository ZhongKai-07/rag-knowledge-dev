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

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 知识库元数据读取端口。
 * 消除 user 域对 knowledge 域 Mapper 的直接依赖（反向依赖）。
 * 实现放 knowledge 域（KbMetadataReaderImpl）。
 */
public interface KbMetadataReader {

    /** 获取 KB 的所属部门 ID。KB 不存在时返回 null。*/
    String getKbDeptId(String kbId);

    /** 获取文档所属 KB 的 ID。文档不存在时返回 null。*/
    String getKbIdOfDoc(String docId);

    /** 指定 KB 是否存在（已删除视为不存在）。*/
    boolean kbExists(String kbId);

    /** 返回所有未删除 KB 的 ID 集合（SUPER_ADMIN 全量可见路径使用）。*/
    Set<String> listAllKbIds();

    /** 返回指定部门下所有未删除 KB 的 ID 集合。*/
    Set<String> listKbIdsByDeptId(String deptId);

    /** 过滤出 kbIds 中实际存在（未删除）的 KB ID。*/
    Set<String> filterExistingKbIds(Collection<String> kbIds);

    /** 过滤出 kbIds 中 dept_id == deptId 的 KB ID。*/
    Set<String> filterKbIdsByDept(Collection<String> kbIds, String deptId);

    /**
     * 获取 KB 的 collection_name（物理向量索引名）。
     *
     * @return collection_name；KB 不存在、已软删或 collection_name 为空时返回 null
     */
    String getCollectionName(String kbId);

    /**
     * 批量解析 kbIds 对应的 collection_name（过滤未删除且 collection_name 非空）。
     *
     * @return 仅包含存在且 collection_name 有效的 kbId；返回 map 不可变
     */
    Map<String, String> getCollectionNames(Collection<String> kbIds);
}
