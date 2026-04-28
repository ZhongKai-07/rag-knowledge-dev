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

package com.knowledgebase.ai.ragent.eval.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * Eval 域 - 评测结果（按条目）实体
 *
 * <p>注意：本表采用硬删除设计，无 deleted / updatedBy / updateTime 字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_result")
public class EvalResultDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属运行 ID
     */
    private String runId;

    /**
     * 对应 Gold 条目 ID
     */
    private String goldItemId;

    /**
     * 问题
     */
    private String question;

    /**
     * 标准答案
     */
    private String groundTruthAnswer;

    /**
     * 系统回答
     */
    private String systemAnswer;

    /**
     * 检索到的分块（JSON 数组）
     */
    private String retrievedChunks;

    /**
     * 忠实度
     */
    private BigDecimal faithfulness;

    /**
     * 回答相关性
     */
    private BigDecimal answerRelevancy;

    /**
     * 上下文精确度
     */
    private BigDecimal contextPrecision;

    /**
     * 上下文召回率
     */
    private BigDecimal contextRecall;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 耗时（毫秒）
     */
    private Integer elapsedMs;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}
