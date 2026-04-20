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

package com.nageoffer.ai.ragent.knowledge.controller.request;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.util.Set;

/**
 * 知识库分页查询请求
 */
@Data
public class KnowledgeBasePageRequest extends Page {

    /**
     * 知识库名称（支持模糊匹配）
     */
    private String name;

    /**
     * RBAC: 当前用户可访问的知识库 ID 集合（null 表示不限）
     */
    @TableField(exist = false)
    private Set<String> accessibleKbIds;

    /**
     * 列表口径：
     * - access（默认）：当前用户可访问范围
     * - owner：当前管理员的拥有范围（DEPT_ADMIN 仅本部门；SUPER_ADMIN 不限）
     */
    private String scope;
}
