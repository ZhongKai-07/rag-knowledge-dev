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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.framework.security.port.KbChunkSamplerPort;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KbChunkSamplerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KbChunkSamplerImpl implements KbChunkSamplerPort {

    private static final int HARD_LIMIT_MULTIPLIER = 10;
    private static final int MIN_HARD_LIMIT = 200;

    private final KbChunkSamplerMapper mapper;

    @Override
    public List<ChunkSample> sampleForSynthesis(String kbId, int count, int maxPerDoc) {
        if (count <= 0 || maxPerDoc <= 0) {
            return Collections.emptyList();
        }
        int hardLimit = Math.max(MIN_HARD_LIMIT, count * HARD_LIMIT_MULTIPLIER);

        List<Map<String, Object>> rows = mapper.sampleRaw(kbId, hardLimit);

        Map<String, Integer> perDocCount = new HashMap<>();
        List<ChunkSample> picked = new ArrayList<>(count);
        for (Map<String, Object> row : rows) {
            String docId = (String) row.get("doc_id");
            int taken = perDocCount.getOrDefault(docId, 0);
            if (taken >= maxPerDoc) continue;
            picked.add(new ChunkSample(
                    (String) row.get("chunk_id"),
                    (String) row.get("chunk_text"),
                    docId,
                    (String) row.get("doc_name")
            ));
            perDocCount.put(docId, taken + 1);
            if (picked.size() >= count) break;
        }
        return picked;
    }
}
