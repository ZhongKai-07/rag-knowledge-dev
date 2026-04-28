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

package com.knowledgebase.ai.ragent.rag.core.retrieve;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetadataFilterTests {

    @Test
    void record_captures_field_op_and_value() {
        MetadataFilter f = new MetadataFilter("security_level", MetadataFilter.FilterOp.LTE, 2);
        assertEquals("security_level", f.field());
        assertEquals(MetadataFilter.FilterOp.LTE, f.op());
        assertEquals(2, f.value());
    }

    @Test
    void all_filter_ops_valueOf() {
        assertEquals(MetadataFilter.FilterOp.EQ,  MetadataFilter.FilterOp.valueOf("EQ"));
        assertEquals(MetadataFilter.FilterOp.LTE, MetadataFilter.FilterOp.valueOf("LTE"));
        assertEquals(MetadataFilter.FilterOp.GTE, MetadataFilter.FilterOp.valueOf("GTE"));
        assertEquals(MetadataFilter.FilterOp.LT,  MetadataFilter.FilterOp.valueOf("LT"));
        assertEquals(MetadataFilter.FilterOp.GT,  MetadataFilter.FilterOp.valueOf("GT"));
        assertEquals(MetadataFilter.FilterOp.IN,  MetadataFilter.FilterOp.valueOf("IN"));
    }
}
