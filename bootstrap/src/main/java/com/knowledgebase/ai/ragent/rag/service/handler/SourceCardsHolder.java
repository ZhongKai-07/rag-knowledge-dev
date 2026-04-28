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

package com.knowledgebase.ai.ragent.rag.service.handler;

import com.knowledgebase.ai.ragent.rag.dto.SourceCard;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回答来源卡片的 set-once CAS 容器。
 *
 * <p>编排层在检索 + 聚合完成后 {@link #trySet(List)} 一次；LLM 异步流回调
 * （PR4 的 {@code onComplete}）通过 {@link #get()} 读取快照，避免依赖
 * ThreadLocal。
 */
public class SourceCardsHolder {

    private final AtomicReference<List<SourceCard>> ref = new AtomicReference<>();

    /** 一次性设值。已设过则返回 {@code false} 且不覆盖既有值。 */
    public boolean trySet(List<SourceCard> cards) {
        return ref.compareAndSet(null, cards);
    }

    /** 未设值时返回 {@link Optional#empty()}。 */
    public Optional<List<SourceCard>> get() {
        return Optional.ofNullable(ref.get());
    }
}
