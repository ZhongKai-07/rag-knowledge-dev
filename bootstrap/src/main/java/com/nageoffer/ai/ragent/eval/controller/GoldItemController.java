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

package com.nageoffer.ai.ragent.eval.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.eval.controller.request.EditGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.request.ReviewGoldItemRequest;
import com.nageoffer.ai.ragent.eval.controller.vo.GoldItemVO;
import com.nageoffer.ai.ragent.eval.service.GoldItemReviewService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/eval")
@SaCheckRole("SUPER_ADMIN")
public class GoldItemController {

    private final GoldItemReviewService reviewService;

    @GetMapping("/gold-datasets/{id}/items")
    public Result<List<GoldItemVO>> list(@PathVariable("id") String datasetId,
                                         @RequestParam(required = false) String reviewStatus) {
        return Results.success(reviewService.list(datasetId, reviewStatus));
    }

    @PostMapping("/gold-items/{id}/review")
    public Result<Void> review(@PathVariable("id") String itemId, @RequestBody ReviewGoldItemRequest req) {
        reviewService.review(itemId, req, StpUtil.getLoginIdAsString());
        return Results.success();
    }

    @PutMapping("/gold-items/{id}")
    public Result<Void> edit(@PathVariable("id") String itemId, @RequestBody EditGoldItemRequest req) {
        reviewService.edit(itemId, req, StpUtil.getLoginIdAsString());
        return Results.success();
    }
}
