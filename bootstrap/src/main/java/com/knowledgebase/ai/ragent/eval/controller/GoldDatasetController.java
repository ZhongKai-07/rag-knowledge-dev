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

package com.knowledgebase.ai.ragent.eval.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.knowledgebase.ai.ragent.eval.config.EvalProperties;
import com.knowledgebase.ai.ragent.eval.controller.request.CreateGoldDatasetRequest;
import com.knowledgebase.ai.ragent.eval.controller.request.TriggerSynthesisRequest;
import com.knowledgebase.ai.ragent.eval.controller.vo.GoldDatasetVO;
import com.knowledgebase.ai.ragent.eval.controller.vo.SynthesisProgressVO;
import com.knowledgebase.ai.ragent.eval.domain.SynthesisProgress;
import com.knowledgebase.ai.ragent.eval.service.GoldDatasetService;
import com.knowledgebase.ai.ragent.eval.service.GoldDatasetSynthesisService;
import com.knowledgebase.ai.ragent.eval.service.SynthesisProgressTracker;
import com.knowledgebase.ai.ragent.framework.convention.Result;
import com.knowledgebase.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/eval/gold-datasets")
@SaCheckRole("SUPER_ADMIN")
public class GoldDatasetController {

    private final GoldDatasetService datasetService;
    private final GoldDatasetSynthesisService synthesisService;
    private final SynthesisProgressTracker progressTracker;
    private final EvalProperties props;

    @GetMapping
    public Result<List<GoldDatasetVO>> list(@RequestParam(required = false) String kbId,
                                            @RequestParam(required = false) String status) {
        return Results.success(datasetService.list(kbId, status));
    }

    @GetMapping("/{id}")
    public Result<GoldDatasetVO> detail(@PathVariable("id") String id) {
        return Results.success(datasetService.detail(id));
    }

    @PostMapping
    public Result<String> create(@RequestBody CreateGoldDatasetRequest req) {
        return Results.success(datasetService.create(req, StpUtil.getLoginIdAsString()));
    }

    @PostMapping("/{id}/synthesize")
    public Result<Void> triggerSynthesis(@PathVariable("id") String id,
                                         @RequestBody(required = false) TriggerSynthesisRequest req) {
        int count = (req != null && req.getCount() != null && req.getCount() > 0)
                ? req.getCount()
                : props.getSynthesis().getDefaultCount();
        synthesisService.trigger(id, count, StpUtil.getLoginIdAsString());
        return Results.success();
    }

    @GetMapping("/{id}/synthesis-progress")
    public Result<SynthesisProgressVO> progress(@PathVariable("id") String id) {
        SynthesisProgress p = progressTracker.get(id);
        return Results.success(new SynthesisProgressVO(p.status(), p.total(), p.processed(), p.failed(), p.error()));
    }

    @PostMapping("/{id}/activate")
    public Result<Void> activate(@PathVariable("id") String id) {
        datasetService.activate(id);
        return Results.success();
    }

    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable("id") String id) {
        datasetService.archive(id);
        return Results.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        datasetService.delete(id);
        return Results.success();
    }
}
