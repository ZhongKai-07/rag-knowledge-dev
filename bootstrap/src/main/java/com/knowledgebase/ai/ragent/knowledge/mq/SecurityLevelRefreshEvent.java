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

package com.knowledgebase.ai.ragent.knowledge.mq;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档 security_level 变更事件。
 * Consumer 接到该事件后调 {@code VectorStoreService.updateChunksMetadata} 刷新 OpenSearch。
 */
public class SecurityLevelRefreshEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String docId;
    private String collectionName;
    private int newSecurityLevel;

    public SecurityLevelRefreshEvent() {
    }

    public SecurityLevelRefreshEvent(String docId, String collectionName, int newSecurityLevel) {
        this.docId = docId;
        this.collectionName = collectionName;
        this.newSecurityLevel = newSecurityLevel;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public int getNewSecurityLevel() {
        return newSecurityLevel;
    }

    public void setNewSecurityLevel(int newSecurityLevel) {
        this.newSecurityLevel = newSecurityLevel;
    }
}
