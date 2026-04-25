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

package com.nageoffer.ai.ragent.framework.security.port;

import java.util.List;

/**
 * 跨域 chunk 采样端口。
 * eval 域通过该 port 从 knowledge 域按 KB 抽取 chunk 用于 Gold Set 合成；
 * 不得绕过该 port 直读 t_knowledge_chunk / t_knowledge_document（硬约束）。
 */
public interface KbChunkSamplerPort {

    /**
     * 从指定 KB 随机采样 chunk（joined with document name），按每 doc 最多 maxPerDoc 条去重。
     *
     * <p>过滤条件（与 spec §6.1 固化，不得改动）：
     * chunk.deleted = 0 AND chunk.enabled = 1 AND
     * doc.deleted   = 0 AND doc.enabled   = 1 AND doc.status = 'success'
     *
     * @param kbId       知识库 ID
     * @param count      目标返回条数（上限）
     * @param maxPerDoc  每 doc 最多贡献条数
     * @return 随机顺序的 chunk 快照（count 不足时尽可能多返回，不抛异常）
     */
    List<ChunkSample> sampleForSynthesis(String kbId, int count, int maxPerDoc);

    /**
     * 采样结果的单条快照——字节级冻结 chunk.content / doc.doc_name。
     * eval 域拿到后直接写入 t_eval_gold_item，不做任何修改。
     */
    record ChunkSample(String chunkId, String chunkText, String docId, String docName) {
    }
}
