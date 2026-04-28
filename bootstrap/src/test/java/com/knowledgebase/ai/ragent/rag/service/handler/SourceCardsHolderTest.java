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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCardsHolderTest {

    @Test
    void getShouldReturnEmptyBeforeSet() {
        SourceCardsHolder holder = new SourceCardsHolder();
        assertThat(holder.get()).isEqualTo(Optional.empty());
    }

    @Test
    void trySetShouldReturnTrueOnFirstCallAndStoreValue() {
        SourceCardsHolder holder = new SourceCardsHolder();
        List<SourceCard> cards = List.of(SourceCard.builder().index(1).docId("d1").build());

        boolean ok = holder.trySet(cards);

        assertThat(ok).isTrue();
        assertThat(holder.get()).isPresent().get().isEqualTo(cards);
    }

    @Test
    void trySetShouldReturnFalseOnSecondCallAndPreserveFirstValue() {
        SourceCardsHolder holder = new SourceCardsHolder();
        List<SourceCard> first = List.of(SourceCard.builder().index(1).docId("d1").build());
        List<SourceCard> second = List.of(SourceCard.builder().index(2).docId("d2").build());

        holder.trySet(first);
        boolean ok2 = holder.trySet(second);

        assertThat(ok2).isFalse();
        assertThat(holder.get()).isPresent().get().isEqualTo(first);
    }

    @Test
    void concurrentTrySetShouldOnlyAcceptOne() throws Exception {
        SourceCardsHolder holder = new SourceCardsHolder();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                int k = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (holder.trySet(List.of(
                                SourceCard.builder().index(k).docId("d" + k).build()))) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(holder.get()).isPresent();
    }
}
