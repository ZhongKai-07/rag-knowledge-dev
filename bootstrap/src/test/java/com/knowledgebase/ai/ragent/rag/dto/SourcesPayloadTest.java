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

package com.knowledgebase.ai.ragent.rag.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourcesPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRoundTripWithMessageIdNull() throws Exception {
        SourceChunk chunk = SourceChunk.builder()
                .chunkId("c1").chunkIndex(0).preview("hi").score(0.9f).build();
        SourceCard card = SourceCard.builder()
                .index(1).docId("d1").docName("doc.pdf").kbId("kb1").topScore(0.9f)
                .chunks(List.of(chunk)).build();
        SourcesPayload original = SourcesPayload.builder()
                .conversationId("c_abc").messageId(null).cards(List.of(card)).build();

        String json = mapper.writeValueAsString(original);
        SourcesPayload restored = mapper.readValue(json, SourcesPayload.class);

        assertThat(restored).isEqualTo(original);
        assertThat(json).contains("\"messageId\":null");
    }

    @Test
    void shouldRoundTripEmptyCards() throws Exception {
        SourcesPayload original = SourcesPayload.builder()
                .conversationId("c").messageId(null).cards(List.of()).build();

        String json = mapper.writeValueAsString(original);
        SourcesPayload restored = mapper.readValue(json, SourcesPayload.class);

        assertThat(restored).isEqualTo(original);
    }
}
