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

package com.knowledgebase.ai.ragent.knowledge.dao.entity;

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
 * RAG 知识库文档分块表实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_knowledge_chunk")
public class KnowledgeChunkDO {

    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 知识库ID
     */
    private String kbId;

    /**
     * 文档ID
     */
    private String docId;

    /**
     * 分块序号（从0开始）
     */
    private Integer chunkIndex;

    /**
     * 分块正文内容
     */
    private String content;

    /**
     * 内容哈希（用于幂等/去重）
     */
    private String contentHash;

    /**
     * 字符数（可用于统计/调参）
     */
    private Integer charCount;

    /**
     * Token数（可选）
     */
    private Integer tokenCount;

    /**
     * 显示用页码提示；page_start/page_end 才是规范化证据范围
     */
    private Integer pageNumber;

    /**
     * 该 chunk 起始的源 1-based 页号
     */
    private Integer pageStart;

    /**
     * 该 chunk 末尾的源 1-based 页号
     */
    private Integer pageEnd;

    /**
     * 祖先标题路径（JSON 数组）
     */
    private String headingPath;

    /**
     * 块类型：TITLE|PARAGRAPH|TABLE|HEADER|FOOTER|LIST|CAPTION|OTHER
     */
    private String blockType;

    /**
     * 来源 layout block id 列表（JSON 数组）
     */
    private String sourceBlockIds;

    /**
     * 来源 bbox 引用（JSON 数组）
     */
    private String bboxRefs;

    /**
     * 文本层类型：NATIVE_TEXT|OCR|MIXED|UNKNOWN
     */
    private String textLayerType;

    /**
     * 解析器输出的 layout 置信度
     */
    private Double layoutConfidence;

    /**
     * 是否启用 0：禁用 1：启用
     */
    private Integer enabled;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除 0：正常 1：删除
     */
    @TableLogic
    private Integer deleted;
}
