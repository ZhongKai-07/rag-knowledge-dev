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

package com.nageoffer.ai.ragent.knowledge.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.controller.vo.SpacesStatsVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.KbScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spaces 控制器
 * 提供 Spaces 页面所需的统计数据接口
 */
@RestController
@RequiredArgsConstructor
public class SpacesController {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KbScopeResolver kbScopeResolver;

    /**
     * 获取当前用户可见的知识库数量和文档总数
     */
    @GetMapping("/spaces/stats")
    public Result<SpacesStatsVO> getStats() {
        LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
        AccessScope scope = kbScopeResolver.resolveForRead(user);

        if (scope instanceof AccessScope.All) {
            // Admin sees everything — query total counts directly.
            // Admin may not have explicit role-KB mappings.
            long kbCount = knowledgeBaseMapper.selectCount(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class));
            long totalDocCount = knowledgeDocumentMapper.selectCount(
                    Wrappers.lambdaQuery(KnowledgeDocumentDO.class));
            return Results.success(new SpacesStatsVO(kbCount, totalDocCount));
        }

        if (!(scope instanceof AccessScope.Ids ids)) {
            throw new IllegalStateException("Unsupported AccessScope: " + scope);
        }
        if (ids.kbIds().isEmpty()) {
            return Results.success(new SpacesStatsVO(0L, 0L));
        }

        long kbCount = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .in(KnowledgeBaseDO::getId, ids.kbIds()));
        long totalDocCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .in(KnowledgeDocumentDO::getKbId, ids.kbIds()));
        return Results.success(new SpacesStatsVO(kbCount, totalDocCount));
    }
}
