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

package com.knowledgebase.ai.ragent.eval.service.impl;

import com.knowledgebase.ai.ragent.eval.client.RagasEvalClient;
import com.knowledgebase.ai.ragent.eval.config.EvalProperties;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldDatasetDO;
import com.knowledgebase.ai.ragent.eval.dao.entity.GoldItemDO;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldDatasetMapper;
import com.knowledgebase.ai.ragent.eval.dao.mapper.GoldItemMapper;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizeRequest;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizeResponse;
import com.knowledgebase.ai.ragent.eval.domain.SynthesizedItem;
import com.knowledgebase.ai.ragent.eval.service.SynthesisProgressTracker;
import com.knowledgebase.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.knowledgebase.ai.ragent.framework.security.port.KbChunkSamplerPort.ChunkSample;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldDatasetSynthesisServiceImplTest {

    private GoldDatasetMapper datasetMapper;
    private GoldItemMapper itemMapper;
    private KbChunkSamplerPort sampler;
    private RagasEvalClient client;
    private SynthesisProgressTracker tracker;
    private EvalProperties props;
    private GoldDatasetSynthesisServiceImpl service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(GoldDatasetMapper.class);
        itemMapper = mock(GoldItemMapper.class);
        sampler = mock(KbChunkSamplerPort.class);
        client = mock(RagasEvalClient.class);
        tracker = new SynthesisProgressTracker();
        props = new EvalProperties();
        props.getSynthesis().setDefaultCount(50);
        props.getSynthesis().setMaxPerDoc(5);
        props.getSynthesis().setBatchSize(2);
        props.getSynthesis().setStrongModel("qwen-max");
        // 7-arg 构造器是唯一 public 构造器；测试传 null executor → trigger() 会抛 IllegalStateException，
        // 测试只驱动 runSynthesisSync 同步路径
        service = new GoldDatasetSynthesisServiceImpl(datasetMapper, itemMapper, sampler, client, tracker, props, null);
    }

    @Test
    void runSynthesis_rejects_if_items_already_exist() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.runSynthesisSync("d1", 10, "user-sys"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("already");
    }

    @Test
    void runSynthesis_freezes_chunk_text_and_doc_name_from_java_side() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);

        ChunkSample sampled = new ChunkSample("chunk-A", "the-full-chunk-text", "doc-X", "Doc X Name");
        when(sampler.sampleForSynthesis("kb-1", 1, 5)).thenReturn(List.of(sampled));

        SynthesizedItem item = new SynthesizedItem("chunk-A", "what is X?", "X is the answer");
        when(client.synthesize(any(SynthesizeRequest.class)))
                .thenReturn(new SynthesizeResponse(List.of(item), List.of()));

        service.runSynthesisSync("d1", 1, "user-sys");

        ArgumentCaptor<GoldItemDO> cap = ArgumentCaptor.forClass(GoldItemDO.class);
        verify(itemMapper).insert(cap.capture());
        GoldItemDO saved = cap.getValue();
        // 硬约束：快照字段从 Java 侧 sampled 冻结，非 Python 返回值
        assertThat(saved.getSourceChunkText()).isEqualTo("the-full-chunk-text");
        assertThat(saved.getSourceDocName()).isEqualTo("Doc X Name");
        assertThat(saved.getSourceDocId()).isEqualTo("doc-X");
        assertThat(saved.getSourceChunkId()).isEqualTo("chunk-A");
        assertThat(saved.getQuestion()).isEqualTo("what is X?");
        assertThat(saved.getGroundTruthAnswer()).isEqualTo("X is the answer");
        assertThat(saved.getReviewStatus()).isEqualTo("PENDING");
        assertThat(saved.getSynthesizedByModel()).isEqualTo("qwen-max");
        assertThat(tracker.get("d1").status()).isEqualTo("COMPLETED");
    }

    @Test
    void runSynthesis_tracks_failed_chunk_ids_without_inserting() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);

        ChunkSample good = new ChunkSample("c-ok", "ok-text", "d1", "doc");
        ChunkSample bad = new ChunkSample("c-bad", "bad-text", "d1", "doc");
        when(sampler.sampleForSynthesis(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(good, bad));

        when(client.synthesize(any(SynthesizeRequest.class))).thenReturn(
                new SynthesizeResponse(
                        List.of(new SynthesizedItem("c-ok", "q?", "a.")),
                        List.of("c-bad")));

        service.runSynthesisSync("d1", 2, "user-sys");

        verify(itemMapper, never()).insert(argThat((GoldItemDO i) -> "c-bad".equals(i.getSourceChunkId())));
        assertThat(tracker.get("d1").failed()).isEqualTo(1);
        assertThat(tracker.get("d1").processed()).isEqualTo(1);
    }

    @Test
    void runSynthesis_splits_by_batchSize() {
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);

        List<ChunkSample> samples = List.of(
                new ChunkSample("a", "a", "d", "D"),
                new ChunkSample("b", "b", "d", "D"),
                new ChunkSample("c", "c", "d", "D"),
                new ChunkSample("e", "e", "d", "D"),
                new ChunkSample("f", "f", "d", "D")
        );
        when(sampler.sampleForSynthesis(anyString(), anyInt(), anyInt())).thenReturn(samples);
        when(client.synthesize(any(SynthesizeRequest.class))).thenAnswer(inv -> {
            SynthesizeRequest req = inv.getArgument(0);
            return new SynthesizeResponse(
                    req.chunks().stream()
                            .map(c -> new SynthesizedItem(c.id(), "q", "a"))
                            .toList(),
                    List.of());
        });

        service.runSynthesisSync("d1", 5, "user-sys");

        // batchSize=2 + 5 chunks → 3 次 HTTP 调用（2 + 2 + 1）
        verify(client, org.mockito.Mockito.times(3)).synthesize(any(SynthesizeRequest.class));
    }

    @Test
    void trigger_with_null_executor_throws_IllegalStateException() {
        // tracker 已占位的情况也应失败——executor 是真正的阻塞门
        when(datasetMapper.selectById("d1")).thenReturn(
                GoldDatasetDO.builder().id("d1").status("DRAFT").kbId("kb-1").build());
        when(itemMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.trigger("d1", 5, "user-sys"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evalExecutor");
    }
}
