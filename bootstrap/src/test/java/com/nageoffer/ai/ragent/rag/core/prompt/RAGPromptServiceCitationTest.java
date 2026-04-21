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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.dto.SourceCard;
import com.nageoffer.ai.ragent.rag.dto.SourceChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGPromptServiceCitationTest {

    @Mock
    PromptTemplateLoader promptTemplateLoader;

    @InjectMocks
    RAGPromptService promptService;

    @BeforeEach
    void setUp() {
        when(promptTemplateLoader.load(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("你是企业知识库助手。请基于参考文档回答。");
    }

    // ---------- helpers ----------

    private SourceCard card(int index, String docId, String docName, List<SourceChunk> chunks) {
        return SourceCard.builder()
                .index(index)
                .docId(docId)
                .docName(docName)
                .kbId("kb_x")
                .topScore(0.9f)
                .chunks(chunks)
                .build();
    }

    private SourceChunk chunk(String chunkId, int chunkIndex, String preview) {
        return SourceChunk.builder()
                .chunkId(chunkId)
                .chunkIndex(chunkIndex)
                .preview(preview)
                .score(0.8f)
                .build();
    }

    private RetrievedChunk rc(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).score(0.9f).build();
    }

    /**
     * Base context with kbContext set — cards defaults to null (not set in builder).
     * PromptContext uses plain @Builder (no toBuilder), so we construct directly each time.
     */
    private PromptContext baseCtxWithKb() {
        return PromptContext.builder()
                .question("员工手册要求？")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                .build();
    }

    /** Extract concatenated content of all system-role messages. */
    private String systemText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> ChatMessage.Role.SYSTEM.equals(m.getRole()))
                .map(ChatMessage::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /** Extract concatenated content of ALL messages regardless of role. */
    private String allText(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ---------- tests ----------

    @Test
    void buildStructuredMessages_whenCardsNull_thenNoRuleBlockAndOriginalKbContext() {
        // cards field not set → null by default in Lombok @Builder
        PromptContext ctx = PromptContext.builder()
                .question("员工手册要求？")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                // cards intentionally omitted → null
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "员工手册要求？", List.of());
        String all = allText(msgs);
        assertThat(all).doesNotContain("【引用规则】");
        assertThat(all).doesNotContain("【参考文档】");
        assertThat(all).contains("kb-evidence"); // original kbContext preserved
    }

    @Test
    void buildStructuredMessages_whenCardsEmpty_thenNoRuleBlockAndOriginalKbContext() {
        PromptContext ctx = PromptContext.builder()
                .question("员工手册要求？")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                .cards(List.of()) // empty list
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "员工手册要求？", List.of());
        String all = allText(msgs);
        assertThat(all).doesNotContain("【引用规则】");
        assertThat(all).contains("kb-evidence");
    }

    @Test
    void buildStructuredMessages_whenCardsNonEmptyButKbContextBlank_thenNoRuleBlock() {
        // 异常组合：cards 非空但 kbContext 空，citationMode 应 false（守护 scene 选择）
        SourceCard c = card(1, "d1", "员工手册.pdf", List.of(chunk("c_001", 12, "preview-1")));
        PromptContext ctx = PromptContext.builder()
                .question("q")
                .mcpContext("mcp-only-evidence")
                .kbContext("") // 关键：kbContext 空
                .cards(List.of(c))
                .intentChunks(Map.of())
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String all = allText(msgs);
        assertThat(all).doesNotContain("【引用规则】");
        assertThat(all).doesNotContain("【参考文档】");
    }

    @Test
    void buildStructuredMessages_whenOneCard_thenRangeIsSingleBracket() {
        SourceCard c = card(1, "d1", "员工手册.pdf", List.of(chunk("c_001", 12, "preview-1")));
        PromptContext ctx = PromptContext.builder()
                .question("q")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                .cards(List.of(c))
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String system = systemText(msgs);
        assertThat(system).contains("【引用规则】");
        assertThat(system).contains("本次可用引用编号仅限 [^1]");
        assertThat(system).doesNotContain("至 [^1]"); // N==1 不输出"至"区间
    }

    @Test
    void buildStructuredMessages_whenMultipleCards_thenRangeIsInterval() {
        SourceCard c1 = card(1, "d1", "A.pdf", List.of(chunk("c_001", 1, "preview-1")));
        SourceCard c2 = card(2, "d2", "B.pdf", List.of(chunk("c_002", 2, "preview-2")));
        PromptContext ctx = PromptContext.builder()
                .question("q")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                .cards(List.of(c1, c2))
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String system = systemText(msgs);
        assertThat(system).contains("本次可用引用编号仅限 [^1] 至 [^2]");
    }

    @Test
    void buildStructuredMessages_whenCitationMode_thenEvidenceUsesFullText() {
        // 关键回归：evidence 必须用 RetrievedChunk.text 全文，不是 SourceChunk.preview
        SourceCard c = card(1, "d1", "手册.pdf", List.of(
                chunk("c_001", 12, "PREVIEW_TRUNCATED"),   // preview 故意与 text 不同
                chunk("c_002", 13, "PREVIEW_TRUNCATED_2")
        ));
        PromptContext ctx = PromptContext.builder()
                .question("q")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                .cards(List.of(c))
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String all = allText(msgs);
        assertThat(all).contains("【参考文档】");
        assertThat(all).contains("[^1]《手册.pdf》");
        assertThat(all).contains("员工入职第 6 个月可申请转正评估。"); // 来自 intentChunks 全文
        assertThat(all).contains("培训期满后考评合格予以录用。");
        assertThat(all).doesNotContain("PREVIEW_TRUNCATED"); // 绝不用 preview
    }

    @Test
    void buildStructuredMessages_whenChunkIdMissingInIntentChunks_thenFallbackToPreview() {
        // chunkId 在 intentChunks 缺失 → 回退到 preview（避免整个 card 失效）
        SourceCard c = card(1, "d1", "手册.pdf", List.of(
                chunk("c_missing", 99, "fallback-preview")
        ));
        PromptContext ctx = PromptContext.builder()
                .question("q")
                .mcpContext("")
                .kbContext("kb-evidence")
                .intentChunks(Map.of("i1", List.of(
                        rc("c_001", "员工入职第 6 个月可申请转正评估。"),
                        rc("c_002", "培训期满后考评合格予以录用。")
                )))
                .cards(List.of(c))
                .build();
        List<ChatMessage> msgs = promptService.buildStructuredMessages(ctx, List.of(), "q", List.of());
        String all = allText(msgs);
        assertThat(all).contains("[^1]《手册.pdf》");
        assertThat(all).contains("fallback-preview");
    }
}
