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

package com.knowledgebase.ai.ragent.rag.core.mcp.executor;

import com.knowledgebase.ai.ragent.framework.convention.ChatRequest;
import com.knowledgebase.ai.ragent.infra.chat.LLMService;
import com.knowledgebase.ai.ragent.rag.core.mcp.LLMMCPParameterExtractor;
import com.knowledgebase.ai.ragent.rag.core.mcp.MCPTool;
import com.knowledgebase.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模拟"DB 加载 mcp_collateral_field_lookup → 调用 LLMMCPParameterExtractor"链路。
 * 不启动 Spring，不连真实 DB——param_prompt_template 直接以字符串注入，
 * 等价 IntentNodeDO.paramPromptTemplate 取出后的状态。
 */
class CollateralFieldLookupParamExtractIntegrationTest {

    private static final String PARAM_PROMPT_FROM_DB =
            """
            你是 Collateral 协议字段筛查的参数抽取器。
            从用户问题中只提取以下 JSON 字段：
            {
              "counterparty": "...",
              "agreementType": "...",
              "fieldName": "..."
            }
            """;

    @Test
    void extractParameters_usesDbProvidedTemplate_andYieldsThreeSlots() {
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader loader = mock(PromptTemplateLoader.class);
        // PromptTemplateLoader 是 mock；当 LLMMCPParameterExtractor 走 DB 模板分支时
        // load(...) 不会被调用，无需 stub。若实施时发现 stub 调用了，说明分支判断逻辑被改动。

        when(llmService.chat(any(ChatRequest.class)))
                .thenReturn("{\"counterparty\":\"HSBC\",\"agreementType\":\"ISDA&CSA\",\"fieldName\":\"minimum transfer amount\"}");

        LLMMCPParameterExtractor extractor = new LLMMCPParameterExtractor(llmService, loader);

        CollateralFieldLookupMCPExecutor executor =
                new CollateralFieldLookupMCPExecutor(new CollateralSeedData(), new CollateralFieldLookupSlotRouter());
        MCPTool tool = executor.getToolDefinition();

        Map<String, Object> params = extractor.extractParameters(
                "华泰和HSBC交易的ISDA&CSA下的 minimum transfer amount是多少",
                tool,
                PARAM_PROMPT_FROM_DB);

        assertThat(params).containsEntry("counterparty", "HSBC");
        assertThat(params.get("agreementType")).isNotNull();
        assertThat(params.get("fieldName")).isNotNull();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(captor.capture());
        // system message 必须使用 DB 模板（不回退到 PromptTemplateLoader.load）
        String firstSystemContent = captor.getValue().getMessages().get(0).getContent();
        assertThat(firstSystemContent).contains("Collateral 协议字段筛查的参数抽取器");
        // 走 DB 模板时，PromptTemplateLoader.load(...) 不应被调用
        verifyNoInteractions(loader);
    }
}
