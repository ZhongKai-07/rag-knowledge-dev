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
 * Eval 域 - Gold 数据集条目实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_gold_item")
public class GoldItemDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属数据集 ID
     */
    private String datasetId;

    /**
     * 问题
     */
    private String question;

    /**
     * 标准答案
     */
    private String groundTruthAnswer;

    /**
     * 来源分块 ID
     */
    private String sourceChunkId;

    /**
     * 来源分块文本
     */
    private String sourceChunkText;

    /**
     * 来源文档 ID
     */
    private String sourceDocId;

    /**
     * 来源文档名
     */
    private String sourceDocName;

    /**
     * 审核状态
     */
    private String reviewStatus;

    /**
     * 审核备注
     */
    private String reviewNote;

    /**
     * 合成该条目所用模型
     */
    private String synthesizedByModel;

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
