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

package com.nageoffer.ai.ragent.arch;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.security.port.CurrentUserProbe;
import com.nageoffer.ai.ragent.knowledge.service.impl.KbScopeResolverImpl;
import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.nageoffer.ai.ragent.user.service.impl.KbAccessServiceImpl;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.nageoffer.ai.ragent",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class KbAccessServiceRetirementArchTest {

    @ArchTest
    static final ArchRule only_retired_kb_access_service_classes_may_depend_on_kb_access_service =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(KbAccessService.class.getName())
                    .and().doNotHaveFullyQualifiedName(KbAccessServiceImpl.class.getName())
                    .should().dependOnClassesThat().areAssignableTo(KbAccessService.class)
                    .because("PR2 c6 retires KbAccessService as a dependency surface; "
                            + "only the retired interface and implementation may reference it.");

    @ArchTest
    static final ArchRule kb_scope_resolver_must_not_depend_on_user_context =
            noClasses()
                    .that().haveFullyQualifiedName(KbScopeResolverImpl.class.getName())
                    .should().dependOnClassesThat().areAssignableTo(UserContext.class)
                    .because("T5.10 keeps KbScopeResolverImpl pure by accepting LoginUser explicitly.");

    @ArchTest
    static final ArchRule kb_scope_resolver_must_not_depend_on_current_user_probe =
            noClasses()
                    .that().haveFullyQualifiedName(KbScopeResolverImpl.class.getName())
                    .should().dependOnClassesThat().areAssignableTo(CurrentUserProbe.class)
                    .because("T5.10 keeps KbScopeResolverImpl independent from current-user probes.");
}
