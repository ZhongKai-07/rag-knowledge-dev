# Permission PR3 — Access Calculator + ThreadLocal Guards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the security-level caller-context leak in `KbAccessServiceImpl.getMaxSecurityLevels(...)` by introducing a target-aware `KbAccessCalculator` (pure function) + `KbAccessSubjectFactory` (single-point ThreadLocal touch); collapse `KbReadAccessPort` signatures to current-user-only; remove dead-method `getMaxSecurityLevelForKb`; eliminate `UserContext` reads inside `StreamChatEventHandler` (factory-injected `userId`); upgrade existing `RagVectorTypeValidator` from log-warn to fail-fast — closing PR roadmap stage A.

**Architecture:** Five sequential commits, each compilable and test-green:

1. New types — `KbAccessSubject` + `KbAccessSubjectFactory(Impl)` + `KbAccessCalculator` + their unit tests. Adds production code that **no caller uses yet**; existing callers untouched.
2. Big-bang migration — `KbReadAccessPort` signature collapse + `KbAccessServiceImpl` rewires to factory/calculator + `AccessServiceImpl` reroutes off port + 4 other call sites + all `@MockBean` test fixtures. Single commit by necessity (port signature change forces simultaneous caller updates). Deletes `KbRbacAccessSupport`.
3. Physical removal of dead `getMaxSecurityLevelForKb(userId, kbId)` from `KbAccessService` interface + `KbAccessServiceImpl` (zero callers, leaks caller context).
4. `StreamChatEventHandler` → factory-injected `userId`; extends `StreamCallbackFactory.createChatEventHandler` signature; updates `RAGChatServiceImpl:112` + test fixtures; adds 3 ArchUnit rules for PR3-1 / PR3-2 / PR3-4.
5. `RagVectorTypeValidator` upgrade — `@PostConstruct` body changes from log-warn to fail-fast unless `rag.vector.allow-incomplete-backend=true`; 3 unit tests; `application.yaml` doc comment.

**Tech Stack:** Java 17 · Spring Boot 3.5 · MyBatis Plus · Mockito · JUnit 5 · ArchUnit · Lombok · Sa-Token · Redisson

**Spec:** `docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md`

---

## File Structure

### Files to create

| Path | Responsibility |
|---|---|
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubject.java` | Immutable `record(userId, deptId, roleTypes, maxSecurityLevel)` + `isSuperAdmin()` / `isDeptAdmin()` derived from `roleTypes` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactory.java` | Interface — `currentOrThrow()` + `forTargetUser(userId)` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactoryImpl.java` | Spring `@Component` — single point of `UserContext` and `UserProfileLoader` access |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessCalculator.java` | Spring `@Component` — pure RBAC + DEPT_ADMIN implicit + per-KB security level computation; **no `UserContext` import** |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactoryImplTest.java` | Unit tests — `currentOrThrow` 3 modes (system→ISE / no-user→CE / valid→subject); `forTargetUser` 2 modes |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/support/KbAccessCalculatorTest.java` | Unit tests — SUPER_ADMIN, regular RBAC, DEPT_ADMIN implicit (same dept), DEPT_ADMIN cross-dept (no implicit), per-KB level isolation, RBAC + DEPT_ADMIN merge takes max |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactoryUserIdTest.java` | Unit test — verifies `createChatEventHandler(..., userId)` propagates `userId` through to `params.getUserId()` |
| `docs/dev/verification/permission-pr3-leak-free.sh` | grep-based CI guard for PR3-2 / PR3-3 / PR3-4 / PR3-5 invariants |

### Files to modify

| Path | What changes |
|---|---|
| `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbReadAccessPort.java` | Drop `userId` parameter from `getAccessScope` and `getMaxSecurityLevelsForKbs`; delete `@Deprecated` `getAccessibleKbIds(String userId)` default method |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java` | Inject `KbAccessSubjectFactory` + `KbAccessCalculator`; rewrite `getAccessScope` / `getMaxSecurityLevelsForKbs` / `getAccessibleKbIds` to delegate; delete `computeRbacKbIds` / `computeDeptAdminAccessibleKbIds` privates; physically delete `getMaxSecurityLevelForKb(userId, kbId)` (commit 3) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java` | Delete `Integer getMaxSecurityLevelForKb(String userId, String kbId);` interface method (commit 3) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java` | Replace `kbReadAccess` field with `subjectFactory` + `calculator`; rewrite `listUserKbGrants` Step 1 to use `forTargetUser` + calculator; delete private `computeTargetUserAccessibleKbIds` helper |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbRbacAccessSupport.java` | **Delete the file** (logic absorbed into `KbAccessCalculator` private methods) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java` | Drop `user.getUserId()` arg from line 47 `kbReadAccess.getAccessScope(...)` call |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java` | Drop `userId` arg from line 125 `kbReadAccess.getAccessScope(...)`; pass `userId` into `callbackFactory.createChatEventHandler(...)` line 112 |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java` | Drop `UserContext.getUserId()` arg from line 262 `kbReadAccess.getMaxSecurityLevelsForKbs(...)` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java` | Add `String userId` field (Lombok `@Getter @Builder`) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java` | `createChatEventHandler` signature gains `String userId` param; builder chain passes `.userId(userId)` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java` | Remove `UserContext` import; `this.userId = params.getUserId()` (line 100); `onComplete` uses `this.userId` instead of `UserContext.getUserId()` (line 190) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/UserKbGrantVO.java:45` | Comment update — `getMaxSecurityLevelForKb` → `calculator.computeMaxSecurityLevels` |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/AccessServiceImplTest.java` | Replace 5 stubs of `kbReadAccess.getMaxSecurityLevelsForKbs(eq(userId), any())` with `subjectFactory.forTargetUser(userId)` + `calculator.computeMaxSecurityLevels(...)` stubs; remove `kbReadAccess` `@Mock` field; add T1+T2 contract tests |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java:125` | Update mock `callbackFactory.createChatEventHandler(any(), any(), any())` → `(any(), any(), any(), any())` (4-arg) |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/PermissionBoundaryArchTest.java` | Add 3 ArchUnit rules: `rag_handler_package_no_user_context`, `kb_access_calculator_no_user_context`, `kb_read_access_port_no_userid_param` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java` | Add `@Value("${rag.vector.allow-incomplete-backend:false}") boolean allowIncomplete`; `@PostConstruct.validate()` body changes from log-warn-only to fail-fast unless `allowIncomplete` |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidatorTest.java` | Create — 3 cases: opensearch silent, milvus+!allow → throw, milvus+allow → warn |
| `bootstrap/src/main/resources/application.yaml` | Add `# allow-incomplete-backend: false` documentation comment under `rag.vector` |
| `docs/dev/design/2026-04-26-permission-roadmap.md` | Update §1 当前坐标 + §3 PR3 status from "进行中" to "已完成"; mark roadmap §3 ⓟ caller-context leak fixed |
| `docs/dev/gotchas.md` (if has reference) | Update any `getMaxSecurityLevelForKb` reference |

### Files explicitly NOT modified (per spec §2.6)

- `framework/security/port/KbManageAccessPort.java`、`KbRoleBindingAdminPort.java`、`KbMetadataReader.java`、`CurrentUserProbe.java`、`UserAdminGuard.java`、`SuperAdminInvariantGuard.java`、`KbAccessCacheAdmin.java` — other 7 ports unchanged
- `KnowledgeChunkService` interface split → PR3.5
- Frontend
- DB schema
- OpenSearch query DSL → stage B
- `LoginUser.maxSecurityLevel` global semantics → stage C / PR6

---

## Important Pre-Flight Notes (read before starting)

### Existing `KbAccessServiceImpl` constructor field order

Verified line 77-83. Existing fields:
```java
private final UserRoleMapper userRoleMapper;
private final RoleKbRelationMapper roleKbRelationMapper;
private final RedissonClient redissonClient;
private final UserMapper userMapper;
private final RoleMapper roleMapper;
private final SysDeptMapper sysDeptMapper;
private final KbMetadataReader kbMetadataReader;
```

Commit 2 adds `KbAccessSubjectFactory subjectFactory` + `KbAccessCalculator calculator` to this list (Lombok `@RequiredArgsConstructor` auto-wires). After migration, `userRoleMapper`, `roleKbRelationMapper`, and `kbMetadataReader` are still used by surviving methods (e.g. `:217-225` `checkAccess`, `:288-326` `cache eviction`), so they stay. **Do not** remove them.

### `LoadedUserProfile` provides everything `KbAccessSubject` needs

`bootstrap/.../user/dao/dto/LoadedUserProfile.java` is already a record:
```java
public record LoadedUserProfile(
    String userId, String username, String avatar,
    String deptId, String deptName,
    List<String> roleIds, Set<RoleType> roleTypes,
    int maxSecurityLevel,
    boolean isSuperAdmin, boolean isDeptAdmin) {}
```

Subject construction is a 5-line wrapper:
```java
KbAccessSubject forTargetUser(String userId) {
    LoadedUserProfile p = userProfileLoader.load(userId);
    if (p == null) throw new ClientException("目标用户不存在: " + userId);
    return new KbAccessSubject(p.userId(), p.deptId(), p.roleTypes(), p.maxSecurityLevel());
}
```

### `AccessScope.All` is a sealed sentinel — preserve it

`framework/.../security/port/AccessScope.java` is `sealed permits AccessScope.All, AccessScope.Ids`. 8+ sites do `instanceof AccessScope.All` for short-circuit (no-filter) logic:
- `bootstrap/.../knowledge/controller/SpacesController.java:56`
- `bootstrap/.../knowledge/service/impl/KnowledgeBaseServiceImpl.java:281`
- `bootstrap/.../knowledge/service/impl/KnowledgeDocumentServiceImpl.java:686`
- `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java:253`
- `bootstrap/.../knowledge/service/impl/KbScopeResolverImpl.java:45,56`
- 多个测试断言 `assertInstanceOf(AccessScope.All.class, scope)`

`KbAccessServiceImpl.getAccessScope` after PR3 must keep:
```java
KbAccessSubject s = subjectFactory.currentOrThrow();
return s.isSuperAdmin()
    ? AccessScope.all()        // ← sentinel
    : AccessScope.ids(calculator.computeAccessibleKbIds(s, p));
```
Not `AccessScope.ids(calculator.computeAccessibleKbIds(...))` directly (would break short-circuit).

### Calculator MUST handle SUPER_ADMIN materialization itself

`AccessServiceImpl.listUserKbGrants` is the admin-views-target path; it needs the **actual KB list** when target is SUPER_ADMIN (UI shows full bind list). So `KbAccessCalculator.computeAccessibleKbIds(subject, perm)` returns:
- if `subject.isSuperAdmin()` → `kbMetadataReader.listAllKbIds()`
- else if `subject.isDeptAdmin()` → RBAC ∪ same-dept KBs
- else → RBAC only

`KbAccessServiceImpl.getAccessScope` then wraps the `SUPER_ADMIN` result as `AccessScope.all()` sentinel. **Calculator returns the materialized set; port wraps as sentinel.** This split is intentional.

### Mismatch: `RAGChatServiceImpl:124` already guards `UserContext.hasUser()`

The current code is:
```java
if (UserContext.hasUser() && userId != null) {
    accessScope = kbReadAccess.getAccessScope(userId, Permission.READ);
} else {
    accessScope = AccessScope.empty();
}
```

After PR3, `kbReadAccess.getAccessScope(p)` internally calls `subjectFactory.currentOrThrow()` which throws on no-user. The outer `UserContext.hasUser() && userId != null` guard ensures we only call the port when there's a user — preserving fail-closed for unauthenticated requests. **Keep the guard intact**, only drop the `userId` arg.

### Test fixture impact: `AccessServiceImplTest`

`bootstrap/src/test/java/.../user/service/AccessServiceImplTest.java` has 5 stubs:
```java
when(kbReadAccess.getMaxSecurityLevelsForKbs(eq(userId), any())).thenReturn(Map.of(...));
verify(kbReadAccess, times(1)).getMaxSecurityLevelsForKbs(eq(userId), ...);
```

After PR3, AccessServiceImpl no longer touches `kbReadAccess`. Each stub becomes:
```java
when(subjectFactory.forTargetUser(eq(userId))).thenReturn(<built KbAccessSubject>);
when(calculator.computeAccessibleKbIds(any(), eq(Permission.READ))).thenReturn(<kb id set>);
when(calculator.computeMaxSecurityLevels(any(), any())).thenReturn(Map.of(...));
```

The `@Mock KbReadAccessPort kbReadAccess` field becomes unused — remove it.

### Test fixture impact: `RAGChatServiceImplSourcesTest`

Line 125: `lenient().when(callbackFactory.createChatEventHandler(any(), any(), any())).thenReturn(callback);`

After commit 4: 4-arg form `createChatEventHandler(any(), any(), any(), any())`.

### `RagVectorTypeValidator` already exists

Verified `bootstrap/.../rag/config/RagVectorTypeValidator.java` exists with `@PostConstruct validate()` doing `log.warn` only. PR3 commit 5 upgrades the existing `validate()` body — **do not create a new `VectorBackendCapabilityValidator` class** (spec review P1).

### ArchUnit rules go to `bootstrap/.../arch/PermissionBoundaryArchTest`

Verified `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/PermissionBoundaryArchTest.java` exists (PR1 commit `e1cde994`); `@AnalyzeClasses(packages = "com.knowledgebase.ai.ragent")` covers all modules. Sister file `KbAccessServiceRetirementArchTest.java` shows the `noClasses().that().haveFullyQualifiedName(...)` pattern PR3 will reuse.

---

## Commit 1 — New types: Subject + Factory + Calculator + tests

The commit produces production code that nothing yet calls. Keeps build green; adds calculator unit tests that pin the algorithm before we point any caller at it.

### Task 1.1: Create `KbAccessSubject` record

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubject.java`

- [ ] **Step 1: Create file with full content**

```java
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

package com.knowledgebase.ai.ragent.user.service.support;

import com.knowledgebase.ai.ragent.framework.context.RoleType;

import java.util.Set;

/**
 * 权限计算的显式主体快照。
 *
 * <p>Calculator 的所有决策**仅**基于此 record 字段 — 无 ThreadLocal、
 * 无延迟加载。两类构造路径见 {@link KbAccessSubjectFactory}.
 *
 * <p>PR3 不变量 PR3-1：calculator 不得 import {@code UserContext} —
 * 主体信息全由调用方通过此 record 传入。
 */
public record KbAccessSubject(
        String userId,
        String deptId,
        Set<RoleType> roleTypes,
        int maxSecurityLevel) {

    public boolean isSuperAdmin() {
        return roleTypes != null && roleTypes.contains(RoleType.SUPER_ADMIN);
    }

    public boolean isDeptAdmin() {
        return roleTypes != null && roleTypes.contains(RoleType.DEPT_ADMIN);
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: BUILD SUCCESS.

### Task 1.2: Create `KbAccessSubjectFactory` interface

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactory.java`

- [ ] **Step 1: Create file**

```java
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

package com.knowledgebase.ai.ragent.user.service.support;

/**
 * 项目内**唯一**把 {@code UserContext} / {@code UserProfileLoader} 转成
 * {@link KbAccessSubject} 的入口。所有权限决策路径取 subject 必须经此。
 *
 * <p>PR3 不变量：calculator 类禁止 import UserContext —— 通过 factory
 * 把 ThreadLocal 触点收敛到一个实现类内。
 */
public interface KbAccessSubjectFactory {

    /**
     * 构造当前登录主体的 subject。
     *
     * @throws IllegalStateException 当 {@code UserContext.isSystem() == true};
     *         系统态走 {@code bypassIfSystemOrAssertActor} 早返回,不应到达 calculator 层
     * @throws com.knowledgebase.ai.ragent.framework.exception.ClientException
     *         当未登录或 userId 缺失（fail-closed,与 PR1 invariant B 一致）
     */
    KbAccessSubject currentOrThrow();

    /**
     * 构造指定目标用户的 subject。用于 admin-views-target 路径
     * （如 {@code AccessServiceImpl.listUserKbGrants}）。
     *
     * @throws com.knowledgebase.ai.ragent.framework.exception.ClientException
     *         当目标用户不存在
     */
    KbAccessSubject forTargetUser(String userId);
}
```

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: BUILD SUCCESS.

### Task 1.3: Failing tests for `KbAccessSubjectFactoryImpl`

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactoryImplTest.java`

- [ ] **Step 1: Create file with all 5 test cases**

```java
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

package com.knowledgebase.ai.ragent.user.service.support;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.knowledgebase.ai.ragent.user.service.UserProfileLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbAccessSubjectFactoryImplTest {

    @Mock
    private UserProfileLoader userProfileLoader;

    @InjectMocks
    private KbAccessSubjectFactoryImpl factory;

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void currentOrThrow_systemActor_throwsIllegalStateException() {
        UserContext.set(LoginUser.builder()
                .username("mq-op")
                .system(true)
                .build());

        assertThatThrownBy(factory::currentOrThrow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system");
    }

    @Test
    void currentOrThrow_noUser_throwsClientException() {
        // UserContext intentionally not set
        assertThatThrownBy(factory::currentOrThrow)
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("missing user context");
    }

    @Test
    void currentOrThrow_validUser_returnsSubjectFromLoginUser() {
        UserContext.set(LoginUser.builder()
                .userId("u1")
                .username("alice")
                .deptId("DEPT_A")
                .roleTypes(Set.of(RoleType.USER))
                .maxSecurityLevel(1)
                .build());

        KbAccessSubject s = factory.currentOrThrow();

        assertThat(s.userId()).isEqualTo("u1");
        assertThat(s.deptId()).isEqualTo("DEPT_A");
        assertThat(s.roleTypes()).containsExactly(RoleType.USER);
        assertThat(s.maxSecurityLevel()).isEqualTo(1);
    }

    @Test
    void forTargetUser_userNotFound_throwsClientException() {
        when(userProfileLoader.load("missing")).thenReturn(null);

        assertThatThrownBy(() -> factory.forTargetUser("missing"))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("目标用户不存在");
    }

    @Test
    void forTargetUser_validUser_returnsSubjectFromProfileLoader() {
        when(userProfileLoader.load("ficc_user_id")).thenReturn(new LoadedUserProfile(
                "ficc_user_id", "ficc", null,
                "FICC_DEPT", "FICC", List.of("FICC_USER"),
                Set.of(RoleType.USER), 1, false, false));

        KbAccessSubject s = factory.forTargetUser("ficc_user_id");

        assertThat(s.userId()).isEqualTo("ficc_user_id");
        assertThat(s.deptId()).isEqualTo("FICC_DEPT");
        assertThat(s.roleTypes()).containsExactly(RoleType.USER);
        assertThat(s.maxSecurityLevel()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run; verify tests fail (impl class not yet created)**

Run: `mvn -pl bootstrap test -Dtest=KbAccessSubjectFactoryImplTest -q`
Expected: COMPILATION FAILURE — `KbAccessSubjectFactoryImpl` symbol unresolved.

### Task 1.4: Implement `KbAccessSubjectFactoryImpl`

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactoryImpl.java`

- [ ] **Step 1: Create file**

```java
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

package com.knowledgebase.ai.ragent.user.service.support;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.user.dao.dto.LoadedUserProfile;
import com.knowledgebase.ai.ragent.user.service.UserProfileLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KbAccessSubjectFactoryImpl implements KbAccessSubjectFactory {

    private final UserProfileLoader userProfileLoader;

    @Override
    public KbAccessSubject currentOrThrow() {
        if (UserContext.isSystem()) {
            throw new IllegalStateException(
                    "system actor cannot construct KbAccessSubject; "
                  + "system path bypasses calculator (PR1 invariant B)");
        }
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            throw new ClientException("missing user context");
        }
        LoginUser u = UserContext.get();
        return new KbAccessSubject(
                u.getUserId(),
                u.getDeptId(),
                u.getRoleTypes(),
                u.getMaxSecurityLevel());
    }

    @Override
    public KbAccessSubject forTargetUser(String userId) {
        LoadedUserProfile p = userProfileLoader.load(userId);
        if (p == null) {
            throw new ClientException("目标用户不存在: " + userId);
        }
        return new KbAccessSubject(
                p.userId(),
                p.deptId(),
                p.roleTypes(),
                p.maxSecurityLevel());
    }
}
```

- [ ] **Step 2: Run tests; verify all 5 pass**

Run: `mvn -pl bootstrap test -Dtest=KbAccessSubjectFactoryImplTest -q`
Expected: 5 tests run, 0 failures.

### Task 1.5: Failing tests for `KbAccessCalculator`

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/support/KbAccessCalculatorTest.java`

- [ ] **Step 1: Create file with all 7 test cases**

```java
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

package com.knowledgebase.ai.ragent.user.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.context.RoleType;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbAccessCalculatorTest {

    @Mock private UserRoleMapper userRoleMapper;
    @Mock private RoleKbRelationMapper roleKbRelationMapper;
    @Mock private KbMetadataReader kbMetadataReader;

    @InjectMocks
    private KbAccessCalculator calculator;

    private static UserRoleDO ur(String roleId) {
        UserRoleDO r = new UserRoleDO();
        r.setRoleId(roleId);
        return r;
    }

    private static RoleKbRelationDO rel(String roleId, String kbId, Permission p, int level) {
        RoleKbRelationDO r = new RoleKbRelationDO();
        r.setRoleId(roleId);
        r.setKbId(kbId);
        r.setPermission(p.name());
        r.setMaxSecurityLevel(level);
        return r;
    }

    @Test
    void computeAccessibleKbIds_superAdmin_returnsAllKbIds() {
        KbAccessSubject s = new KbAccessSubject(
                "super1", "ANY", Set.of(RoleType.SUPER_ADMIN), 3);
        when(kbMetadataReader.listAllKbIds()).thenReturn(Set.of("KB1", "KB2", "KB3"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactlyInAnyOrder("KB1", "KB2", "KB3");
    }

    @Test
    void computeAccessibleKbIds_regularUser_returnsRbacOnly() {
        KbAccessSubject s = new KbAccessSubject(
                "u1", "DEPT_A", Set.of(RoleType.USER), 1);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("ROLE_A")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("ROLE_A", "KB1", Permission.READ, 1)));
        when(kbMetadataReader.filterExistingKbIds(any())).thenReturn(Set.of("KB1"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactly("KB1");
    }

    @Test
    void computeAccessibleKbIds_deptAdminSameDept_unionsRbacAndDeptKbs() {
        KbAccessSubject s = new KbAccessSubject(
                "admin1", "DEPT_OPS", Set.of(RoleType.DEPT_ADMIN), 2);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("OPS_ADMIN")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("OPS_ADMIN", "RBAC_KB", Permission.READ, 2)));
        when(kbMetadataReader.filterExistingKbIds(any())).thenReturn(Set.of("RBAC_KB"));
        when(kbMetadataReader.listKbIdsByDeptId("DEPT_OPS"))
                .thenReturn(Set.of("OPS_DEPT_KB"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactlyInAnyOrder("RBAC_KB", "OPS_DEPT_KB");
    }

    @Test
    void computeAccessibleKbIds_deptAdminMissingDept_returnsRbacOnly() {
        KbAccessSubject s = new KbAccessSubject(
                "admin1", null, Set.of(RoleType.DEPT_ADMIN), 2);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("OPS_ADMIN")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("OPS_ADMIN", "RBAC_KB", Permission.READ, 1)));
        when(kbMetadataReader.filterExistingKbIds(any())).thenReturn(Set.of("RBAC_KB"));

        Set<String> result = calculator.computeAccessibleKbIds(s, Permission.READ);

        assertThat(result).containsExactly("RBAC_KB");
    }

    @Test
    void computeMaxSecurityLevels_superAdmin_returnsThreeForAllRequestedKbs() {
        KbAccessSubject s = new KbAccessSubject(
                "super1", "ANY", Set.of(RoleType.SUPER_ADMIN), 3);

        Map<String, Integer> result = calculator.computeMaxSecurityLevels(
                s, Set.of("KB1", "KB2"));

        assertThat(result).containsEntry("KB1", 3).containsEntry("KB2", 3);
    }

    @Test
    void computeMaxSecurityLevels_regularUser_returnsRbacCeilingPerKb() {
        KbAccessSubject s = new KbAccessSubject(
                "u1", "DEPT_A", Set.of(RoleType.USER), 1);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("ROLE_A")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        rel("ROLE_A", "KB1", Permission.READ, 1),
                        rel("ROLE_A", "KB2", Permission.READ, 0)));

        Map<String, Integer> result = calculator.computeMaxSecurityLevels(
                s, Set.of("KB1", "KB2"));

        assertThat(result).containsEntry("KB1", 1).containsEntry("KB2", 0);
    }

    @Test
    void computeMaxSecurityLevels_deptAdmin_unionsCeilingWithSameDeptKbs() {
        KbAccessSubject s = new KbAccessSubject(
                "admin1", "DEPT_OPS", Set.of(RoleType.DEPT_ADMIN), 2);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ur("OPS_ADMIN")));
        when(roleKbRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(rel("OPS_ADMIN", "RBAC_KB", Permission.READ, 1)));
        when(kbMetadataReader.filterKbIdsByDept(any(), eq("DEPT_OPS")))
                .thenReturn(Set.of("OPS_DEPT_KB"));

        Map<String, Integer> result = calculator.computeMaxSecurityLevels(
                s, Set.of("RBAC_KB", "OPS_DEPT_KB"));

        // RBAC ceiling 1 on RBAC_KB; dept admin ceiling 2 on OPS_DEPT_KB; takes Math::max where both apply
        assertThat(result).containsEntry("RBAC_KB", 1).containsEntry("OPS_DEPT_KB", 2);
    }
}
```

(Add `import static org.mockito.ArgumentMatchers.eq;` to the file imports.)

- [ ] **Step 2: Run tests; verify compilation fails**

Run: `mvn -pl bootstrap test -Dtest=KbAccessCalculatorTest -q`
Expected: COMPILATION FAILURE — `KbAccessCalculator` symbol unresolved.

### Task 1.6: Implement `KbAccessCalculator`

**Files:**
- Create: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessCalculator.java`

The implementation absorbs `KbRbacAccessSupport.computeRbacKbIdsFor` (current location: `bootstrap/.../user/service/impl/KbRbacAccessSupport.java`) plus the DEPT_ADMIN union and per-KB security-level logic that today is split between `KbAccessServiceImpl.computeDeptAdminAccessibleKbIds` (private) and `KbAccessServiceImpl.getMaxSecurityLevelsForKbs` (public, leaky).

- [ ] **Step 1: Create file**

```java
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

package com.knowledgebase.ai.ragent.user.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.knowledgebase.ai.ragent.framework.context.Permission;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KB 访问权限计算器 — 纯函数；输入 {@link KbAccessSubject},输出可访问 KB 集 / 各 KB 密级上限。
 *
 * <p>PR3 不变量 PR3-1：本类不得 import {@code UserContext} / {@code LoginUser} /
 * {@code UserProfileLoader}（ArchUnit 守门）。所有主体信息通过 {@link KbAccessSubject}
 * 由调用方传入。
 *
 * <p>取代 PR2 的 {@code KbRbacAccessSupport} 静态工具 + {@code KbAccessServiceImpl}
 * 的 {@code computeDeptAdminAccessibleKbIds} 私有 + {@code getMaxSecurityLevelsForKbs}
 * 偷读 ThreadLocal 的实现段（spec §0.1）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbAccessCalculator {

    private final UserRoleMapper userRoleMapper;
    private final RoleKbRelationMapper roleKbRelationMapper;
    private final KbMetadataReader kbMetadataReader;

    /** SUPER_ADMIN 视角下"全集"的密级上限；与 KbAccessServiceImpl 原口径保持一致。*/
    private static final int SUPER_ADMIN_LEVEL_CEILING = 3;

    /**
     * 计算 subject 在 {@code minPermission} 下可访问的 KB ID 集合。
     *
     * <p>SUPER_ADMIN → {@code kbMetadataReader.listAllKbIds()}（物化全集，
     * 供 admin-views-target 报表用；port 出口由 {@code KbAccessServiceImpl}
     * 包装为 {@code AccessScope.all()} sentinel）。
     *
     * <p>DEPT_ADMIN → RBAC ∪ 同部门 KB（subject.deptId 为 null 时 fallback RBAC-only
     * + WARN 日志）。
     *
     * <p>USER → RBAC only。
     */
    public Set<String> computeAccessibleKbIds(KbAccessSubject subject, Permission minPermission) {
        if (subject.isSuperAdmin()) {
            return kbMetadataReader.listAllKbIds();
        }
        Set<String> rbacKbs = computeRbacKbIds(subject.userId(), minPermission);
        if (subject.isDeptAdmin()) {
            String deptId = subject.deptId();
            if (deptId == null) {
                log.warn("DEPT_ADMIN 用户未挂载部门, userId={}", subject.userId());
                return rbacKbs;
            }
            rbacKbs.addAll(kbMetadataReader.listKbIdsByDeptId(deptId));
        }
        return rbacKbs;
    }

    /**
     * 计算 subject 在指定 KB 集合上的最高密级上限映射。
     *
     * <p>SUPER_ADMIN → 每个 KB 上限 {@value #SUPER_ADMIN_LEVEL_CEILING}（与原 KbAccessServiceImpl 一致）。
     * <p>USER → RBAC 关系上的 max(level)。
     * <p>DEPT_ADMIN → RBAC ∪ 本部门 KB（本部门 KB 上限取 subject.maxSecurityLevel,
     * RBAC 重叠取 {@code Math::max}）。
     */
    public Map<String, Integer> computeMaxSecurityLevels(
            KbAccessSubject subject, Collection<String> kbIds) {
        if (subject == null || kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyMap();
        }
        if (subject.isSuperAdmin()) {
            Map<String, Integer> result = new HashMap<>(kbIds.size() * 2);
            for (String kbId : kbIds) {
                if (kbId != null) {
                    result.put(kbId, SUPER_ADMIN_LEVEL_CEILING);
                }
            }
            return result;
        }

        Map<String, Integer> result = new HashMap<>();

        // RBAC: 2 queries
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, subject.userId()));
        if (!userRoles.isEmpty()) {
            List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();
            List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                    Wrappers.lambdaQuery(RoleKbRelationDO.class)
                            .in(RoleKbRelationDO::getRoleId, roleIds)
                            .in(RoleKbRelationDO::getKbId, kbIds));
            for (RoleKbRelationDO rel : relations) {
                Integer level = rel.getMaxSecurityLevel() == null ? 0 : rel.getMaxSecurityLevel();
                result.merge(rel.getKbId(), level, Math::max);
            }
        }

        // DEPT_ADMIN: 同部门 KB 上限 = subject.maxSecurityLevel,与 RBAC merge 取大
        if (subject.isDeptAdmin() && subject.deptId() != null) {
            int deptCeiling = subject.maxSecurityLevel();
            Set<String> sameDeptKbIds = kbMetadataReader.filterKbIdsByDept(kbIds, subject.deptId());
            for (String kbId : sameDeptKbIds) {
                result.merge(kbId, deptCeiling, Math::max);
            }
        }
        return result;
    }

    /** RBAC 路径：userId → roles → kb_relations → 过滤 permission ≥ minPermission → 过滤已删除 KB */
    private Set<String> computeRbacKbIds(String userId, Permission minPermission) {
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class)
                        .eq(UserRoleDO::getUserId, userId));
        if (userRoles.isEmpty()) {
            return new HashSet<>();
        }
        List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();

        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class)
                        .in(RoleKbRelationDO::getRoleId, roleIds));
        Set<String> kbIds = relations.stream()
                .filter(r -> permissionSatisfies(r.getPermission(), minPermission))
                .map(RoleKbRelationDO::getKbId)
                .collect(Collectors.toCollection(HashSet::new));

        if (!kbIds.isEmpty()) {
            kbIds = new HashSet<>(kbMetadataReader.filterExistingKbIds(kbIds));
        }
        return kbIds;
    }

    private static boolean permissionSatisfies(String actual, Permission required) {
        if (actual == null) return false;
        try {
            return Permission.valueOf(actual).ordinal() >= required.ordinal();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown permission value in DB: {}", actual);
            return false;
        }
    }
}
```

- [ ] **Step 2: Run all calculator tests; verify all 7 pass**

Run: `mvn -pl bootstrap test -Dtest=KbAccessCalculatorTest -q`
Expected: 7 tests run, 0 failures.

- [ ] **Step 3: Run all bootstrap tests to confirm no collateral breakage**

Run: `mvn -pl bootstrap test -q`
Expected: BUILD SUCCESS (preexisting baseline failures from root CLAUDE.md ignored — `MilvusCollectionTests`, `InvoiceIndexDocumentTests`, `PgVectorStoreServiceTest.testChineseCharacterInsertion`, `IntentTreeServiceTests.initFromFactory`, `VectorTreeIntentClassifierTests`).

### Task 1.7: Commit

- [ ] **Step 1: Stage and commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubject.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactory.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactoryImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/support/KbAccessCalculator.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/support/KbAccessSubjectFactoryImplTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/support/KbAccessCalculatorTest.java
git commit -m "$(cat <<'EOF'
feat(security): introduce KbAccessSubject + factory + calculator (PR3 c1)

新增 user/service/support/ 包,容纳 PR3 的 target-aware 权限计算单元:

- KbAccessSubject (record) — userId/deptId/roleTypes/maxSecurityLevel + isSuperAdmin/isDeptAdmin
- KbAccessSubjectFactory(Impl) — 项目内唯一把 UserContext/UserProfileLoader 转成 subject
  的入口;currentOrThrow 在系统态/no-user 时 fail-closed (PR1 invariant B 延伸)
- KbAccessCalculator — 纯函数计算器;吸收 KbRbacAccessSupport + KbAccessServiceImpl 的
  computeDeptAdminAccessibleKbIds private + getMaxSecurityLevelsForKbs 决策段;不 import
  UserContext/LoginUser (PR3-1 不变量,c4 加 ArchUnit 守门)
- 12 个单测覆盖 SUPER_ADMIN / DEPT_ADMIN implicit / 跨部门 / per-KB level / 工厂三态

c1 仅新增,不修改现有调用方;c2 才把 KbAccessServiceImpl 等迁过来。

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR3
Spec: docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Commit 2 — Port signature collapse + impl migration + caller migration + test fixture

This is the **single big-bang commit**: `KbReadAccessPort` signature change forces simultaneous updates to its implementation and all callers. Cannot be split — intermediate state would not compile.

### Task 2.1: Collapse `KbReadAccessPort` signatures

**Files:**
- Modify: `framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbReadAccessPort.java`

- [ ] **Step 1: Drop `userId` params and remove deprecated default**

Replace the file contents (everything between `package` and the final closing brace) with:

```java
package com.knowledgebase.ai.ragent.framework.security.port;

import com.knowledgebase.ai.ragent.framework.context.Permission;

import java.util.Collection;
import java.util.Map;

/**
 * 知识库读取侧权限端口（PR3 起 current-user only）。
 * RAG 检索路径和知识库列表查询的授权入口；admin-views-target 路径不走此 port。
 */
public interface KbReadAccessPort {

    /**
     * 获取**当前登录用户**的访问范围。
     * SUPER_ADMIN 返回 {@link AccessScope.All}；其他角色返回 {@link AccessScope.Ids}。
     * 调用方应在调用前自行守 {@code UserContext.hasUser()},否则实现层抛 ClientException。
     */
    AccessScope getAccessScope(Permission minPermission);

    /**
     * 校验当前用户对指定 KB 的 READ 权限,无权抛 ClientException。
     * SUPER_ADMIN 和系统态直接放行。
     */
    void checkReadAccess(String kbId);

    /**
     * 批量解析**当前登录用户**对一组 KB 的最高安全等级。
     * 返回 map 仅包含 kbIds 中用户实际拥有访问权的 KB。
     */
    Map<String, Integer> getMaxSecurityLevelsForKbs(Collection<String> kbIds);
}
```

(Existing license header retained at file top.)

- [ ] **Step 2: Verify framework module compile fails (it should — implementation lags)**

Run: `mvn -pl framework compile -q`
Expected: BUILD SUCCESS (port is interface-only; no impl in framework). Now bootstrap will fail to compile until §2.2 done.

### Task 2.2: Rewrite `KbAccessServiceImpl` to delegate to factory + calculator

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1: Add imports**

Append (after existing user-service imports):

```java
import com.knowledgebase.ai.ragent.user.service.support.KbAccessCalculator;
import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubject;
import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubjectFactory;
```

- [ ] **Step 2: Add two new injected fields**

Right after `private final KbMetadataReader kbMetadataReader;` (line 83):

```java
    private final KbAccessSubjectFactory subjectFactory;
    private final KbAccessCalculator calculator;
```

- [ ] **Step 3: Rewrite `getAccessScope`**

Replace lines 134-140 (`getAccessScope(String userId, Permission minPermission)`) with:

```java
    @Override
    public AccessScope getAccessScope(Permission minPermission) {
        KbAccessSubject s = subjectFactory.currentOrThrow();
        return s.isSuperAdmin()
                ? AccessScope.all()
                : AccessScope.ids(calculator.computeAccessibleKbIds(s, minPermission));
    }
```

- [ ] **Step 4: Rewrite `getAccessibleKbIds(userId, p)` (deprecated KbAccessService method)**

Replace lines 85-125 with:

```java
    @Override
    public Set<String> getAccessibleKbIds(String userId, Permission minPermission) {
        boolean cacheable = minPermission == Permission.READ;
        String cacheKey = CACHE_PREFIX + userId;
        if (cacheable) {
            RBucket<Set<String>> bucket = redissonClient.getBucket(cacheKey);
            Set<String> cached = bucket.get();
            if (cached != null) {
                return cached;
            }
        }
        // PR3: 永远走 forTargetUser 路径(spec §5.8 决策);decision based on userId param,
        // 不再读 caller's UserContext;cache 由 userId 索引,值反映 target 真值
        KbAccessSubject s = subjectFactory.forTargetUser(userId);
        Set<String> result = calculator.computeAccessibleKbIds(s, minPermission);
        if (cacheable) {
            redissonClient.getBucket(cacheKey).<Set<String>>set(result, CACHE_TTL);
        }
        return result;
    }
```

- [ ] **Step 5: Delete two private helpers**

Remove the private methods `computeDeptAdminAccessibleKbIds` (lines 147-158) and `computeRbacKbIds` (lines 160-164). They no longer have callers.

- [ ] **Step 6: Rewrite `getMaxSecurityLevelsForKbs`**

Replace lines 341-386 (`getMaxSecurityLevelsForKbs(String userId, Collection<String> kbIds)` and its body) with:

```java
    @Override
    public Map<String, Integer> getMaxSecurityLevelsForKbs(Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyMap();
        }
        KbAccessSubject s = subjectFactory.currentOrThrow();
        return calculator.computeMaxSecurityLevels(s, kbIds);
    }
```

- [ ] **Step 7: Compile to verify shape**

Run: `mvn -pl bootstrap compile -q`
Expected: COMPILATION ERRORS at caller sites: `KbScopeResolverImpl.java:47`, `RAGChatServiceImpl.java:125`, `MultiChannelRetrievalEngine.java:262`, `AccessServiceImpl.java:145`. Tasks 2.3-2.6 fix these.

### Task 2.3: Migrate `KbScopeResolverImpl`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java`

- [ ] **Step 1: Drop `userId` arg at line 47**

Change line 47 from:
```java
        return kbReadAccess.getAccessScope(user.getUserId(), Permission.READ);
```
to:
```java
        return kbReadAccess.getAccessScope(Permission.READ);
```

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: still failing — 3 more callers.

### Task 2.4: Migrate `RAGChatServiceImpl`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`

- [ ] **Step 1: Drop `userId` arg at line 125**

Change line 125 from:
```java
            accessScope = kbReadAccess.getAccessScope(userId, Permission.READ);
```
to:
```java
            accessScope = kbReadAccess.getAccessScope(Permission.READ);
```

(Line 119 `String userId = UserContext.getUserId();` retained — used by `callbackFactory.createChatEventHandler` invocation in commit 4.)

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: still failing — 2 more callers.

### Task 2.5: Migrate `MultiChannelRetrievalEngine`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`

- [ ] **Step 1: Drop `userId` arg at line 262**

Change line 262 from:
```java
            kbSecurityLevels = kbReadAccess.getMaxSecurityLevelsForKbs(UserContext.getUserId(), ids.kbIds());
```
to:
```java
            kbSecurityLevels = kbReadAccess.getMaxSecurityLevelsForKbs(ids.kbIds());
```

- [ ] **Step 2: Check if `UserContext` import becomes unused**

Run: `grep -n "UserContext" bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java`
- If line 261 still uses `UserContext.hasUser()`, the import stays — done.
- If only the deleted line referenced `UserContext`, also remove `import com.knowledgebase.ai.ragent.framework.context.UserContext;`.

(Line 261's `UserContext.hasUser()` guard preserves fail-closed semantics for unauthenticated paths; keep it.)

- [ ] **Step 3: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: still failing — 1 more caller (`AccessServiceImpl`).

### Task 2.6: Migrate `AccessServiceImpl` (kill the leak)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java`

- [ ] **Step 1: Update imports**

Add these lines to imports:
```java
import com.knowledgebase.ai.ragent.user.service.support.KbAccessCalculator;
import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubject;
import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubjectFactory;
```

Remove this line:
```java
import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;
```

- [ ] **Step 2: Replace `kbReadAccess` field with factory + calculator**

Replace line 66 (`private final KbReadAccessPort kbReadAccess;`) with:

```java
    private final KbAccessSubjectFactory subjectFactory;
    private final KbAccessCalculator calculator;
```

- [ ] **Step 3: Rewrite `listUserKbGrants` Step 1 + 5 (kill the leak)**

Replace lines 117-198 (`listUserKbGrants` method body) with:

```java
    @Override
    public List<UserKbGrantVO> listUserKbGrants(String userId) {
        UserDO user = userMapper.selectById(userId);
        if (user == null) {
            throw new ClientException("目标用户不存在");
        }

        // PR3: target-aware subject;权限决策仅依赖 subject 字段,不读 caller 的 UserContext
        KbAccessSubject target = subjectFactory.forTargetUser(userId);

        // Step 1: 真相范围 —— calculator 基于 target subject 计算
        Set<String> targetReadableKbIds = calculator.computeAccessibleKbIds(target, Permission.READ);
        if (targetReadableKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBaseDO> kbs = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class).in(KnowledgeBaseDO::getId, targetReadableKbIds));
        // PR3: target 的真实 ceilings,不再被 caller-context leak 污染
        Map<String, Integer> levels = calculator.computeMaxSecurityLevels(target, targetReadableKbIds);

        // Step 2: 显式 role 链
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId));
        Set<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).collect(Collectors.toSet());
        Map<String, String> explicitPermByKb = new HashMap<>();
        Map<String, List<String>> sourceRoleIdsByKb = new HashMap<>();
        if (!roleIds.isEmpty()) {
            List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                    Wrappers.lambdaQuery(RoleKbRelationDO.class)
                            .in(RoleKbRelationDO::getRoleId, roleIds)
                            .in(RoleKbRelationDO::getKbId, targetReadableKbIds));
            for (RoleKbRelationDO rel : relations) {
                explicitPermByKb.merge(rel.getKbId(), rel.getPermission(),
                        (a, b) -> maxPermission(a, b));
                sourceRoleIdsByKb.computeIfAbsent(rel.getKbId(), k -> new ArrayList<>()).add(rel.getRoleId());
            }
        }

        // Step 3: implicit 独立判（用 target subject 的 isDeptAdmin,不是 caller 的）
        String userDeptId = target.deptId();

        // Step 5: 取密级 + 拼装 VO
        List<UserKbGrantVO> out = new ArrayList<>(kbs.size());
        for (KnowledgeBaseDO kb : kbs) {
            String explicitPerm = explicitPermByKb.get(kb.getId());
            List<String> sourceIds = sourceRoleIdsByKb.getOrDefault(kb.getId(), Collections.emptyList());
            boolean implicit = target.isDeptAdmin()
                    && userDeptId != null
                    && userDeptId.equals(kb.getDeptId());
            // Step 4: effective
            String effectivePerm;
            if (target.isSuperAdmin()) {
                effectivePerm = Permission.MANAGE.name();
            } else if (implicit) {
                effectivePerm = Permission.MANAGE.name();
            } else {
                effectivePerm = explicitPerm;
            }

            Integer securityLevel = levels.getOrDefault(kb.getId(), 0);

            out.add(UserKbGrantVO.builder()
                    .kbId(kb.getId())
                    .kbName(kb.getName())
                    .deptId(kb.getDeptId())
                    .permission(effectivePerm)
                    .explicitPermission(explicitPerm)
                    .securityLevel(securityLevel)
                    .sourceRoleIds(sourceIds)
                    .implicit(implicit)
                    .build());
        }
        return out;
    }
```

- [ ] **Step 4: Delete the `computeTargetUserAccessibleKbIds` private helper**

Remove lines 312-336 entirely (the comment block + helper method body).

- [ ] **Step 5: Remove unused imports**

Run: `grep -n "import com.knowledgebase.ai.ragent.user.service.impl.KbRbacAccessSupport" bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java`

If the line exists, delete it. Same for any leftover unused imports (`KbMetadataReader` may or may not still be needed — check by `grep "kbMetadataReader\\." bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java` after Step 4; if zero hits, also remove `private final KbMetadataReader kbMetadataReader;` and the import).

- [ ] **Step 6: Compile bootstrap**

Run: `mvn -pl bootstrap compile -q`
Expected: BUILD SUCCESS.

### Task 2.7: Delete `KbRbacAccessSupport` static helper

**Files:**
- Delete: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbRbacAccessSupport.java`

- [ ] **Step 1: Verify zero callers**

Run: `grep -rn "KbRbacAccessSupport" bootstrap/src/main/java`
Expected: zero matches.

- [ ] **Step 2: Delete the file**

```bash
git rm bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbRbacAccessSupport.java
```

- [ ] **Step 3: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: BUILD SUCCESS.

### Task 2.8: Update `AccessServiceImplTest`

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/AccessServiceImplTest.java`

- [ ] **Step 1: Replace `kbReadAccess` mock with subject factory + calculator mocks**

Find `@Mock` block (near top of class). Remove:
```java
    @Mock
    private KbReadAccessPort kbReadAccess;
```
Add:
```java
    @Mock
    private KbAccessSubjectFactory subjectFactory;
    @Mock
    private KbAccessCalculator calculator;
```

Update imports accordingly:
- Remove `import com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort;`
- Add `import com.knowledgebase.ai.ragent.user.service.support.KbAccessCalculator;`
- Add `import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubject;`
- Add `import com.knowledgebase.ai.ragent.user.service.support.KbAccessSubjectFactory;`
- Add `import com.knowledgebase.ai.ragent.user.dao.dto.LoadedUserProfile;` if not present.

- [ ] **Step 2: Replace each existing stub of `getMaxSecurityLevelsForKbs` (5 sites)**

For each of the 5 stubs at approximately lines 206 / 219 / 240 / 265 / 294, replace:

```java
when(kbReadAccess.getMaxSecurityLevelsForKbs(eq(userId), any())).thenReturn(Map.of(kbId, 3));
```
with (adjust subject + map values to match each test's intent):

```java
KbAccessSubject targetSubject = new KbAccessSubject(
        userId, "DEPT_X", Set.of(RoleType.USER), 3 /*maxSecurityLevel as test expects*/);
when(subjectFactory.forTargetUser(userId)).thenReturn(targetSubject);
when(calculator.computeAccessibleKbIds(eq(targetSubject), eq(Permission.READ)))
        .thenReturn(Set.of(kbId));
when(calculator.computeMaxSecurityLevels(eq(targetSubject), any())).thenReturn(Map.of(kbId, 3));
```

For each existing `verify(kbReadAccess, ...)` call, replace with `verify(calculator, ...)` (matching the new method).

The intention: each test now stubs the **target** subject explicitly, then declares the calculator's expected return — so the test asserts the value flows correctly without going through the leaky port.

- [ ] **Step 3: Add T1 — admin-views-target ceiling not polluted**

Append a new test method:

```java
    @Test
    @DisplayName("PR3 T1: SUPER_ADMIN viewing FICC_USER returns target's ceiling, not 3")
    void superAdminViewingFiccUser_returnsTargetCeiling_notSuperAdminCeiling() {
        // Caller is SUPER_ADMIN (we don't even need to set UserContext —
        // calculator's decision now depends on target subject only)
        String targetId = "ficc_user_id";
        UserDO targetUser = new UserDO();
        targetUser.setId(targetId);
        targetUser.setDeptId("FICC_DEPT");
        when(userMapper.selectById(targetId)).thenReturn(targetUser);

        KbAccessSubject targetSubject = new KbAccessSubject(
                targetId, "FICC_DEPT", Set.of(RoleType.USER), 1);  // target's ceiling=1
        when(subjectFactory.forTargetUser(targetId)).thenReturn(targetSubject);
        when(calculator.computeAccessibleKbIds(eq(targetSubject), eq(Permission.READ)))
                .thenReturn(Set.of("OPS_KB"));
        when(calculator.computeMaxSecurityLevels(eq(targetSubject), any()))
                .thenReturn(Map.of("OPS_KB", 1));   // target's actual ceiling

        KnowledgeBaseDO opsKb = new KnowledgeBaseDO();
        opsKb.setId("OPS_KB");
        opsKb.setName("OPS-COB");
        opsKb.setDeptId("OPS_DEPT");
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(opsKb));

        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of());

        List<UserKbGrantVO> grants = accessService.listUserKbGrants(targetId);

        UserKbGrantVO opsGrant = grants.stream()
                .filter(g -> "OPS_KB".equals(g.getKbId()))
                .findFirst().orElseThrow();
        assertThat(opsGrant.getSecurityLevel()).isEqualTo(1);  // target's, not caller's 3
    }
```

- [ ] **Step 4: Add T2 — DEPT_ADMIN cross-dept implicit not polluted**

```java
    @Test
    @DisplayName("PR3 T2: OPS_ADMIN viewing FICC_USER does not apply OPS_ADMIN ceiling/implicit")
    void opsAdminViewingFiccUser_doesNotApplyOpsAdminDeptCeiling() {
        String targetId = "ficc_user_id";
        UserDO targetUser = new UserDO();
        targetUser.setId(targetId);
        targetUser.setDeptId("FICC_DEPT");
        when(userMapper.selectById(targetId)).thenReturn(targetUser);

        // Target is regular USER, ceiling=0 on FICC_KB; not DEPT_ADMIN
        KbAccessSubject targetSubject = new KbAccessSubject(
                targetId, "FICC_DEPT", Set.of(RoleType.USER), 0);
        when(subjectFactory.forTargetUser(targetId)).thenReturn(targetSubject);
        when(calculator.computeAccessibleKbIds(eq(targetSubject), eq(Permission.READ)))
                .thenReturn(Set.of("FICC_KB"));
        when(calculator.computeMaxSecurityLevels(eq(targetSubject), any()))
                .thenReturn(Map.of("FICC_KB", 0));

        KnowledgeBaseDO ficcKb = new KnowledgeBaseDO();
        ficcKb.setId("FICC_KB");
        ficcKb.setName("FICC-Internal");
        ficcKb.setDeptId("FICC_DEPT");
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(ficcKb));

        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(roleKbRelationMapper.selectList(any())).thenReturn(List.of());

        List<UserKbGrantVO> grants = accessService.listUserKbGrants(targetId);

        UserKbGrantVO grant = grants.stream()
                .filter(g -> "FICC_KB".equals(g.getKbId()))
                .findFirst().orElseThrow();
        assertThat(grant.getSecurityLevel()).isEqualTo(0);
        assertThat(grant.isImplicit()).isFalse();   // target is USER, not DEPT_ADMIN
    }
```

- [ ] **Step 5: Run AccessServiceImplTest**

Run: `mvn -pl bootstrap test -Dtest=AccessServiceImplTest -q`
Expected: all tests pass.

### Task 2.9: Update `RAGChatServiceImplSourcesTest` mock

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`

- [ ] **Step 1: Update mock to 4-arg form**

Change line ~125 from:
```java
        lenient().when(callbackFactory.createChatEventHandler(any(), any(), any())).thenReturn(callback);
```
to (will be 4-arg after commit 4 modifies the factory; but commit 2 must keep the test compilable):

**Wait — this test is a 3-arg form which still compiles in commit 2 (factory signature unchanged here)**. Defer the change to commit 4.

(Skip this step in commit 2 — left for commit 4 task 4.4.)

### Task 2.10: Update `KbScopeResolverImplTest` mock fixture

**Files:**
- Verify/modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolverImplTest.java`

- [ ] **Step 1: grep test for stale `getAccessScope(*, Permission.*)` 2-arg stubs**

Run: `grep -n "getAccessScope" bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolverImplTest.java`

If any stub is `when(kbReadAccess.getAccessScope(eq("u1"), eq(Permission.READ)))` form, simplify to `when(kbReadAccess.getAccessScope(eq(Permission.READ)))`.

- [ ] **Step 2: Run test**

Run: `mvn -pl bootstrap test -Dtest=KbScopeResolverImplTest -q`
Expected: all tests pass.

### Task 2.11: Run all bootstrap + framework tests

- [ ] **Step 1: Full suite**

Run: `mvn test -q -pl bootstrap,framework`
Expected: BUILD SUCCESS (preexisting baseline failures from root CLAUDE.md ignored).

If any unexpected test fails:
- Check whether the test mocks `kbReadAccess.getAccessScope(<userId>, ...)` — collapse to `getAccessScope(...)`.
- Check whether the test mocks `kbReadAccess.getMaxSecurityLevelsForKbs(<userId>, ...)` — drop the userId arg.

### Task 2.12: Commit

- [ ] **Step 1: Stage and commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbReadAccessPort.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KbScopeResolverImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/AccessServiceImplTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KbScopeResolverImplTest.java
git rm bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbRbacAccessSupport.java
git commit -m "$(cat <<'EOF'
refactor(security): collapse KbReadAccessPort to current-user-only + kill caller-context leak (PR3 c2)

Port + 4 callers + 1 test fixture 单 commit 大改:

KbReadAccessPort 签名:
- getAccessScope(userId, p) → getAccessScope(p)
- getMaxSecurityLevelsForKbs(userId, kbIds) → getMaxSecurityLevelsForKbs(kbIds)
- 删除 @Deprecated default getAccessibleKbIds(userId)

KbAccessServiceImpl 决策段 (修复 leak):
- getAccessScope/getMaxSecurityLevelsForKbs 改 subjectFactory.currentOrThrow + calculator
- getAccessibleKbIds(userId, p) (deprecated) 永远走 forTargetUser (spec §5.8)
- 删除 computeRbacKbIds + computeDeptAdminAccessibleKbIds 私有方法
- SUPER_ADMIN 在 getAccessScope 仍包装为 AccessScope.all() sentinel (spec review P2)

AccessServiceImpl.listUserKbGrants 修 admin-views-target leak:
- 改走 subjectFactory.forTargetUser(targetUserId) + calculator (PR3-3 不变量)
- 不再注入 KbReadAccessPort
- target 的 securityLevel/implicit 决策仅依赖 target subject 字段,不读 caller UserContext
- 删除 computeTargetUserAccessibleKbIds 私有 helper

调用方迁移:
- KbScopeResolverImpl:47 / RAGChatServiceImpl:125 / MultiChannelRetrievalEngine:262 砍 userId 参数

测试 fixture 修订 + T1/T2 contract tests:
- AccessServiceImplTest 5 个 stub 改用 calculator + subjectFactory mock
- 新增 PR3 T1 (SUPER_ADMIN 看 FICC_USER 不污染 ceiling) + T2 (OPS_ADMIN 跨部门 implicit 不污染)

KbRbacAccessSupport 静态工具删除 (逻辑迁入 calculator)

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR3
Spec: docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Commit 3 — Physically delete `getMaxSecurityLevelForKb` single-key dead method

The method has zero main/test callers (verified via grep earlier) AND has the same caller-context leak. Pure removal.

### Task 3.1: Verify zero callers (defensive)

- [ ] **Step 1: Search**

Run:
```bash
grep -rn "getMaxSecurityLevelForKb\b" bootstrap/src/main/java bootstrap/src/test/java framework/src
```

Expected: matches **only in**:
- `bootstrap/.../user/service/KbAccessService.java` (interface declaration — to be removed)
- `bootstrap/.../user/service/impl/KbAccessServiceImpl.java` (impl — to be removed)
- `bootstrap/.../user/controller/vo/UserKbGrantVO.java:45` (comment — to update)

If any other production or test file matches, **stop and reassess** — the spec assumption breaks.

### Task 3.2: Delete from interface + implementation

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/UserKbGrantVO.java`

- [ ] **Step 1: Delete interface declaration**

In `KbAccessService.java`, delete the lines (around 100-103):
```java
    /** ... javadoc ... */
    Integer getMaxSecurityLevelForKb(String userId, String kbId);
```

- [ ] **Step 2: Delete implementation**

In `KbAccessServiceImpl.java`, delete lines 283-339 (the entire `@Override public Integer getMaxSecurityLevelForKb(String userId, String kbId) { ... }` block).

- [ ] **Step 3: Update VO comment**

In `UserKbGrantVO.java:45`, change the comment from:
```java
    /** 该 KB 密级上限（getMaxSecurityLevelForKb） */
```
to:
```java
    /** 该 KB 密级上限（calculator.computeMaxSecurityLevels） */
```

- [ ] **Step 4: Compile + run tests**

Run: `mvn test -q -pl bootstrap`
Expected: BUILD SUCCESS (preexisting baseline failures ignored).

### Task 3.3: Commit

- [ ] **Step 1: Stage and commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/vo/UserKbGrantVO.java
git commit -m "$(cat <<'EOF'
refactor(security): physically delete dead method getMaxSecurityLevelForKb single-key (PR3 c3)

PR3-5 不变量:删除接口面 + 修 leak 一刀两断。

- KbAccessService.getMaxSecurityLevelForKb(userId, kbId) 接口声明删除
- KbAccessServiceImpl 同名实现删除 (原行 :284-339,自带 caller-context leak —
  实现读 UserContext.get() 决定 SUPER_ADMIN/DEPT_ADMIN bypass)
- 调用面 grep 在 bootstrap/src/main/java + bootstrap/src/test/java 零命中,
  纯死代码,直接物理删除而非 @Deprecated
- UserKbGrantVO:45 注释更新到新的 calculator 路径

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR3
Spec: docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Commit 4 — Handler ThreadLocal 收尾 + factory signature + ArchUnit

### Task 4.1: Add `userId` field to `StreamChatHandlerParams`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java`

- [ ] **Step 1: Add field**

After existing `private final String taskId;` (line 55), add:

```java
    /**
     * 当前请求的 userId(由 orchestrator 在请求线程上读 UserContext 后透传)。
     * Handler 类内部禁止再读 UserContext (PR3-4 不变量)。
     */
    private final String userId;
```

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: BUILD SUCCESS.

### Task 4.2: Update factory signature + builder chain

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java`

- [ ] **Step 1: Add `userId` parameter to `createChatEventHandler`**

Change the method signature + builder chain:

```java
    public StreamChatEventHandler createChatEventHandler(SseEmitter emitter,
                                                         String conversationId,
                                                         String taskId,
                                                         String userId) {
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(userId)
                .modelProperties(modelProperties)
                .memoryService(memoryService)
                .conversationMessageService(conversationMessageService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .evaluationService(evaluationService)
                .traceRecordService(traceRecordService)
                .suggestedQuestionsService(suggestedQuestionsService)
                .suggestedQuestionsExecutor(suggestedQuestionsExecutor)
                .ragConfigProperties(ragConfigProperties)
                .build();
        return new StreamChatEventHandler(params);
    }
```

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: COMPILATION ERROR at `RAGChatServiceImpl.java:112` (still passes 3 args).

### Task 4.3: Update `RAGChatServiceImpl` factory call site

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java`

- [ ] **Step 1: Move userId compute above factory call**

Currently line 112 calls factory; line 119 reads `userId`. Reorder so userId is read **first** then passed:

Replace lines 109-120 (between the `log.info(...)` call and the AccessScope block) with:

```java
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        String userId = UserContext.getUserId();   // PR3: 读一次 ThreadLocal,下游全程透传

        StreamChatEventHandler callback = callbackFactory.createChatEventHandler(
                emitter, actualConversationId, taskId, userId);

        // 初始化评测数据采集器
        EvaluationCollector evalCollector = new EvaluationCollector();
        evalCollector.setOriginalQuery(question);
        RagTraceContext.setEvalCollector(evalCollector);
```

(The original line 119 `String userId = UserContext.getUserId();` is removed because we hoisted it above the factory call.)

- [ ] **Step 2: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: BUILD SUCCESS.

### Task 4.4: Update `RAGChatServiceImplSourcesTest` mock to 4-arg

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java`

- [ ] **Step 1: Update mock**

Change line ~125 from:
```java
        lenient().when(callbackFactory.createChatEventHandler(any(), any(), any())).thenReturn(callback);
```
to:
```java
        lenient().when(callbackFactory.createChatEventHandler(any(), any(), any(), any())).thenReturn(callback);
```

- [ ] **Step 2: grep for any other 3-arg mocks**

Run: `grep -rn "createChatEventHandler(any" bootstrap/src/test/java`
For every match showing only 3 `any()`, expand to 4. Common locations:
- `bootstrap/src/test/java/.../rag/service/impl/RAGChatServiceImpl*Test.java` (multiple sibling tests)
- Any other test that mocks `StreamCallbackFactory`.

- [ ] **Step 3: Run RAG tests**

Run: `mvn -pl bootstrap test -Dtest='RAGChatServiceImpl*Test' -q`
Expected: all pass.

### Task 4.5: Remove `UserContext` from `StreamChatEventHandler`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java`

- [ ] **Step 1: Remove import**

Delete line 27:
```java
import com.knowledgebase.ai.ragent.framework.context.UserContext;
```

- [ ] **Step 2: Replace constructor read**

Change line 100 from:
```java
        this.userId = UserContext.getUserId();
```
to:
```java
        this.userId = params.getUserId();
```

- [ ] **Step 3: Replace `onComplete` async read**

Change line 190 (inside `onComplete`) from:
```java
        String messageId = memoryService.append(conversationId, UserContext.getUserId(),
                ChatMessage.assistant(answer.toString()), null);
```
to:
```java
        String messageId = memoryService.append(conversationId, this.userId,
                ChatMessage.assistant(answer.toString()), null);
```

- [ ] **Step 4: Compile**

Run: `mvn -pl bootstrap compile -q`
Expected: BUILD SUCCESS.

### Task 4.6: Add `StreamCallbackFactoryUserIdTest`

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactoryUserIdTest.java`

- [ ] **Step 1: Create file**

```java
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

package com.knowledgebase.ai.ragent.rag.service.handler;

import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.convention.ChatMessage;
import com.knowledgebase.ai.ragent.infra.config.AIModelProperties;
import com.knowledgebase.ai.ragent.rag.config.RAGConfigProperties;
import com.knowledgebase.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.knowledgebase.ai.ragent.rag.core.suggest.SuggestedQuestionsService;
import com.knowledgebase.ai.ragent.rag.service.ConversationGroupService;
import com.knowledgebase.ai.ragent.rag.service.ConversationMessageService;
import com.knowledgebase.ai.ragent.rag.service.RagEvaluationService;
import com.knowledgebase.ai.ragent.rag.service.RagTraceRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamCallbackFactoryUserIdTest {

    @Mock private AIModelProperties modelProperties;
    @Mock private ConversationMemoryService memoryService;
    @Mock private ConversationMessageService conversationMessageService;
    @Mock private ConversationGroupService conversationGroupService;
    @Mock private StreamTaskManager taskManager;
    @Mock private RagEvaluationService evaluationService;
    @Mock private RagTraceRecordService traceRecordService;
    @Mock private SuggestedQuestionsService suggestedQuestionsService;
    @Mock private RAGConfigProperties ragConfigProperties;
    @Mock private ThreadPoolTaskExecutor suggestedQuestionsExecutor;

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void factoryPropagatesUserIdToHandler_indep_of_threadlocal() {
        // Stream config defaults
        AIModelProperties.Stream s = new AIModelProperties.Stream();
        lenient().when(modelProperties.getStream()).thenReturn(s);
        lenient().when(ragConfigProperties.getSuggestionsEnabled()).thenReturn(false);
        when(conversationGroupService.findConversation(any(), any())).thenReturn(null);

        StreamCallbackFactory factory = new StreamCallbackFactory(
                modelProperties, memoryService, conversationMessageService,
                conversationGroupService, taskManager, evaluationService,
                traceRecordService, suggestedQuestionsService, ragConfigProperties,
                suggestedQuestionsExecutor);

        SseEmitter emitter = mock(SseEmitter.class);
        UserContext.clear();   // simulate no UserContext

        StreamChatEventHandler handler = factory.createChatEventHandler(
                emitter, "conv-1", "task-1", "INJECTED_UID");

        // Drive onContent + onComplete; verify memoryService.append got injected userId
        handler.onContent("hello answer");
        handler.onComplete();

        verify(memoryService).append(eq("conv-1"), eq("INJECTED_UID"), any(ChatMessage.class), isNull());
    }
}
```

- [ ] **Step 2: Run test**

Run: `mvn -pl bootstrap test -Dtest=StreamCallbackFactoryUserIdTest -q`
Expected: 1 test runs, 0 failures.

### Task 4.7: Add 3 ArchUnit rules to `PermissionBoundaryArchTest`

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/PermissionBoundaryArchTest.java`

- [ ] **Step 1: Add imports**

Add these imports near the top of the file:

```java
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
```

- [ ] **Step 2: Append 3 rules + helper inside the class**

Append before the closing brace:

```java
    @ArchTest
    static final ArchRule rag_handler_package_no_user_context =
            noClasses()
                    .that().resideInAPackage("com.knowledgebase.ai.ragent.rag.service.handler..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.knowledgebase.ai.ragent.framework.context.UserContext")
                    .because("PR3-4: handler 类不读 ThreadLocal,userId 由构造参数显式传入");

    @ArchTest
    static final ArchRule kb_access_calculator_no_user_context =
            noClasses()
                    .that().haveFullyQualifiedName(
                            "com.knowledgebase.ai.ragent.user.service.support.KbAccessCalculator")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedNameMatching(
                            "com\\.nageoffer\\.ai\\.ragent\\.framework\\.context\\.(UserContext|LoginUser)")
                    .because("PR3-1: Calculator 纯函数性");

    @ArchTest
    static final ArchRule kb_read_access_port_no_userid_param =
            methods()
                    .that().areDeclaredInClassesThat()
                    .haveFullyQualifiedName(
                            "com.knowledgebase.ai.ragent.framework.security.port.KbReadAccessPort")
                    .should(notHaveStringParameterNamedUserId())
                    .because("PR3-2: Port 签名 current-user only");

    private static ArchCondition<JavaMethod> notHaveStringParameterNamedUserId() {
        return new ArchCondition<JavaMethod>("not have a String parameter named 'userId'") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                int idx = 0;
                for (var param : method.getParameters()) {
                    String typeName = param.getRawType().getName();
                    String name = param.getName();
                    if ("java.lang.String".equals(typeName) && "userId".equals(name)) {
                        events.add(SimpleConditionEvent.violated(method,
                                String.format("Method %s has String parameter at index %d "
                                                + "named 'userId' — PR3-2 forbids userId on KbReadAccessPort",
                                        method.getFullName(), idx)));
                    }
                    idx++;
                }
            }
        };
    }
```

- [ ] **Step 3: Compile + run ArchTest**

Run: `mvn -pl bootstrap test -Dtest=PermissionBoundaryArchTest -q`
Expected: all rules pass (no architecture violations after commit 2 + earlier commit 4 steps).

If `kb_read_access_port_no_userid_param` fails: re-check `KbReadAccessPort.java` from commit 2 — confirm both methods dropped `userId`. If `kb_access_calculator_no_user_context` fails: re-check `KbAccessCalculator.java` imports — must not contain `UserContext` or `LoginUser`. If `rag_handler_package_no_user_context` fails: re-check `StreamChatEventHandler.java` import block.

### Task 4.8: Run all tests + commit

- [ ] **Step 1: Full bootstrap test suite**

Run: `mvn test -q -pl bootstrap`
Expected: BUILD SUCCESS (preexisting baseline failures ignored).

- [ ] **Step 2: Stage and commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatHandlerParams.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactory.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler/StreamChatEventHandler.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/handler/StreamCallbackFactoryUserIdTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/service/impl/RAGChatServiceImplSourcesTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/arch/PermissionBoundaryArchTest.java
git commit -m "$(cat <<'EOF'
refactor(security): handler ThreadLocal 收尾 + factory userId + 3 ArchUnit rules (PR3 c4)

PR3-4 不变量:rag/service/handler/ 包零 UserContext 读;async 回调用 final 字段。

- StreamChatHandlerParams 加 userId 字段
- StreamCallbackFactory.createChatEventHandler 签名扩 userId 参数
- RAGChatServiceImpl 把 UserContext.getUserId() 上提至 factory 调用前,
  factory 透传到 handler params (单点 ThreadLocal 读)
- StreamChatEventHandler 删 UserContext import;构造期/onComplete 改用 this.userId
- StreamCallbackFactoryUserIdTest 验证 factory 透传不被 ThreadLocal 状态影响
- RAGChatServiceImplSourcesTest mock 4 个 any() (factory 4-arg 形)
- PermissionBoundaryArchTest 新增 3 条 ArchUnit 规则:
  * rag_handler_package_no_user_context (PR3-4)
  * kb_access_calculator_no_user_context (PR3-1)
  * kb_read_access_port_no_userid_param (PR3-2,自定义 ArchCondition)

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR3
Spec: docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Commit 5 — `RagVectorTypeValidator` upgrade to fail-fast + tests + config docs

### Task 5.1: Failing test for `RagVectorTypeValidator`

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidatorTest.java`

- [ ] **Step 1: Create test file**

```java
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

package com.knowledgebase.ai.ragent.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagVectorTypeValidatorTest {

    @Test
    void opensearch_validatesSilently() {
        RagVectorTypeValidator v = new RagVectorTypeValidator();
        ReflectionTestUtils.setField(v, "vectorType", "opensearch");
        ReflectionTestUtils.setField(v, "allowIncomplete", false);

        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void milvus_withoutOverride_throwsIllegalStateException() {
        RagVectorTypeValidator v = new RagVectorTypeValidator();
        ReflectionTestUtils.setField(v, "vectorType", "milvus");
        ReflectionTestUtils.setField(v, "allowIncomplete", false);

        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("milvus")
                .hasMessageContaining("allow-incomplete-backend");
    }

    @Test
    void milvus_withOverride_validatesAndWarns() {
        RagVectorTypeValidator v = new RagVectorTypeValidator();
        ReflectionTestUtils.setField(v, "vectorType", "milvus");
        ReflectionTestUtils.setField(v, "allowIncomplete", true);

        // Note: log assertion would require logback test setup; here we just
        // assert no throw — log.warn side effect is contractual but not asserted.
        assertThatCode(v::validate).doesNotThrowAnyException();
        assertThat(true).isTrue();   // explicit pass
    }
}
```

- [ ] **Step 2: Run test; verify they fail**

Run: `mvn -pl bootstrap test -Dtest=RagVectorTypeValidatorTest -q`
Expected: tests run; `milvus_withoutOverride_throwsIllegalStateException` FAILS (current impl only logs).

### Task 5.2: Upgrade `RagVectorTypeValidator` body

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java`

- [ ] **Step 1: Replace `validate()` body and add `allowIncomplete` field**

Replace the entire class body (preserving the license header) with:

```java
package com.knowledgebase.ai.ragent.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量库类型配置校验器(PR3 起 fail-fast)。
 *
 * <p>生产仅支持 OpenSearch:Milvus / Pg 实现不回填
 * {@code RetrievedChunk.kbId} / {@code securityLevel} / {@code docId} /
 * {@code chunkIndex},{@code AuthzPostProcessor} 在认证会话里 fail-closed,
 * "回答来源"卡片永远为空。
 *
 * <p>启动期非 OpenSearch 后端 → 抛 {@link IllegalStateException} 阻止 context
 * 启动,除非显式 {@code rag.vector.allow-incomplete-backend=true} dev override。
 *
 * <p>对应路线图 §3 阶段 A 成功标准 + PR3-6 不变量
 * (docs/dev/design/2026-04-26-permission-roadmap.md)。
 */
@Slf4j
@Component
public class RagVectorTypeValidator {

    @Value("${rag.vector.type:opensearch}")
    private String vectorType;

    @Value("${rag.vector.allow-incomplete-backend:false}")
    private boolean allowIncomplete;

    @PostConstruct
    public void validate() {
        if ("opensearch".equalsIgnoreCase(vectorType)) {
            return;
        }
        if (allowIncomplete) {
            log.warn(
                    "rag.vector.type={} 不是 opensearch,但 allow-incomplete-backend=true: "
                  + "权限/security_level 过滤可能不完整,'回答来源'卡片将为空。仅 dev 用,"
                  + "不要在生产或共享环境启用。",
                    vectorType);
            return;
        }
        throw new IllegalStateException(
                "rag.vector.type=" + vectorType + " 不被支持。"
              + "当前生产仅支持 opensearch;dev 临时切换其他后端需"
              + "rag.vector.allow-incomplete-backend=true。"
              + "见 docs/dev/followup/backlog.md SL-1");
    }
}
```

- [ ] **Step 2: Run test**

Run: `mvn -pl bootstrap test -Dtest=RagVectorTypeValidatorTest -q`
Expected: 3 tests run, 0 failures.

### Task 5.3: Document `allow-incomplete-backend` in `application.yaml`

**Files:**
- Modify: `bootstrap/src/main/resources/application.yaml`

- [ ] **Step 1: grep current `rag.vector` block**

Run: `grep -n -A3 "^rag:" bootstrap/src/main/resources/application.yaml`

Find the `vector:` sub-block (typically `rag.vector.type`).

- [ ] **Step 2: Add comment line**

Right after `type: opensearch` (or whatever the current setting is), add:

```yaml
    # allow-incomplete-backend: false  # dev override; set true to allow milvus/pg, otherwise startup fails (PR3-6)
```

(Keep it commented out — production deployment requires explicit opt-in.)

### Task 5.4: Add grep守门 script

**Files:**
- Create: `docs/dev/verification/permission-pr3-leak-free.sh`

- [ ] **Step 1: Create script**

```bash
#!/usr/bin/env bash
# PR3 守门: 验证 admin-views-target 不再走 port + 单 key 方法已删 + handler 不读 ThreadLocal + port 签名 current-user only
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if ! command -v rg >/dev/null 2>&1; then
    echo "✗ ripgrep (rg) not installed; cannot run PR3 grep gate. Install rg first."
    exit 2
fi

fail=0

# PR3-3: AccessServiceImpl 不调 kbReadAccess
if rg -n "kbReadAccess\." \
    bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/AccessServiceImpl.java; then
    echo "✗ PR3-3 violation: AccessServiceImpl 调用了 KbReadAccessPort"
    fail=1
fi

# PR3-5: 单 key getMaxSecurityLevelForKb 物理删除
hits=$(rg -c "getMaxSecurityLevelForKb\b" \
    bootstrap/src/main/java bootstrap/src/test/java || true)
if [ -n "$hits" ]; then
    echo "✗ PR3-5 violation: getMaxSecurityLevelForKb 仍存在:"
    echo "$hits"
    fail=1
fi

# PR3-2: KbReadAccessPort 不接受 userId 参数
if rg -n "String\s+userId" \
    framework/src/main/java/com/nageoffer/ai/ragent/framework/security/port/KbReadAccessPort.java; then
    echo "✗ PR3-2 violation: KbReadAccessPort 仍有 String userId 参数"
    fail=1
fi

# PR3-4: handler 包不 import UserContext
if rg -n "import.*\\.UserContext\\b" \
    bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/service/handler; then
    echo "✗ PR3-4 violation: handler 包仍 import UserContext"
    fail=1
fi

if [ $fail -eq 0 ]; then
    echo "✓ PR3 4 条 grep 守门全通过"
fi
exit $fail
```

- [ ] **Step 2: Make executable + run on the local repo**

```bash
chmod +x docs/dev/verification/permission-pr3-leak-free.sh
./docs/dev/verification/permission-pr3-leak-free.sh
```

Expected output: `✓ PR3 4 条 grep 守门全通过`

If any guard fails: re-check the corresponding commit's edits.

### Task 5.5: Update roadmap doc

**Files:**
- Modify: `docs/dev/design/2026-04-26-permission-roadmap.md`

- [ ] **Step 1: Update §1 当前坐标 row "进行中" + "下一步"**

Find the table row currently saying "进行中 | （无）— 准备启动 PR2" (or whatever PR2-merge note exists). Update to:

```
| 进行中 | （无）— PR3 已完成,准备启动 PR4 |
| 下一步 | **PR4** — `RetrievalScopeBuilder` 引入 + `RagRequest.kbIds + scopeMode` 字段迁移（详见 §3 阶段 B） |
```

Update "已完成" row to add PR3 entry:

```
| 已完成 | …… **PR3**（2026-04-27）— `KbAccessSubject + Factory + Calculator` 引入,修复 admin-views-target 路径 security-level caller-context leak;`KbReadAccessPort` 签名级回归 current-user only;handler 包零 UserContext;`RagVectorTypeValidator` 升级 fail-fast。3 ArchUnit 规则 + grep 守门。 |
```

- [ ] **Step 2: Update §3 PR3 段落标记为已完成**

Find the section heading `#### PR3 — KbAccessCalculator 提取 + caller-context 泄漏修复`. Add status marker after it:

```
#### PR3 ✅ 已完成（2026-04-27）— Access Calculator + ThreadLocal Guards
```

Append落地内容 block after the original section content:

```markdown
落地内容：
- `KbAccessSubject` (record) + `KbAccessSubjectFactory(Impl)` + `KbAccessCalculator` 引入
- `KbReadAccessPort` 签名级回归 current-user only(`getAccessScope(p)` / `getMaxSecurityLevelsForKbs(kbIds)`)
- `KbAccessServiceImpl` 内部改注 calculator;deprecated `getAccessibleKbIds(userId, p)` 永远走 forTargetUser
- `AccessServiceImpl.listUserKbGrants` 改走 `forTargetUser + calculator`,修 admin-views-target 路径 caller-context leak (SUPER_ADMIN 看 FICC_USER 不再误报满级)
- 物理删除单 key `getMaxSecurityLevelForKb`(无 caller + 自带 leak)
- `StreamChatEventHandler` 全类去 UserContext;`StreamCallbackFactory.createChatEventHandler` 签名扩 `userId` 参数
- `RagVectorTypeValidator` 升级为 fail-fast(`allow-incomplete-backend=true` 是 dev override)
- 3 条 ArchUnit 规则(`PermissionBoundaryArchTest`)+ `permission-pr3-leak-free.sh` grep 守门
- T1/T2 admin-views-target 反污染契约测试
```

### Task 5.6: Run full test suite + commit

- [ ] **Step 1: All tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS (preexisting baseline failures from root CLAUDE.md ignored).

- [ ] **Step 2: Format check (CI gate)**

Run: `mvn spotless:check -q`
Expected: BUILD SUCCESS. If fails: `mvn spotless:apply -q` then re-stage.

- [ ] **Step 3: Stage and commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidator.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/rag/config/RagVectorTypeValidatorTest.java \
        bootstrap/src/main/resources/application.yaml \
        docs/dev/verification/permission-pr3-leak-free.sh \
        docs/dev/design/2026-04-26-permission-roadmap.md
git commit -m "$(cat <<'EOF'
feat(infra): RagVectorTypeValidator fail-fast + PR3 verification closure (PR3 c5)

PR3-6 不变量 + 阶段 A 成功标准收尾:

- RagVectorTypeValidator 升级:非 opensearch 启动 fail-fast;
  rag.vector.allow-incomplete-backend=true 是 dev override
- 3 个单测覆盖 opensearch 静默 / milvus 抛 / milvus+override 通过
- application.yaml 加 allow-incomplete-backend 注释行
- permission-pr3-leak-free.sh grep 守门(PR3-2/3/4/5 四条规则)
- roadmap §1 + §3 标 PR3 已完成,下一步指向 PR4

阶段 A (PR1+PR2+PR3) 全部完成;PR4 起进入阶段 B 检索链路对齐。

Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR3
Spec: docs/superpowers/specs/2026-04-27-permission-pr3-access-calculator-threadlocal-guards-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit succeeds.

---

## Final Verification (post all 5 commits)

### Task F.1: Run all CI gates

- [ ] **Step 1: Full Maven build (skipTests off)**

Run: `mvn clean install -q -DskipTests=false`
Expected: BUILD SUCCESS — preexisting baseline failures ignored per root CLAUDE.md.

- [ ] **Step 2: Spotless format**

Run: `mvn spotless:check -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: ArchUnit rules**

Run: `mvn -pl bootstrap test -Dtest=PermissionBoundaryArchTest,KbAccessServiceRetirementArchTest -q`
Expected: 0 violations.

- [ ] **Step 4: PR3 grep 守门**

Run: `./docs/dev/verification/permission-pr3-leak-free.sh`
Expected: `✓ PR3 4 条 grep 守门全通过`.

- [ ] **Step 5: Targeted PR3 tests**

Run: `mvn -pl bootstrap test -Dtest='KbAccessCalculatorTest,KbAccessSubjectFactoryImplTest,AccessServiceImplTest,StreamCallbackFactoryUserIdTest,RagVectorTypeValidatorTest' -q`
Expected: all pass.

### Task F.2: Manual smoke (per spec §3.4)

Spec lists 5 manual smoke paths. The PR3 author should run at least #1, #4, #5 locally before merging:

1. SUPER_ADMIN 在管理后台访问"用户管理 → 查看 FICC_USER 授权" → OPS_KB 显示 securityLevel=1 (target ceiling), not 3.
2. OPS_ADMIN 在管理后台查看 FICC_USER 授权 → FICC_KB 上 implicit=false, securityLevel=target's RBAC.
3. FICC_USER 登录后 SSE 问答 → onComplete 后 t_message.user_id 正确.
4. 启动应用 with `rag.vector.type=milvus`, no override → 启动失败, log 含 IllegalStateException + "milvus" + "allow-incomplete-backend".
5. 启动应用 with `rag.vector.type=opensearch` (default) → 启动成功, no WARN.

If any smoke path fails: investigate before merging.

### Task F.3: Update PR description / dev_log

- [ ] **Step 1: Append entry to `log/dev_log/dev_log.md`**

Add a row under `2026-04-27` (or create the date heading) summarizing PR3 with link to spec + plan + key invariants.

---

## Rollback

If PR3 needs reversion post-merge: `git revert <merge-commit-sha>` (the merge commit). Intermediate PR3 commits **do not** form usable rollback points — c2 is a single big-bang and partial revert breaks compile.

---

## Self-Review (executed by writer; included for traceability)

**Spec coverage check**:
- §0 Problem Statement (leak证据) → Task 2.6 fixes leak; Tasks 2.8 T1/T2 contract tests assert it.
- §1.2 6 invariants → PR3-1 ArchUnit (Task 4.7), PR3-2 ArchUnit (Task 4.7), PR3-3 grep (Task 5.4), PR3-4 ArchUnit (Task 4.7), PR3-5 physical delete (Task 3.2), PR3-6 fail-fast (Task 5.2 + 5.1).
- §2.1 New files → all 6 in Tasks 1.1-1.6.
- §2.2 Port + impl + 4 callers + tests → Tasks 2.1-2.11.
- §2.3 Delete getMaxSecurityLevelForKb → Tasks 3.1-3.3.
- §2.4 Handler + factory + ArchUnit → Tasks 4.1-4.8.
- §2.5 RagVectorTypeValidator upgrade → Tasks 5.1-5.4.
- §3 Test contracts T1/T2 → Tasks 2.8 Steps 3+4. T3 calculator pure-function → covered by `KbAccessCalculatorTest` Task 1.5 (subject-only inputs imply ThreadLocal-independence). T4 handler injected userId → Task 4.6. T5 onComplete order → covered by existing `StreamChatEventHandlerCitationTest` (preserved); no PR3 change needed. T6 vector validator → Task 5.1.
- §4.1 Commit sequence → 5 commits as specified.

**Placeholder scan**: No "TBD" / "TODO" / "implement later" / "similar to Task N" patterns. Each step shows full code.

**Type consistency**: `KbAccessSubject` fields (userId / deptId / roleTypes / maxSecurityLevel) referenced consistently across Tasks 1.1, 1.4, 1.6, 2.6, 2.8. Method names `currentOrThrow` / `forTargetUser` / `computeAccessibleKbIds` / `computeMaxSecurityLevels` consistent across plan.
