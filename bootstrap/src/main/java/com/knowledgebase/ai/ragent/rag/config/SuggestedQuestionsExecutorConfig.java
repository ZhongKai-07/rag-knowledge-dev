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

package com.knowledgebase.ai.ragent.rag.config;

import com.alibaba.ttl.TtlRunnable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 推荐问题生成专用线程池
 *
 * 设计要点：
 * - 与 modelStreamExecutor 隔离，避免故障扩散到主流式回调
 * - AbortPolicy：推荐是辅助 UX，队列满时应丢弃而不是拖慢 done 事件
 * - TaskDecorator 包 TtlRunnable：让 RagTraceContext 等 TTL 上下文跨线程传递，
 *   确保 @RagTraceNode("suggested-chat") 子节点能正确落库
 */
@Configuration
public class SuggestedQuestionsExecutorConfig {

    @Bean("suggestedQuestionsExecutor")
    public ThreadPoolTaskExecutor suggestedQuestionsExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("suggest-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.setTaskDecorator(runnable -> TtlRunnable.get(runnable));
        ex.initialize();
        return ex;
    }
}
