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

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.SysDeptVO;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService sysDeptService;
    private final KbAccessService kbAccessService;

    @GetMapping("/sys-dept")
    public Result<List<SysDeptVO>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        kbAccessService.checkAnyAdminAccess();
        return Results.success(sysDeptService.list(keyword));
    }

    @GetMapping("/sys-dept/{id}")
    public Result<SysDeptVO> getById(@PathVariable("id") String id) {
        kbAccessService.checkAnyAdminAccess();
        return Results.success(sysDeptService.getById(id));
    }

    @SaCheckRole("SUPER_ADMIN")
    @PostMapping("/sys-dept")
    public Result<String> create(@RequestBody SysDeptCreateRequest request) {
        return Results.success(sysDeptService.create(request));
    }

    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/sys-dept/{id}")
    public Result<Void> update(@PathVariable("id") String id, @RequestBody SysDeptUpdateRequest request) {
        sysDeptService.update(id, request);
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @DeleteMapping("/sys-dept/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        sysDeptService.delete(id);
        return Results.success();
    }
}
