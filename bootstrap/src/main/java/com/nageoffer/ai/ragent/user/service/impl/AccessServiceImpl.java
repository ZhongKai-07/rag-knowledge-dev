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

package com.nageoffer.ai.ragent.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.controller.vo.AccessRoleVO;
import com.nageoffer.ai.ragent.user.dao.entity.RoleDO;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.mapper.RoleMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.service.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccessServiceImpl implements AccessService {

    private final RoleMapper roleMapper;
    private final SysDeptMapper sysDeptMapper;

    @Override
    public List<AccessRoleVO> listRoles(String deptId, boolean includeGlobal) {
        Set<String> deptIds = new HashSet<>();
        if (deptId != null && !deptId.isBlank()) {
            deptIds.add(deptId);
        }
        if (includeGlobal) {
            deptIds.add(SysDeptServiceImpl.GLOBAL_DEPT_ID);
        }

        List<RoleDO> roles;
        if (deptIds.isEmpty()) {
            // 无过滤条件 = 返回全部
            roles = roleMapper.selectList(Wrappers.lambdaQuery(RoleDO.class));
        } else {
            roles = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getDeptId, deptIds));
        }

        if (roles.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> referencedDeptIds = roles.stream()
                .map(RoleDO::getDeptId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> deptNameById = referencedDeptIds.isEmpty()
                ? Collections.emptyMap()
                : sysDeptMapper.selectList(
                        Wrappers.lambdaQuery(SysDeptDO.class).in(SysDeptDO::getId, referencedDeptIds))
                        .stream().collect(Collectors.toMap(SysDeptDO::getId, SysDeptDO::getDeptName));

        return roles.stream()
                .map(r -> AccessRoleVO.builder()
                        .id(r.getId())
                        .name(r.getName())
                        .description(r.getDescription())
                        .roleType(r.getRoleType())
                        .maxSecurityLevel(r.getMaxSecurityLevel())
                        .deptId(r.getDeptId())
                        .deptName(deptNameById.get(r.getDeptId()))
                        .build())
                .toList();
    }
}
