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

package com.nageoffer.ai.ragent.rag.service.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.rag.dao.entity.RagEvaluationRecordDO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceEvalAccessSupportTest {

    private CurrentUserProbe currentUserProbe;
    private UserMapper userMapper;
    private TraceEvalAccessSupport support;

    @BeforeEach
    void setUp() {
        initTableInfo(RagEvaluationRecordDO.class);
        currentUserProbe = mock(CurrentUserProbe.class);
        userMapper = mock(UserMapper.class);
        support = new TraceEvalAccessSupport(currentUserProbe, userMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void applyUserScope_shouldRestrictRegularUserToOwnUserId() {
        UserContext.set(LoginUser.builder().userId("u-1").build());
        when(currentUserProbe.isSuperAdmin()).thenReturn(false);
        when(currentUserProbe.isDeptAdmin()).thenReturn(false);
        LambdaQueryWrapper<RagEvaluationRecordDO> wrapper = Wrappers.lambdaQuery(RagEvaluationRecordDO.class);

        support.applyUserScope(wrapper, RagEvaluationRecordDO::getUserId);

        assertTrue(wrapper.getSqlSegment().contains("user_id"));
    }

    @Test
    void applyUserScope_shouldUseDeptSubqueryForDeptAdmin() {
        UserContext.set(LoginUser.builder().userId("admin-1").deptId("dept-1").build());
        when(currentUserProbe.isSuperAdmin()).thenReturn(false);
        when(currentUserProbe.isDeptAdmin()).thenReturn(true);
        LambdaQueryWrapper<RagEvaluationRecordDO> wrapper = Wrappers.lambdaQuery(RagEvaluationRecordDO.class);

        support.applyUserScope(wrapper, RagEvaluationRecordDO::getUserId);

        assertTrue(wrapper.getSqlSegment().contains("SELECT id FROM t_user WHERE dept_id = 'dept-1' AND deleted = 0"));
    }

    @Test
    void applyUserScope_shouldFailClosedWhenDeptAdminHasNoDept() {
        UserContext.set(LoginUser.builder().userId("admin-1").build());
        when(currentUserProbe.isSuperAdmin()).thenReturn(false);
        when(currentUserProbe.isDeptAdmin()).thenReturn(true);
        LambdaQueryWrapper<RagEvaluationRecordDO> wrapper = Wrappers.lambdaQuery(RagEvaluationRecordDO.class);

        support.applyUserScope(wrapper, RagEvaluationRecordDO::getUserId);

        assertTrue(wrapper.getSqlSegment().contains("1 = 0"));
    }

    @Test
    void isVisible_shouldReturnFalseWhenRegularUserReadsOthersRecord() {
        UserContext.set(LoginUser.builder().userId("u-1").build());
        when(currentUserProbe.isSuperAdmin()).thenReturn(false);
        when(currentUserProbe.isDeptAdmin()).thenReturn(false);

        assertFalse(support.isVisible("u-2"));
    }

    @Test
    void isVisible_shouldAllowDeptAdminToReadSameDeptRecord() {
        UserContext.set(LoginUser.builder().userId("admin-1").deptId("dept-1").build());
        when(currentUserProbe.isSuperAdmin()).thenReturn(false);
        when(currentUserProbe.isDeptAdmin()).thenReturn(true);
        when(userMapper.selectById("owner-1")).thenReturn(UserDO.builder().id("owner-1").deptId("dept-1").build());

        assertTrue(support.isVisible("owner-1"));
    }

    private static void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }
}
