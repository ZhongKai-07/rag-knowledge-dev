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

import com.nageoffer.ai.ragent.user.service.KbAccessService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Locks PR1 (#24, a24ea4b) controller-thinning invariant: no class in any controller
 * package may invoke one of the 5 KB-resource-scoped {@code KbAccessService.check*}
 * methods that PR1 moved from controllers into the service layer. Authorization for
 * KB resources must live at the service public user-entry (per
 * docs/dev/design/2026-04-26-permission-roadmap.md §2 Invariant A — service public
 * user-entry is the unique authorization boundary for KB-scoped resources).
 *
 * <p>Pre-PR1 baseline was guarded by docs/dev/verification/permission-pr1-controllers-clean.sh
 * (manual grep on PR review). This rule promotes the guard to CI: regression fails the build.
 *
 * <p>Admin-gate methods ({@code checkAnyAdminAccess}, {@code checkUserManageAccess},
 * {@code checkRoleMutation}, {@code checkAssignRolesAccess}) are intentionally NOT
 * banned — they legitimately remain at HTTP entry as programmatic {@code @SaCheckRole}
 * equivalents (see docs/dev/gotchas.md §3: "every controller needs explicit
 * authorization … programmatic kbAccessService checks").
 */
@AnalyzeClasses(
        packages = "com.nageoffer.ai.ragent",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class PermissionBoundaryArchTest {

    /**
     * Five KB-resource-scoped check methods that PR1 (#24, a24ea4b) moved from
     * controllers into the service layer. Admin-gate methods like
     * {@code checkAnyAdminAccess}, {@code checkUserManageAccess},
     * {@code checkRoleMutation}, {@code checkAssignRolesAccess} are intentionally
     * NOT in this list — they remain at HTTP entry as programmatic {@code @SaCheckRole}
     * equivalents (see docs/dev/gotchas.md §3).
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
                    .because("PR1 (#24) moved KB-scoped authorization (5 methods) "
                            + "from controllers into the service layer. Authorization "
                            + "for KB resources must live at the service public user-entry "
                            + "(roadmap §2 Invariant A). Admin-gate methods "
                            + "(checkAnyAdminAccess / checkUserManageAccess / checkRoleMutation / "
                            + "checkAssignRolesAccess) are explicitly allowed at HTTP entry "
                            + "and are NOT in this list — see docs/dev/gotchas.md §3.");

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
}
