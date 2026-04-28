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

package com.knowledgebase.ai.ragent.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.knowledgebase.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link KbMetadataReader} 的默认实现，住在 knowledge 域。
 *
 * <p>所有查询依赖 MyBatis Plus {@code @TableLogic} 自动追加 {@code deleted=0}，
 * 调用方不必也不应手动再过滤 deleted 字段。
 *
 * <p>此类的存在是为了消除 user 域对 knowledge Mapper 的反向依赖：
 * 以前 user 域权限实现直接持有 {@code KnowledgeBaseMapper} +
 * {@code KnowledgeDocumentMapper}，造成 user → knowledge 的跨域穿透。
 */
@Service
@RequiredArgsConstructor
public class KbMetadataReaderImpl implements KbMetadataReader {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Override
    public String getKbDeptId(String kbId) {
        if (kbId == null) {
            return null;
        }
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        return kb != null ? kb.getDeptId() : null;
    }

    @Override
    public String getKbIdOfDoc(String docId) {
        if (docId == null) {
            return null;
        }
        KnowledgeDocumentDO doc = knowledgeDocumentMapper.selectById(docId);
        return doc != null ? doc.getKbId() : null;
    }

    @Override
    public boolean kbExists(String kbId) {
        if (kbId == null) {
            return false;
        }
        Long count = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getId, kbId));
        return count != null && count > 0;
    }

    @Override
    public Set<String> listAllKbIds() {
        return knowledgeBaseMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                                .select(KnowledgeBaseDO::getId))
                .stream()
                .map(KnowledgeBaseDO::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> listKbIdsByDeptId(String deptId) {
        if (deptId == null) {
            return Set.of();
        }
        return knowledgeBaseMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                                .eq(KnowledgeBaseDO::getDeptId, deptId)
                                .select(KnowledgeBaseDO::getId))
                .stream()
                .map(KnowledgeBaseDO::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> filterExistingKbIds(Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Set.of();
        }
        return knowledgeBaseMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                                .in(KnowledgeBaseDO::getId, kbIds)
                                .select(KnowledgeBaseDO::getId))
                .stream()
                .map(KnowledgeBaseDO::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> filterKbIdsByDept(Collection<String> kbIds, String deptId) {
        if (kbIds == null || kbIds.isEmpty() || deptId == null) {
            return Set.of();
        }
        return knowledgeBaseMapper.selectList(
                        Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                                .in(KnowledgeBaseDO::getId, kbIds)
                                .eq(KnowledgeBaseDO::getDeptId, deptId)
                                .select(KnowledgeBaseDO::getId))
                .stream()
                .map(KnowledgeBaseDO::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public String getCollectionName(String kbId) {
        if (kbId == null) {
            return null;
        }
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            return null;
        }
        String name = kb.getCollectionName();
        return name != null && !name.isBlank() ? name : null;
    }

    @Override
    public Map<String, String> getCollectionNames(Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeBaseDO> kbs = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .in(KnowledgeBaseDO::getId, kbIds)
                        .select(KnowledgeBaseDO::getId, KnowledgeBaseDO::getCollectionName));
        Map<String, String> result = new HashMap<>(kbs.size() * 2);
        for (KnowledgeBaseDO kb : kbs) {
            String name = kb.getCollectionName();
            if (name != null && !name.isBlank()) {
                result.put(kb.getId(), name);
            }
        }
        return Map.copyOf(result);
    }
}
