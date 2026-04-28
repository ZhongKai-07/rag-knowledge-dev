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

package com.knowledgebase.ai.ragent.rag.core.retrieve.scope;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁住 RetrievalScopeBuilder 单参契约 (review P1#1).
 * 双参 build(LoginUser, String) 引入双身份来源风险, 此测试确保不退化.
 */
class RetrievalScopeBuilderMismatchTest {

    @Test
    void build_method_has_only_single_string_parameter() throws NoSuchMethodException {
        Method build = RetrievalScopeBuilder.class.getDeclaredMethod("build", String.class);

        assertThat(build.getParameterCount()).isEqualTo(1);
        assertThat(build.getParameterTypes()[0]).isEqualTo(String.class);
    }

    @Test
    void no_login_user_overload_exists() {
        Method[] methods = RetrievalScopeBuilder.class.getDeclaredMethods();
        boolean hasLoginUserOverload = false;
        for (Method method : methods) {
            if (!"build".equals(method.getName())) {
                continue;
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType.getSimpleName().equals("LoginUser")) {
                    hasLoginUserOverload = true;
                    break;
                }
            }
        }

        assertThat(hasLoginUserOverload)
                .as("RetrievalScopeBuilder must not accept LoginUser to avoid double-identity drift")
                .isFalse();
    }
}
