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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.SysDeptUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.SysDeptVO;
import com.nageoffer.ai.ragent.user.dao.entity.SysDeptDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.service.SysDeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysDeptServiceImpl implements SysDeptService {

    /** GLOBAL 部门硬编码 id，对应 init_data_pg.sql seed */
    public static final String GLOBAL_DEPT_ID = "1";
    public static final String GLOBAL_DEPT_CODE = "GLOBAL";

    private final SysDeptMapper sysDeptMapper;
    private final UserMapper userMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public List<SysDeptVO> list(String keyword) {
        var wrapper = Wrappers.lambdaQuery(SysDeptDO.class);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(SysDeptDO::getDeptName, keyword.trim())
                   .or()
                   .like(SysDeptDO::getDeptCode, keyword.trim());
        }
        wrapper.orderByAsc(SysDeptDO::getCreateTime);
        return sysDeptMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public SysDeptVO getById(String id) {
        SysDeptDO dept = sysDeptMapper.selectById(id);
        return dept == null ? null : toVO(dept);
    }

    @Override
    public String create(SysDeptCreateRequest request) {
        validateFields(request.getDeptCode(), request.getDeptName());
        Long existing = sysDeptMapper.selectCount(
                Wrappers.lambdaQuery(SysDeptDO.class)
                        .eq(SysDeptDO::getDeptCode, request.getDeptCode().trim())
        );
        if (existing != null && existing > 0) {
            throw new ClientException("部门编码已存在: " + request.getDeptCode());
        }
        SysDeptDO dept = SysDeptDO.builder()
                .deptCode(request.getDeptCode().trim())
                .deptName(request.getDeptName().trim())
                .build();
        sysDeptMapper.insert(dept);
        return dept.getId();
    }

    @Override
    public void update(String id, SysDeptUpdateRequest request) {
        SysDeptDO dept = sysDeptMapper.selectById(id);
        if (dept == null) {
            throw new ClientException("部门不存在: " + id);
        }
        if (GLOBAL_DEPT_ID.equals(id)) {
            throw new ClientException("GLOBAL 部门不可修改");
        }
        validateFields(request.getDeptCode(), request.getDeptName());
        if (!dept.getDeptCode().equals(request.getDeptCode().trim())) {
            Long existing = sysDeptMapper.selectCount(
                    Wrappers.lambdaQuery(SysDeptDO.class)
                            .eq(SysDeptDO::getDeptCode, request.getDeptCode().trim())
                            .ne(SysDeptDO::getId, id)
            );
            if (existing != null && existing > 0) {
                throw new ClientException("部门编码已被其他部门占用: " + request.getDeptCode());
            }
        }
        dept.setDeptCode(request.getDeptCode().trim());
        dept.setDeptName(request.getDeptName().trim());
        sysDeptMapper.updateById(dept);
    }

    @Override
    public void delete(String id) {
        SysDeptDO dept = sysDeptMapper.selectById(id);
        if (dept == null) {
            throw new ClientException("部门不存在: " + id);
        }
        if (GLOBAL_DEPT_ID.equals(id)) {
            throw new ClientException("GLOBAL 部门不可删除");
        }
        Long userCount = userMapper.selectCount(
                Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getDeptId, id));
        if (userCount != null && userCount > 0) {
            throw new ClientException("部门下仍有 " + userCount + " 个用户，不可删除");
        }
        Long kbCount = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class).eq(KnowledgeBaseDO::getDeptId, id));
        if (kbCount != null && kbCount > 0) {
            throw new ClientException("部门下仍有 " + kbCount + " 个知识库，不可删除");
        }
        sysDeptMapper.deleteById(id);
    }

    private void validateFields(String deptCode, String deptName) {
        if (deptCode == null || deptCode.isBlank()) {
            throw new ClientException("部门编码不能为空");
        }
        if (deptName == null || deptName.isBlank()) {
            throw new ClientException("部门名称不能为空");
        }
        if (deptCode.length() > 32) {
            throw new ClientException("部门编码不能超过 32 字符");
        }
        if (deptName.length() > 64) {
            throw new ClientException("部门名称不能超过 64 字符");
        }
    }

    private SysDeptVO toVO(SysDeptDO dept) {
        Long userCount = userMapper.selectCount(
                Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getDeptId, dept.getId()));
        Long kbCount = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class).eq(KnowledgeBaseDO::getDeptId, dept.getId()));
        return new SysDeptVO(
                dept.getId(),
                dept.getDeptCode(),
                dept.getDeptName(),
                userCount == null ? 0 : userCount.intValue(),
                kbCount == null ? 0 : kbCount.intValue(),
                dept.getCreateTime(),
                dept.getUpdateTime(),
                GLOBAL_DEPT_ID.equals(dept.getId())
        );
    }
}
