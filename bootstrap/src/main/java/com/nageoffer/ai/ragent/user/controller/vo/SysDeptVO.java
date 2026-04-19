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

package com.nageoffer.ai.ragent.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SysDeptVO {
    private String id;
    private String deptCode;
    private String deptName;
    /** 该部门关联的用户数 */
    private Integer userCount;
    /** 该部门关联的知识库数 */
    private Integer kbCount;
    /** P1.3d: 该部门归属的角色数 */
    private Integer roleCount;
    private Date createTime;
    private Date updateTime;
    /** GLOBAL 部门该字段为 true，前端据此禁用编辑/删除按钮 */
    private Boolean systemReserved;
}
