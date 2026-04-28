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

import com.knowledgebase.ai.ragent.user.service.KbAccessService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.lang.reflect.Parameter;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Permission boundary architecture guards for the staged permission roadmap.
 */
@AnalyzeClasses(
        packages = "com.knowledgebase.ai.ragent",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class PermissionBoundaryArchTest {

    /**
     * KB-resource-scoped check methods that must live in service-layer user entry points.
     * Admin-gate methods intentionally remain allowed at HTTP entry.
     */
    private static final Set<String> KB_SCOPED_CHECK_METHODS = Set.of(
            "checkAccess",
            "checkManageAccess",
            "checkDocManageAccess",
            "checkDocSecurityLevelAccess",
            "checkKbRoleBindingAccess");

    @ArchTest
    static final ArchRule controllers_must_not_call_kb_scoped_check =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().callMethodWhere(kbScopedCheckMethod())
                    .because("PR1 moved KB-scoped authorization from controllers into service user-entry points. "
                            + "Admin-gate methods are intentionally not in this list.");

    @ArchTest
    static final ArchRule rag_handler_package_no_user_context =
            noClasses()
                    .that().resideInAPackage("..rag.service.handler..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.knowledgebase.ai.ragent.framework.context.UserContext")
                    .because("PR3-4 injects request userId into stream handler params; async handlers "
                            + "must not read UserContext ThreadLocal.");

    @ArchTest
    static final ArchRule kb_access_calculator_no_user_context =
            noClasses()
                    .that().haveFullyQualifiedName("com.knowledgebase.ai.ragent.user.service.support.KbAccessCalculator")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.knowledgebase.ai.ragent.framework.context.UserContext")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("com.knowledgebase.ai.ragent.framework.context.LoginUser")
                    .because("PR3-1 keeps KbAccessCalculator pure: callers pass KbAccessSubject "
                            + "instead of ThreadLocal or LoginUser state.");

    @ArchTest
    static final ArchRule kb_read_access_port_no_userid_param =
            methods()
                    .that().areDeclaredIn(com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort.class)
                    .should(notHaveStringParameterNamedUserId())
                    .because("PR3-2 keeps KbReadAccessPort current-user only; user id must not be "
                            + "threaded through read-port signatures.");

    private static DescribedPredicate<JavaMethodCall> kbScopedCheckMethod() {
        return new DescribedPredicate<JavaMethodCall>(
                "target is one of the 5 KB-scoped check methods on a type assignable to KbAccessService") {
            @Override
            public boolean test(JavaMethodCall call) {
                JavaClass owner = call.getTarget().getOwner();
                return owner.isAssignableTo(KbAccessService.class)
                        && KB_SCOPED_CHECK_METHODS.contains(call.getTarget().getName());
            }
        };
    }

    private static ArchCondition<JavaMethod> notHaveStringParameterNamedUserId() {
        return new ArchCondition<JavaMethod>("not have a String parameter named userId") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (Parameter parameter : method.reflect().getParameters()) {
                    boolean violation = parameter.getType().equals(String.class)
                            && "userId".equals(parameter.getName());
                    if (violation) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " declares String parameter named userId"));
                        return;
                    }
                }
                events.add(SimpleConditionEvent.satisfied(method,
                        method.getFullName() + " has no String parameter named userId"));
            }
        };
    }
}
