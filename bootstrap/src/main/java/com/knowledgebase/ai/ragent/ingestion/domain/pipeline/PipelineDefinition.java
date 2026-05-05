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

package com.knowledgebase.ai.ragent.ingestion.domain.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowledgebase.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 摄取管道定义实体类
 * 定义一个完整的文档摄取管道，包含管道的基本信息和节点配置列表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class PipelineDefinition {

    /**
     * 管道的唯一标识符
     */
    private String id;

    /**
     * 管道名称
     */
    private String name;

    /**
     * 管道描述信息
     */
    private String description;

    /**
     * 管道中的节点配置列表
     * 按执行顺序排列的节点配置
     */
    private List<NodeConfig> nodes;

    /**
     * 返回一个新的 {@link PipelineDefinition}：把 {@code key=value} 注入到所有
     * {@code nodeType == "parser"} 的 {@link NodeConfig#getSettings()} ObjectNode 中。
     * <p>
     * 用途：上传期把用户选择的 {@code parseMode}（BASIC/ENHANCED）注入到 ParserNode 的运行时
     * settings，避免修改 cached pipeline definition。本方法不变更原 definition 与原 NodeConfig；
     * 通过 deep copy 保持线程安全。
     * </p>
     *
     * @param key   要注入的 settings 字段名
     * @param value 字段值
     * @return 新的 {@link PipelineDefinition} 实例
     */
    public PipelineDefinition withParserNodeSetting(String key, String value) {
        if (nodes == null || nodes.isEmpty()) {
            return this;
        }
        ObjectMapper om = new ObjectMapper();
        String parserType = IngestionNodeType.PARSER.getValue();
        List<NodeConfig> copy = new ArrayList<>(nodes.size());
        for (NodeConfig n : nodes) {
            if (n != null && parserType.equals(n.getNodeType())) {
                ObjectNode settings = n.getSettings() == null
                        ? om.createObjectNode()
                        : ((ObjectNode) n.getSettings()).deepCopy();
                settings.put(key, value);
                copy.add(n.toBuilder().settings(settings).build());
            } else {
                copy.add(n);
            }
        }
        return this.toBuilder().nodes(copy).build();
    }
}
