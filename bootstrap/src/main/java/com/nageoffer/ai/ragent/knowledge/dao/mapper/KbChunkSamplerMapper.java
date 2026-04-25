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

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * chunk 采样专用 Mapper——JOIN document 表，固化 spec §6.1 过滤条件。
 * 不绑 BaseMapper——纯 @Select，走 @MapperScan 扫描即可装配。
 */
@Mapper
public interface KbChunkSamplerMapper {

    @Select("SELECT c.id AS chunk_id, c.content AS chunk_text, c.doc_id AS doc_id, d.doc_name AS doc_name "
            + "FROM t_knowledge_chunk c "
            + "JOIN t_knowledge_document d ON c.doc_id = d.id "
            + "WHERE c.kb_id = #{kbId} "
            + "AND c.deleted = 0 AND c.enabled = 1 "
            + "AND d.deleted = 0 AND d.enabled = 1 AND d.status = 'success' "
            + "ORDER BY c.doc_id, RANDOM() "
            + "LIMIT #{hardLimit}")
    List<Map<String, Object>> sampleRaw(@Param("kbId") String kbId, @Param("hardLimit") int hardLimit);
}
