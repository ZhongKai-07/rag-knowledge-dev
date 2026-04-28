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

package com.knowledgebase.ai.ragent.user.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色表 Mapper
 */
@Mapper
public interface RoleMapper extends BaseMapper<RoleDO> {

    /**
     * 查询用户挂载的所有角色（完整 RoleDO，用于拿 max_security_level 等字段）。
     *
     * <p>注意：走 {@code t_user_role → t_role} 内连接，不做 deleted 过滤 ——
     * {@code @TableLogic} 只对基类的 {@code BaseMapper} 自动生效，自定义 SQL 里
     * 必须显式加 {@code AND t_role.deleted = 0}。
     */
    @Select("SELECT r.* FROM t_user_role ur "
          + "JOIN t_role r ON ur.role_id = r.id "
          + "WHERE ur.user_id = #{userId} "
          + "AND r.deleted = 0")
    List<RoleDO> selectRolesByUserId(@Param("userId") String userId);

    /**
     * 查询用户挂载的所有角色类型字符串（仅 {@code role_type} 列，Sa-Token 用）。
     */
    @Select("SELECT DISTINCT r.role_type FROM t_user_role ur "
          + "JOIN t_role r ON ur.role_id = r.id "
          + "WHERE ur.user_id = #{userId} "
          + "AND r.deleted = 0 "
          + "AND r.role_type IS NOT NULL")
    List<String> selectRoleTypesByUserId(@Param("userId") String userId);
}
