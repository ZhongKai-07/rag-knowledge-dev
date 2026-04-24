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

package com.nageoffer.ai.ragent.eval.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * eval 域异步执行线程池配置。
 *
 * <p>不使用 Spring `@EnableAsync` —— 项目主启动类没有开启全局 async，
 * 且 legacy `RagEvaluationServiceImpl.saveRecord` 的 `@Async` 注解目前处于失效状态（EVAL-2）。
 * 这里只定义一个显式命名的 `evalExecutor` bean，业务代码 `@Qualifier("evalExecutor")` 注入后
 * 调 `execute(Runnable)`，不依赖全局 async 语义。
 */
@Configuration
public class EvalAsyncConfig {

    @Bean(name = "evalExecutor")
    public ThreadPoolTaskExecutor evalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("eval-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
