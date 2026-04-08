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
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.controller.vo.SpacesStatsVO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Spaces 控制器
 * 提供 Spaces 页面所需的统计数据接口
 */
@RestController
@RequiredArgsConstructor
public class SpacesController {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KbAccessService kbAccessService;

    /**
     * 获取当前用户可见的知识库数量和文档总数
     */
    @GetMapping("/spaces/stats")
    public Result<SpacesStatsVO> getStats() {
        String userId = UserContext.getUserId();
        boolean isAdmin = "admin".equals(UserContext.getRole());

        if (isAdmin) {
            // Admin sees everything — query total counts directly.
            // Do NOT call kbAccessService.getAccessibleKbIds: it walks role chains
            // and admin may not have explicit role-KB mappings.
            long kbCount = knowledgeBaseMapper.selectCount(
                    Wrappers.lambdaQuery(KnowledgeBaseDO.class));
            long totalDocCount = knowledgeDocumentMapper.selectCount(
                    Wrappers.lambdaQuery(KnowledgeDocumentDO.class));
            return Results.success(new SpacesStatsVO(kbCount, totalDocCount));
        }

        // Regular user: get accessible KB IDs via RBAC
        Set<String> accessibleKbIds = kbAccessService.getAccessibleKbIds(userId);

        // Empty set short-circuit: return 0/0 to avoid SQL IN() syntax error
        if (accessibleKbIds == null || accessibleKbIds.isEmpty()) {
            return Results.success(new SpacesStatsVO(0L, 0L));
        }

        long kbCount = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .in(KnowledgeBaseDO::getId, accessibleKbIds));
        long totalDocCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .in(KnowledgeDocumentDO::getKbId, accessibleKbIds));
        return Results.success(new SpacesStatsVO(kbCount, totalDocCount));
    }
}
