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

package com.knowledgebase.ai.ragent.core.parser.layout;

import java.util.List;

/**
 * Engine-neutral layout block. Adapters from Docling / future engines produce these.
 */
public record LayoutBlock(
        String blockId,
        BlockType blockType,
        int pageNo,
        Bbox bbox,
        String text,
        Integer readingOrder,
        Double confidence,
        Integer headingLevel,
        List<String> headingPath) {
    public LayoutBlock {
        if (headingPath == null) headingPath = List.of();
    }

    public record Bbox(double x, double y, double width, double height) {}
}
