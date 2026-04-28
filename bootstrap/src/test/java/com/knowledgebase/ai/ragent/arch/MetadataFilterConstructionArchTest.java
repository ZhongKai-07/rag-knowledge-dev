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

package com.knowledgebase.ai.ragent.arch;

import com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * PR5 c4: only {@code DefaultMetadataFilterBuilder} may construct
 * {@link MetadataFilter} from inside {@code rag.core.retrieve..}.
 *
 * <p>This static guard locks c1's invariant — channel/engine/retriever code can't
 * bypass the builder by calling {@code new MetadataFilter(...)} directly. Production
 * callers must go through {@code MetadataFilterBuilder.build(ctx, kbId)} so every
 * OpenSearch DSL emitted from {@code rag.core.retrieve} carries the
 * {@code kb_id IN [..]} + {@code security_level LTE_OR_MISSING ..} contract that
 * {@code OpenSearchRetrieverService.enforceFilterContract} fails closed on.
 *
 * <p>Tests are excluded via {@link ImportOption.DoNotIncludeTests}, so test-only
 * fixtures that build {@code MetadataFilter} ad hoc remain unrestricted.
 */
@AnalyzeClasses(
        packages = "com.knowledgebase.ai.ragent",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class MetadataFilterConstructionArchTest {

    private static final String METADATA_FILTER_FQN =
            "com.knowledgebase.ai.ragent.rag.core.retrieve.MetadataFilter";

    /**
     * Anchor exemption to the FQN, not the simple name. Using
     * {@code doNotHaveSimpleName("DefaultMetadataFilterBuilder")} would let any
     * future class with the same simple name in any other package inherit the
     * exemption and bypass the rule silently.
     */
    private static final String DEFAULT_BUILDER_FQN =
            "com.knowledgebase.ai.ragent.rag.core.retrieve.filter.DefaultMetadataFilterBuilder";

    @ArchTest
    public static final ArchRule only_default_builder_may_construct_metadata_filter =
            classes()
                    .that().resideInAPackage("..rag.core.retrieve..")
                    .and().doNotHaveFullyQualifiedName(DEFAULT_BUILDER_FQN)
                    .should(notCallMetadataFilterConstructor())
                    .as("PR5 c1: only DefaultMetadataFilterBuilder (anchored by FQN "
                            + DEFAULT_BUILDER_FQN + ") may construct MetadataFilter inside "
                            + "rag.core.retrieve.. — every other caller must go through "
                            + "MetadataFilterBuilder.build(...) so the kb_id + security_level "
                            + "contract is invariant");

    /**
     * Custom condition: violate if the class issues any constructor call targeting
     * {@link MetadataFilter}. Plain
     * {@code callConstructor(MetadataFilter.class, String.class, FilterOp.class, Object.class)}
     * does not match the record canonical ctor reliably across ArchUnit versions
     * because the canonical ctor descriptor can carry erased / nested-enum signatures;
     * matching by owner FQN + {@code <init>} is robust.
     *
     * <p>Per-class semantics: emit one violation event per offending {@code <init>}
     * call site; emit one "satisfied" event when the class issues no such call so
     * the rule has explicit positive coverage in reports.
     */
    private static ArchCondition<JavaClass> notCallMetadataFilterConstructor() {
        return new ArchCondition<JavaClass>(
                "not call MetadataFilter constructor (rag.core.retrieve.. only allows "
                        + "DefaultMetadataFilterBuilder)") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean offended = false;
                for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
                    JavaClass owner = call.getTargetOwner();
                    if (METADATA_FILTER_FQN.equals(owner.getFullName())) {
                        offended = true;
                        events.add(SimpleConditionEvent.violated(
                                call,
                                String.format(
                                        "%s constructs MetadataFilter at %s — only "
                                                + "DefaultMetadataFilterBuilder may do so",
                                        item.getFullName(),
                                        call.getSourceCodeLocation())));
                    }
                }
                if (!offended) {
                    events.add(SimpleConditionEvent.satisfied(
                            item,
                            item.getFullName() + " does not construct MetadataFilter"));
                }
            }
        };
    }
}
