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

package com.knowledgebase.ai.ragent.rag.core.source;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.knowledgebase.ai.ragent.rag.dto.SourceCard;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class CitationStatsCollector {

    private static final Pattern CITATION = Pattern.compile("\\[\\^(\\d+)]");
    private static final Pattern SENTENCE = Pattern.compile("[^。！？]+[。！？]");

    private CitationStatsCollector() {}

    public static CitationStats scan(String answer, List<SourceCard> cards) {
        if (StrUtil.isBlank(answer) || CollUtil.isEmpty(cards)) {
            return new CitationStats(0, 0, 0, 0.0);
        }
        Set<Integer> validIndexes = cards.stream()
                .map(SourceCard::getIndex)
                .collect(Collectors.toSet());

        int total = 0, valid = 0, invalid = 0;
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            total++;
            if (validIndexes.contains(n)) valid++;
            else invalid++;
        }

        // SENTENCE 粗切只匹配以 。！？ 结尾的句子；answer 末尾若缺终止标点，该尾段不计入 totalSent，
        // coverage 对"以未结尾语句收束"的 LLM 输出会系统性低估。读 trace.extra_data.citationCoverage 时需知此限。
        Matcher sm = SENTENCE.matcher(answer);
        int totalSent = 0, citedSent = 0;
        while (sm.find()) {
            totalSent++;
            if (CITATION.matcher(sm.group()).find()) citedSent++;
        }
        double coverage = totalSent == 0 ? 0.0 : (double) citedSent / totalSent;

        return new CitationStats(total, valid, invalid, coverage);
    }

    public record CitationStats(int total, int valid, int invalid, double coverage) {}
}
