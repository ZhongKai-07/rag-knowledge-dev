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

package com.nageoffer.ai.ragent.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Eval 域 - 评测运行实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_run")
public class EvalRunDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 使用的数据集 ID
     */
    private String datasetId;

    /**
     * 所属知识库 ID
     */
    private String kbId;

    /**
     * 触发人
     */
    private String triggeredBy;

    /**
     * 运行状态
     */
    private String status;

    /**
     * 总条目数
     */
    private Integer totalItems;

    /**
     * 成功条目数
     */
    private Integer succeededItems;

    /**
     * 失败条目数
     */
    private Integer failedItems;

    /**
     * 指标汇总（JSON）
     */
    private String metricsSummary;

    /**
     * 系统快照（JSON）
     */
    private String systemSnapshot;

    /**
     * 评测 LLM 名称
     */
    private String evaluatorLlm;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private Date startedAt;

    /**
     * 结束时间
     */
    private Date finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
