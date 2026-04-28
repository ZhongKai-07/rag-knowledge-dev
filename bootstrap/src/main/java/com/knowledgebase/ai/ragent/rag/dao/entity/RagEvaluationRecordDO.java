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

package com.knowledgebase.ai.ragent.rag.dao.entity;

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
 * RAG 评测记录（Query-Chunk-Answer 留存）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_rag_evaluation_record")
public class RagEvaluationRecordDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String traceId;

    private String conversationId;

    private String messageId;

    private String userId;

    /**
     * 原始查询
     */
    private String originalQuery;

    /**
     * 改写后查询
     */
    private String rewrittenQuery;

    /**
     * 子问题列表（JSON 数组）
     */
    private String subQuestions;

    /**
     * 检索到的分块列表（JSON 数组）
     */
    private String retrievedChunks;

    /**
     * 检索 TopK
     */
    private Integer retrievalTopK;

    /**
     * 模型回答
     */
    private String answer;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 意图识别结果（JSON）
     */
    private String intentResults;

    /**
     * 评测状态：PENDING / COMPLETED
     */
    private String evalStatus;

    /**
     * 评测指标（JSON，如 RAGAS 结果）
     */
    private String evalMetrics;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
