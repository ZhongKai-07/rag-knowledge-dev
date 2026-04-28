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

package com.knowledgebase.ai.ragent.knowledge.dto;

/**
 * 文档元信息快照 (batch 查询返回类型).
 * 用于"回答来源"功能在回答生成时拉取 docId → docName/kbId 映射快照.
 * 快照语义: 答案生成时的 docName / kbId, 后续文档改名 / 删除不影响历史引用展示.
 */
public record DocumentMetaSnapshot(String docId, String docName, String kbId) {}
