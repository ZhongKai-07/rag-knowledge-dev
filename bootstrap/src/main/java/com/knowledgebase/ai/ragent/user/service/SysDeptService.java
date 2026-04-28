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

package com.knowledgebase.ai.ragent.user.service;

import com.knowledgebase.ai.ragent.user.controller.request.SysDeptCreateRequest;
import com.knowledgebase.ai.ragent.user.controller.request.SysDeptUpdateRequest;
import com.knowledgebase.ai.ragent.user.controller.vo.SysDeptVO;

import java.util.List;

/**
 * 部门管理服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>GLOBAL 部门（id='1' / dept_code='GLOBAL'）硬保护：不可删除、dept_code 不可改</li>
 *   <li>删除部门前校验：t_user 和 t_knowledge_base 中无引用，否则 409</li>
 *   <li>dept_code 唯一（数据库 uk_dept_code 约束 + 业务层预检）</li>
 * </ul>
 */
public interface SysDeptService {

    /** 列表 + 可选关键字过滤 */
    List<SysDeptVO> list(String keyword);

    /** 根据 id 查询，不存在返回 null */
    SysDeptVO getById(String id);

    /** 创建部门，返回新 id */
    String create(SysDeptCreateRequest request);

    /** 更新部门；GLOBAL 拒绝 */
    void update(String id, SysDeptUpdateRequest request);

    /** 删除部门；GLOBAL 或有引用时拒绝 */
    void delete(String id);
}
