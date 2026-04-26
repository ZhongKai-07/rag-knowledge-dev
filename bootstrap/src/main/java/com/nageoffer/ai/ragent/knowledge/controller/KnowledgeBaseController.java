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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.security.port.AccessScope;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.ChunkStrategyVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.knowledge.service.KbScopeResolver;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.user.service.RoleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 知识库控制器
 * 提供知识库的增删改查等基础操作接口
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbScopeResolver kbScopeResolver;
    private final RoleService roleService;

    /**
     * 创建知识库
     */
    @PostMapping("/knowledge-base")
    public Result<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        return Results.success(knowledgeBaseService.create(requestParam));
    }

    /**
     * 重命名知识库
     */
    @PutMapping("/knowledge-base/{kb-id}")
    public Result<Void> renameKnowledgeBase(@PathVariable("kb-id") String kbId,
                                            @RequestBody KnowledgeBaseUpdateRequest requestParam) {
        knowledgeBaseService.rename(kbId, requestParam);
        return Results.success();
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/knowledge-base/{kb-id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable("kb-id") String kbId) {
        knowledgeBaseService.delete(kbId);
        return Results.success();
    }

    /**
     * 查询知识库详情
     */
    @GetMapping("/knowledge-base/{kb-id}")
    public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
        return Results.success(knowledgeBaseService.queryById(kbId));
    }

    /**
     * 分页查询知识库列表
     */
    @GetMapping("/knowledge-base")
    public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
        LoginUser user = UserContext.hasUser() ? UserContext.get() : null;
        AccessScope scope = "owner".equalsIgnoreCase(requestParam.getScope())
                ? kbScopeResolver.resolveForOwnerScope(user)
                : kbScopeResolver.resolveForRead(user);
        return Results.success(knowledgeBaseService.pageQuery(requestParam, scope));
    }

    /**
     * 查询支持的分块策略列表
     */
    @GetMapping("/knowledge-base/chunk-strategies")
    public Result<List<ChunkStrategyVO>> listChunkStrategies() {
        List<ChunkStrategyVO> list = Arrays.stream(ChunkingMode.values())
                .filter(ChunkingMode::isVisible)
                .map(mode -> new ChunkStrategyVO(mode.getValue(), mode.getLabel(), mode.getDefaultConfig()))
                .toList();
        return Results.success(list);
    }

    @GetMapping("/knowledge-base/{kb-id}/role-bindings")
    public Result<List<KbRoleBindingVO>> getKbRoleBindings(@PathVariable("kb-id") String kbId) {
        return Results.success(roleService.getKbRoleBindings(kbId));
    }

    @PutMapping("/knowledge-base/{kb-id}/role-bindings")
    public Result<Void> setKbRoleBindings(@PathVariable("kb-id") String kbId,
                                          @RequestBody List<KbRoleBindingRequest> bindings) {
        roleService.setKbRoleBindings(kbId, bindings);
        return Results.success();
    }

    @Data
    public static class KbRoleBindingVO {
        private String roleId;
        private String roleName;
        private String roleType;
        private String permission;
        private Integer maxSecurityLevel;
        /** 角色归属部门 ID（sys_dept.id）；null 表示旧数据未回填（P1.0 backfill 后应非空）。P1.5 前端跨部门 badge 依赖此字段。 */
        private String deptId;
        /** 角色归属部门名称，派生自 sys_dept.dept_name，前端展示用。 */
        private String deptName;
    }

    @Data
    public static class KbRoleBindingRequest {
        private String roleId;
        private String permission;
        private Integer maxSecurityLevel;
    }
}
