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

package com.nageoffer.ai.ragent.user.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.controller.vo.AccessRoleVO;
import com.nageoffer.ai.ragent.user.service.AccessService;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * P1.3: 权限中心新页面专用接口。路径前缀 {@code /access/*}，与老 {@code /role}、{@code /user}
 * 接口并存。老接口保持不变（30 天观察期，D9 决议）。
 * <p>
 * 设计文档 §六 P1：https://github.com/ZhongKai-07/ragent/blob/feature/access-center-redesign/
 * docs/dev/design/2026-04-19-access-center-redesign.md
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/access")
public class AccessController {

    private final AccessService accessService;
    private final KbAccessService kbAccessService;

    /**
     * P1.3a: 按部门筛选可见角色。SUPER 或 DEPT_ADMIN 均可查询任意部门（用于 Tab 2 共享面板的
     * 跨部门共享能力）；写校验留在 {@code POST /role} 与 {@code validateRoleAssignment}。
     */
    @GetMapping("/roles")
    public Result<List<AccessRoleVO>> listRoles(
            @RequestParam(value = "dept_id", required = false) String deptId,
            @RequestParam(value = "include_global", required = false, defaultValue = "true") boolean includeGlobal) {
        kbAccessService.checkAnyAdminAccess();
        return Results.success(accessService.listRoles(deptId, includeGlobal));
    }
}
