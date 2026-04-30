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

package com.knowledgebase.ai.ragent.rag.core.intent;

import cn.hutool.core.util.StrUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Stream;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NodeScoreFilters {

    public static List<NodeScore> mcp(List<NodeScore> scores) {
        return stream(scores)
                .filter(ns -> ns.getNode() != null && ns.getNode().isMCP())
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    public static List<NodeScore> mcp(List<NodeScore> scores, double minScore) {
        return stream(scores)
                .filter(ns -> ns.getScore() >= minScore)
                .filter(ns -> ns.getNode() != null && ns.getNode().isMCP())
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    public static List<NodeScore> kb(List<NodeScore> scores) {
        return stream(scores)
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }

    public static List<NodeScore> kb(List<NodeScore> scores, double minScore) {
        return stream(scores)
                .filter(ns -> ns.getScore() >= minScore)
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .toList();
    }

    private static Stream<NodeScore> stream(List<NodeScore> scores) {
        return scores == null ? Stream.empty() : scores.stream();
    }
}
