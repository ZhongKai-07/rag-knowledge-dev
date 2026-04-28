# Permission PR1 — Controller Thinning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move 14 inline `kbAccessService.check*` calls from controllers into 20 service entries, add explicit `system=true` actor for MQ/Schedule, and replace silent-bypass-on-no-user with explicit throw — without changing any permission decision rule or HTTP semantics.

**Architecture:** Six independent commits, each compilable and test-green:
1. framework — add `LoginUser.system` + `UserContext.isSystem()`
2. MQ/Schedule — adopt `system=true` (zero behavioral effect; pre-stages commit 3)
3. KbAccessService — replace silent-bypass with explicit guard (system early-return OR throw `ClientException("missing user context")`)
4. Controllers shed 14 inline checks; 20 service entries gain checks; 4 parameterized boundary tests
5. Verification closure — fail-closed contract test, `rg` script, smoke doc
6. (optional) javadoc on internal helpers + `KnowledgeBaseServiceImpl.update`

**Tech Stack:** Java 17 · Spring Boot 3.5 · MyBatis Plus · Mockito · JUnit 5 · Sa-Token · Lombok

**Spec:** `docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md`

---

## File Structure

### Files to create

| Path | Responsibility |
|---|---|
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceSystemActorTest.java` | T2a + T3 contract tests (system bypass + missing-user throw) |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumerSystemActorTest.java` | T2b — MQ consumer sets `system=true` + clears after |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseServiceAuthBoundaryTest.java` | Parameterized boundary test for KB service (3 entries) |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentServiceAuthBoundaryTest.java` | Parameterized boundary test for Document service (9 entries) |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeChunkServiceAuthBoundaryTest.java` | Parameterized boundary test for Chunk service (6 entries) |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/RoleServiceAuthBoundaryTest.java` | Parameterized boundary test for RoleBinding (2 entries) |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/PageQueryFailClosedTest.java` | T1 — empty `accessibleKbIds` returns empty page |
| `docs/dev/verification/permission-pr1-controllers-clean.sh` | Reusable `rg` script that asserts no controller still calls `kbAccessService.check*` |

### Files to modify

| Path | What changes |
|---|---|
| `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java` | Add `boolean system` field |
| `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/UserContext.java` | Add `static boolean isSystem()` |
| `framework/src/test/java/com/nageoffer/ai/ragent/framework/context/LoginUserTests.java` | Cover new `system` field default + builder |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java:52` | `LoginUser.builder()...system(true).build()` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessor.java:211` | Same pattern |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java` | Add `bypassIfSystemOrAssertActor()` helper; replace existing silent-bypass blocks in `checkAccess`, `checkManageAccess`, `checkDocManageAccess`; add guard to `checkKbRoleBindingAccess` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java` | Remove 5 inline `kbAccessService.check*` calls (lines 76, 86, 96, 147, 154) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java` | Remove 9 inline `kbAccessService.check*` calls (lines 72, 81, 91, 103, 114, 125, 148, 162, 178) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java` | Add check at `rename`, `delete`, `queryById` |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java` | Add check at 9 user entries |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java` | Inject `KbMetadataReader` + `KbAccessService`; add check at 6 user entries |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeChunkService.java` | Add javadoc to internal helpers (`batchCreate` / `deleteByDocId` / `updateEnabledByDocId` / `listByDocId`) |
| `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java` | Add check at `getKbRoleBindings`, `setKbRoleBindings` |
| `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceImplTest.java` | Update existing `silent-bypass`-style tests if any (commit 3 may break them; replace with explicit fixture setup using `system(true)` or remove obsolete cases) |

### Files explicitly NOT modified

Per spec §2.6: `framework/security/port/*`, `KbAccessServiceImpl` decision logic (lines 85-194 + 327-380), `AccessServiceImpl`, `KnowledgeBaseController.applyKbScope`, `SpacesController`, `KnowledgeDocumentController.search`'s scope compute, all frontend, OpenSearch / `role_kb_relation` / DB schema.

---

## Important Pre-Flight Notes (read before starting)

### Current shape of the 5 check methods (verified against `KbAccessServiceImpl.java`)

| Method | Current top guard | Line | Action in commit 3 |
|---|---|---|---|
| `checkAccess(kbId)` | `if (!UserContext.hasUser() \|\| UserContext.getUserId() == null) return;` | 216-218 | **Replace** with `bypassIfSystemOrAssertActor()` |
| `checkManageAccess(kbId)` | (same silent-bypass) | 242-244 | **Replace** |
| `checkDocManageAccess(docId)` | (same silent-bypass) | 603-605 | **Replace** |
| `checkDocSecurityLevelAccess(docId, lvl)` | (none — delegates to `checkDocManageAccess`) | 617-619 | **No direct change** (transitive via delegate) |
| `checkKbRoleBindingAccess(kbId)` | (none — currently has no system handling) | 666-681 | **Add** `bypassIfSystemOrAssertActor()` at first line |
| `checkReadAccess(kbId)` | (none — delegates to `checkAccess`) | 142-144 | **No direct change** (transitive via delegate) |

The semantic change in commit 3 is: from silent-bypass-on-no-user → explicit-system-or-throw. This is why commit 2 (MQ/Schedule adopting `system=true`) **must land first**.

### Chunk controller already passes `docId` per call

Verified against `KnowledgeChunkController.java`: every chunk endpoint passes `docId` as a path variable to the service method. So chunk service signatures already have `docId` available — no need to fetch a chunk to recover its `docId`. `batchToggleEnabled(docId, request, enabled)` is single-doc per call by interface; the spec's "deduplicate docIds" caveat is a defensive note for future signature evolution, not a current concern.

### Chunk pageQuery uses `checkAccess(kbId)` — needs docId→kbId resolution

`KbAccessService` exposes `checkDocManageAccess(docId)` (which internally resolves docId→kbId) but does **not** expose `checkDocReadAccess(docId)`. To avoid expanding the API surface, chunk service does the resolution inline:

```java
String kbId = kbMetadataReader.getKbIdOfDoc(docId);
if (kbId == null) {
    throw new ClientException("文档不存在: " + docId);
}
kbAccessService.checkAccess(kbId);
```

This requires injecting `KbMetadataReader` into `KnowledgeChunkServiceImpl` — note the constructor change in commit 4.

---

## Commit 1 — framework: `LoginUser.system` + `UserContext.isSystem()`

### Task 1.1: Add failing test for `LoginUser.system` default + builder

**Files:**
- Modify: `framework/src/test/java/com/nageoffer/ai/ragent/framework/context/LoginUserTests.java`

- [ ] **Step 1: Append two test methods**

```java
@Test
void system_field_defaults_to_false() {
    LoginUser user = LoginUser.builder()
            .userId("3")
            .username("normal")
            .build();
    assertFalse(user.isSystem());
}

@Test
void system_actor_built_explicitly() {
    LoginUser sys = LoginUser.builder()
            .username("op-name")
            .system(true)
            .build();
    assertTrue(sys.isSystem());
    assertEquals("op-name", sys.getUsername());
}
```

Add the imports at the top if missing:
```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl framework test -Dtest=LoginUserTests`
Expected: FAIL with `cannot find symbol: method isSystem()` or `cannot find symbol: method system(boolean)`

### Task 1.2: Implement `LoginUser.system` field

**Files:**
- Modify: `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java`

- [ ] **Step 1: Add the field after `maxSecurityLevel`**

Locate the `private int maxSecurityLevel;` declaration (currently the last field). Append below it:

```java
    /**
     * 系统态执行者标记。仅 MQ 消费者 / 定时任务 / 框架级回调入口允许显式置为 true，
     * 在 KbAccessService.check* 层短路放行。
     * <p>HTTP 入口忘记 attach user 不会落到此路径——guard 通过 isSystem()=false
     * + getUserId()=null 抛 ClientException。
     */
    @lombok.Builder.Default
    private boolean system = false;
```

Also add the import at top if not already present:
```java
import lombok.Builder;
```
(already present — check the existing `import lombok.Builder;` is there; no action if so)

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -pl framework test -Dtest=LoginUserTests`
Expected: PASS — all four tests green.

### Task 1.3: Add failing test for `UserContext.isSystem()`

**Files:**
- Create: `framework/src/test/java/com/nageoffer/ai/ragent/framework/context/UserContextTests.java`

- [ ] **Step 1: Create the test file**

```java
/*
 * Licensed under Apache 2.0 (see LoginUserTests for full header).
 */
package com.knowledgebase.ai.ragent.framework.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContextTests {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void isSystem_returns_false_when_no_user_context_set() {
        assertFalse(UserContext.isSystem());
    }

    @Test
    void isSystem_returns_false_for_normal_user() {
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
        assertFalse(UserContext.isSystem());
    }

    @Test
    void isSystem_returns_true_for_explicit_system_actor() {
        UserContext.set(LoginUser.builder().username("mq-op").system(true).build());
        assertTrue(UserContext.isSystem());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl framework test -Dtest=UserContextTests`
Expected: FAIL with `cannot find symbol: method isSystem()` on `UserContext`.

### Task 1.4: Implement `UserContext.isSystem()`

**Files:**
- Modify: `framework/src/main/java/com/nageoffer/ai/ragent/framework/context/UserContext.java`

- [ ] **Step 1: Add the method just below `hasUser()`**

After the existing `hasUser()` method (currently the last method), append:

```java
    /**
     * 判断当前线程是否为显式系统态执行者（MQ 消费者 / 定时任务 / 框架级回调）。
     * <p>仅在 LoginUser.system == true 时返回 true。
     * <p>缺失 UserContext 或普通登录用户均返回 false。
     */
    public static boolean isSystem() {
        LoginUser user = CONTEXT.get();
        return user != null && user.isSystem();
    }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -pl framework test -Dtest=UserContextTests`
Expected: PASS — all three new tests green.

### Task 1.5: Run all framework tests + commit

- [ ] **Step 1: Verify framework module is fully green**

Run: `mvn -pl framework test`
Expected: BUILD SUCCESS, all framework tests pass.

- [ ] **Step 2: Stage and commit**

```bash
git add framework/src/main/java/com/nageoffer/ai/ragent/framework/context/LoginUser.java \
        framework/src/main/java/com/nageoffer/ai/ragent/framework/context/UserContext.java \
        framework/src/test/java/com/nageoffer/ai/ragent/framework/context/LoginUserTests.java \
        framework/src/test/java/com/nageoffer/ai/ragent/framework/context/UserContextTests.java
git commit -m "$(cat <<'EOF'
feat(framework): add explicit system actor (LoginUser.system + UserContext.isSystem)

Pre-stages permission PR1 commit 3. system=false by default; MQ/Schedule
will explicitly set system(true) in commit 2. Behavior unchanged at this
commit: KbAccessService does not yet consume the flag.

Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md
EOF
)"
```

---

## Commit 2 — MQ/Schedule entries adopt `system=true`

### Task 2.1: Add failing test for chunk MQ consumer system actor

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumerSystemActorTest.java`

- [ ] **Step 1: Create the test file**

```java
/*
 * Licensed under Apache 2.0.
 */
package com.knowledgebase.ai.ragent.knowledge.mq;

import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.mq.MessageWrapper;
import com.knowledgebase.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.knowledgebase.ai.ragent.knowledge.service.KnowledgeDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class KnowledgeDocumentChunkConsumerSystemActorTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void onMessage_sets_system_actor_during_invocation_and_clears_after() {
        KnowledgeDocumentService documentService = mock(KnowledgeDocumentService.class);
        AtomicBoolean wasSystem = new AtomicBoolean(false);

        doAnswer((InvocationOnMock invocation) -> {
            wasSystem.set(UserContext.isSystem());
            return null;
        }).when(documentService).executeChunk("doc-001");

        KnowledgeDocumentChunkEvent event = new KnowledgeDocumentChunkEvent();
        event.setDocId("doc-001");
        event.setOperator("op-x");
        MessageWrapper<KnowledgeDocumentChunkEvent> wrapper = MessageWrapper.of(event, "k-1");

        KnowledgeDocumentChunkConsumer consumer = new KnowledgeDocumentChunkConsumer(documentService);
        consumer.onMessage(wrapper);

        assertTrue(wasSystem.get(), "UserContext.isSystem() must be true inside service call");
        assertFalse(UserContext.hasUser(), "UserContext must be cleared after onMessage returns");
        verify(documentService).executeChunk("doc-001");
    }
}
```

If `MessageWrapper.of(...)` does not exist with that signature, replace with whatever factory the existing codebase uses (check by `grep -rn "MessageWrapper" framework/`). The test logic above stays identical — only the wrapper instantiation changes.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeDocumentChunkConsumerSystemActorTest`
Expected: FAIL on `assertTrue(wasSystem.get(), ...)` — current consumer builds `LoginUser` without `system(true)`.

### Task 2.2: Implement `system=true` in chunk consumer

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java:52`

- [ ] **Step 1: Add `.system(true)` to the builder**

Change line 52 from:
```java
        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
```
to:
```java
        UserContext.set(LoginUser.builder().username(event.getOperator()).system(true).build());
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeDocumentChunkConsumerSystemActorTest`
Expected: PASS.

### Task 2.3: Implement `system=true` in schedule processor

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessor.java:211`

- [ ] **Step 1: Add `.system(true)` to the builder**

Change line 211 from:
```java
                UserContext.set(LoginUser.builder().username(SYSTEM_USER).build());
```
to:
```java
                UserContext.set(LoginUser.builder().username(SYSTEM_USER).system(true).build());
```

- [ ] **Step 2: Compile to confirm**

Run: `mvn -pl bootstrap compile`
Expected: BUILD SUCCESS.

(`ScheduleRefreshProcessor` does not have a unit-level test class for system-actor verification at this stage; it is enumerated as a manual / code-review checkpoint per spec §3.1 T2b.)

### Task 2.4: Run consumer tests + commit

- [ ] **Step 1: Run targeted bootstrap tests**

Run: `mvn -pl bootstrap test -Dtest='*ChunkConsumer*'`
Expected: all green (existing chunk-consumer tests + new system-actor test).

- [ ] **Step 2: Stage and commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumer.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/schedule/ScheduleRefreshProcessor.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/mq/KnowledgeDocumentChunkConsumerSystemActorTest.java
git commit -m "$(cat <<'EOF'
feat(knowledge): MQ consumer + schedule processor declare system actor

Both non-HTTP entries that drive the chunk pipeline now construct
LoginUser.builder()...system(true).build() before UserContext.set.
KbAccessService still ignores the flag in this commit; commit 3 will
consume it. T2b unit test asserts isSystem() inside service call and
hasUser()==false after onMessage returns.

Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md (commit 2)
EOF
)"
```

---

## Commit 3 — KbAccessService: `bypassIfSystemOrAssertActor()` guard

### Task 3.1: Add T2a + T3 contract tests (failing)

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceSystemActorTest.java`

- [ ] **Step 1: Create the test file**

```java
/*
 * Licensed under Apache 2.0.
 */
package com.knowledgebase.ai.ragent.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.user.dao.entity.RoleKbRelationDO;
import com.knowledgebase.ai.ragent.user.dao.entity.UserRoleDO;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleKbRelationMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.RoleMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.SysDeptMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserMapper;
import com.knowledgebase.ai.ragent.user.dao.mapper.UserRoleMapper;
import com.knowledgebase.ai.ragent.user.service.impl.KbAccessServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class KbAccessServiceSystemActorTest {

    private KbMetadataReader kbMetadataReader;
    private UserRoleMapper userRoleMapper;
    private RoleKbRelationMapper roleKbRelationMapper;
    private KbAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        initTableInfo(RoleKbRelationDO.class);
        initTableInfo(UserRoleDO.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleKbRelationMapper = mock(RoleKbRelationMapper.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        UserMapper userMapper = mock(UserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        SysDeptMapper sysDeptMapper = mock(SysDeptMapper.class);
        kbMetadataReader = mock(KbMetadataReader.class);
        service = new KbAccessServiceImpl(
                userRoleMapper, roleKbRelationMapper, redissonClient,
                userMapper, roleMapper, sysDeptMapper, kbMetadataReader);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    // ===== T2a — system bypass returns early =====

    @Test
    void checkAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkAccess("kb-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkManageAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkManageAccess("kb-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkDocManageAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkDocManageAccess("doc-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkDocSecurityLevelAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkDocSecurityLevelAccess("doc-x", 2));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    @Test
    void checkKbRoleBindingAccess_system_actor_returns_early_without_db_lookup() {
        UserContext.set(LoginUser.builder().username("op").system(true).build());
        assertDoesNotThrow(() -> service.checkKbRoleBindingAccess("kb-x"));
        verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper);
    }

    // ===== T3 — missing user context throws =====

    @Test
    void checkAccess_throws_when_no_user_context() {
        UserContext.clear();
        ClientException ex = assertThrows(ClientException.class,
                () -> service.checkAccess("kb-x"));
        assertContainsMissingUserContext(ex);
    }

    @Test
    void checkManageAccess_throws_when_no_user_context() {
        UserContext.clear();
        assertThrows(ClientException.class, () -> service.checkManageAccess("kb-x"));
    }

    @Test
    void checkDocManageAccess_throws_when_no_user_context() {
        UserContext.clear();
        assertThrows(ClientException.class, () -> service.checkDocManageAccess("doc-x"));
    }

    @Test
    void checkDocSecurityLevelAccess_throws_when_no_user_context() {
        UserContext.clear();
        assertThrows(ClientException.class,
                () -> service.checkDocSecurityLevelAccess("doc-x", 1));
    }

    @Test
    void checkKbRoleBindingAccess_throws_when_no_user_context() {
        UserContext.clear();
        assertThrows(ClientException.class, () -> service.checkKbRoleBindingAccess("kb-x"));
    }

    private static void assertContainsMissingUserContext(ClientException ex) {
        if (ex.getMessage() == null || !ex.getMessage().contains("missing user context")) {
            throw new AssertionError(
                    "Expected exception message to contain 'missing user context' but was: "
                            + ex.getMessage());
        }
    }

    private static void initTableInfo(Class<?> clazz) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), clazz);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bootstrap test -Dtest=KbAccessServiceSystemActorTest`
Expected:
- T3 cases (no-context) **fail** because current methods silently return on missing user.
- T2a cases may **pass** (silent-bypass currently catches no-user, including system) but the `verifyNoInteractions` will hold since no DB is touched. The relevant assertion that fails is **T3**.

The test set is failing as a whole by at least one method — that's the red signal.

### Task 3.2: Implement guard helper + apply to 4 methods

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1: Add the private helper near the bottom of the class (above the closing `}`)**

```java
    /**
     * 显式区分系统态与缺失登录态。
     * <ul>
     *   <li>UserContext.isSystem() == true → 返回 true，调用方 early return（MQ/定时任务）</li>
     *   <li>无 UserContext / userId 为空 → 抛 ClientException("missing user context")</li>
     *   <li>正常用户 → 返回 false，调用方继续走原决策路径</li>
     * </ul>
     * 系统态以 LoginUser.system == true 为唯一信号；缺失上下文不退化为放行。
     */
    private boolean bypassIfSystemOrAssertActor() {
        if (UserContext.isSystem()) {
            return true;
        }
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            throw new ClientException("missing user context");
        }
        return false;
    }
```

- [ ] **Step 2: Replace the silent-bypass block in `checkAccess` (line 214-218)**

Change:
```java
    @Override
    public void checkAccess(String kbId) {
        // 系统态（MQ 消费者、定时任务）—— 没有登录态，直接放行
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            return;
        }
        if (isSuperAdmin()) {
```
to:
```java
    @Override
    public void checkAccess(String kbId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
```

- [ ] **Step 3: Replace the silent-bypass block in `checkManageAccess` (line 240-244)**

Change:
```java
    @Override
    public void checkManageAccess(String kbId) {
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            return;
        }
        if (isSuperAdmin()) {
```
to:
```java
    @Override
    public void checkManageAccess(String kbId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
```

- [ ] **Step 4: Replace the silent-bypass block in `checkDocManageAccess` (line 601-605)**

Change:
```java
    @Override
    public void checkDocManageAccess(String docId) {
        if (!UserContext.hasUser() || UserContext.getUserId() == null) {
            return; // 系统态
        }
        if (isSuperAdmin()) {
```
to:
```java
    @Override
    public void checkDocManageAccess(String docId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
```

- [ ] **Step 5: Add guard to `checkKbRoleBindingAccess` (line 666)**

Change:
```java
    @Override
    public void checkKbRoleBindingAccess(String kbId) {
        if (isSuperAdmin()) {
            return;
        }
        if (!isDeptAdmin()) {
            throw new ClientException("需要管理员权限");
        }
```
to:
```java
    @Override
    public void checkKbRoleBindingAccess(String kbId) {
        if (bypassIfSystemOrAssertActor()) {
            return;
        }
        if (isSuperAdmin()) {
            return;
        }
        if (!isDeptAdmin()) {
            throw new ClientException("需要管理员权限");
        }
```

`checkDocSecurityLevelAccess` and `checkReadAccess` are delegators — no direct change.

- [ ] **Step 6: Run new contract tests**

Run: `mvn -pl bootstrap test -Dtest=KbAccessServiceSystemActorTest`
Expected: PASS — all 10 tests green.

### Task 3.3: Reconcile existing `KbAccessServiceImplTest` against new semantics

**Files:**
- Modify: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceImplTest.java`

The existing test class likely contains cases that expected silent-bypass-on-no-user (e.g. an `assertDoesNotThrow` after `UserContext.clear()`). Those are now expected to throw.

- [ ] **Step 1: Run existing test and identify failures**

Run: `mvn -pl bootstrap test -Dtest=KbAccessServiceImplTest`
Expected: zero or more failures with `ClientException("missing user context")` thrown unexpectedly.

- [ ] **Step 2: Fix each failure**

For each failing test, choose the appropriate mitigation:
- If the test is asserting the **old** silent-bypass behavior — **delete** the test or **rewrite** to set `system(true)` if the intent was "system-mode behaves correctly".
- If the test omitted `UserContext.set(...)` for an unrelated reason — **add** a normal user setup at the top of that test.

Example pattern for a system-mode rewrite:
```java
// before
UserContext.clear();
service.checkAccess("kb-x");  // expected silent return
// after
UserContext.set(LoginUser.builder().username("op").system(true).build());
service.checkAccess("kb-x");  // explicit system bypass
```

- [ ] **Step 3: Run again to confirm green**

Run: `mvn -pl bootstrap test -Dtest=KbAccessServiceImplTest`
Expected: PASS.

### Task 3.4: Full bootstrap test pass + commit

- [ ] **Step 1: Verify bootstrap module is green (excluding known baseline-red)**

Run: `mvn -pl bootstrap test`
Expected: BUILD SUCCESS or only the 5 known baseline-red tests fail (`MilvusCollectionTests`, `InvoiceIndexDocumentTests`, `PgVectorStoreServiceTest.testChineseCharacterInsertion`, `IntentTreeServiceTests.initFromFactory`, `VectorTreeIntentClassifierTests`).

- [ ] **Step 2: Stage and commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceImplTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/KbAccessServiceSystemActorTest.java
git commit -m "$(cat <<'EOF'
feat(security): replace silent no-user bypass with explicit system/throw guard

KbAccessService.check{Access,ManageAccess,DocManageAccess,
DocSecurityLevelAccess (transitive), KbRoleBindingAccess} now route
through bypassIfSystemOrAssertActor():
  - UserContext.isSystem() == true → early return (MQ/Schedule path)
  - missing UserContext or null userId → ClientException("missing user context")
  - normal user → continue existing decision logic

This intentionally turns the prior "no UserContext = silent bypass"
default into an explicit failure, eliminating fail-open paths from
forgotten attach-user filters. MQ/Schedule already adopted system=true
in commit 2, so non-HTTP paths remain green.

T2a (system bypass + verifyNoInteractions on all 5 methods) and T3
(missing-context throw on all 5 methods) added in
KbAccessServiceSystemActorTest.

Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md (commit 3)
EOF
)"
```

---

## Commit 4 — Controller thinning + 20 service entries + boundary tests

This is the largest commit. The order within: tests first (red), then code changes (green), then commit.

### Task 4.1: Build the parameterized boundary test infrastructure

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseServiceAuthBoundaryTest.java`

- [ ] **Step 1: Create the KB service boundary test (3 entries)**

```java
/*
 * Licensed under Apache 2.0.
 */
package com.knowledgebase.ai.ragent.knowledge.service;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import com.knowledgebase.ai.ragent.user.service.KbAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KnowledgeBaseServiceAuthBoundaryTest {

    private KbAccessService kbAccessService;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        kbAccessService = mock(KbAccessService.class);
        // KnowledgeBaseServiceImpl has many collaborators. Use any test-helper or
        // construct with mocks. For this boundary test we only need the call
        // surface — mock all collaborators with Mockito.mock(...).
        service = TestKnowledgeBaseServiceFactory.withMockedAccess(kbAccessService);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void rename_propagates_check_failure() {
        doThrow(new ClientException("denied"))
                .when(kbAccessService).checkManageAccess("kb-1");
        assertThrows(ClientException.class,
                () -> service.rename("kb-1", new KnowledgeBaseUpdateRequest()));
        verify(kbAccessService).checkManageAccess("kb-1");
    }

    @Test
    void delete_propagates_check_failure() {
        doThrow(new ClientException("denied"))
                .when(kbAccessService).checkManageAccess("kb-1");
        assertThrows(ClientException.class, () -> service.delete("kb-1"));
        verify(kbAccessService).checkManageAccess("kb-1");
    }

    @Test
    void queryById_propagates_check_failure() {
        doThrow(new ClientException("denied"))
                .when(kbAccessService).checkAccess("kb-1");
        assertThrows(ClientException.class, () -> service.queryById("kb-1"));
        verify(kbAccessService).checkAccess("kb-1");
    }
}
```

If `KnowledgeBaseServiceImpl`'s constructor takes many collaborators (likely), introduce a small helper class `TestKnowledgeBaseServiceFactory` in the same package that constructs with all-mocked collaborators except the injected `KbAccessService`. Inline the helper if a single test class — keep helper minimal.

Example helper inline at the bottom of this test file:
```java
final class TestKnowledgeBaseServiceFactory {
    static KnowledgeBaseService withMockedAccess(KbAccessService kbAccess) {
        // Inspect KnowledgeBaseServiceImpl's @RequiredArgsConstructor field order
        // and mock each collaborator. Replace the kbAccessService argument with
        // the supplied mock.
        return new KnowledgeBaseServiceImpl(
                /* knowledgeBaseMapper       */ mock(...),
                /* knowledgeDocumentMapper   */ mock(...),
                /* sysDeptMapper             */ mock(...),
                /* kbAccessService           */ kbAccess,
                /* ...other collaborators... */ ...
        );
    }
}
```
**Action**: open `KnowledgeBaseServiceImpl.java` and read the field declarations; mirror them in the helper, mocking everything except `kbAccessService`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeBaseServiceAuthBoundaryTest`
Expected: FAIL on `verify(kbAccessService).checkXxx(...)` — service does not yet call the access check.

### Task 4.2: Add check at 3 KB service entries

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`

- [ ] **Step 1: Inject `KbAccessService` (if not already a field)**

Verify `KnowledgeBaseServiceImpl` already has `private final KbAccessService kbAccessService;` (it almost certainly does, since other call sites already use it). If not, add it to the `@RequiredArgsConstructor`-managed final-fields block.

- [ ] **Step 2: Add check to `rename(kbId, request)`**

At the first line of the method body:
```java
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        kbAccessService.checkManageAccess(kbId);
        // ...existing body
```

- [ ] **Step 3: Add check to `delete(kbId)`**

```java
    public void delete(String kbId) {
        kbAccessService.checkManageAccess(kbId);
        // ...existing body
```

- [ ] **Step 4: Add check to `queryById(kbId)`**

```java
    public KnowledgeBaseVO queryById(String kbId) {
        kbAccessService.checkAccess(kbId);
        // ...existing body
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeBaseServiceAuthBoundaryTest`
Expected: PASS.

### Task 4.3: Repeat tests-then-implementation for KnowledgeDocumentService (9 entries)

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentServiceAuthBoundaryTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java`

- [ ] **Step 1: Create the parameterized boundary test (9 entries)**

Pattern: mirror `KnowledgeBaseServiceAuthBoundaryTest`; one `@Test` per entry.

```java
/*
 * Licensed under Apache 2.0.
 */
package com.knowledgebase.ai.ragent.knowledge.service;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.knowledgebase.ai.ragent.user.service.KbAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class KnowledgeDocumentServiceAuthBoundaryTest {

    private KbAccessService kbAccessService;
    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        kbAccessService = mock(KbAccessService.class);
        service = TestKnowledgeDocumentServiceFactory.withMockedAccess(kbAccessService);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void upload_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkManageAccess("kb-1");
        assertThrows(ClientException.class,
                () -> service.upload("kb-1", new KnowledgeDocumentUploadRequest(), null));
    }

    @Test
    void startChunk_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class, () -> service.startChunk("doc-1"));
    }

    @Test
    void delete_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class, () -> service.delete("doc-1"));
    }

    @Test
    void get_propagates_check_failure_when_doc_present() {
        // Arrange: documentMapper returns a doc with kbId so service can resolve and check
        KnowledgeDocumentDO doc = new KnowledgeDocumentDO();
        doc.setId("doc-1");
        doc.setKbId("kb-1");
        TestKnowledgeDocumentServiceFactory.stubGetDoc(service, doc);
        doThrow(new ClientException("denied")).when(kbAccessService).checkAccess("kb-1");
        assertThrows(ClientException.class, () -> service.get("doc-1"));
    }

    @Test
    void update_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class,
                () -> service.update("doc-1", new KnowledgeDocumentUpdateRequest()));
    }

    @Test
    void page_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkAccess("kb-1");
        assertThrows(ClientException.class,
                () -> service.page("kb-1", new KnowledgeDocumentPageRequest()));
    }

    @Test
    void enable_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class, () -> service.enable("doc-1", true));
    }

    @Test
    void updateSecurityLevel_propagates_check_failure() {
        doThrow(new ClientException("denied"))
                .when(kbAccessService).checkDocSecurityLevelAccess("doc-1", 2);
        assertThrows(ClientException.class, () -> service.updateSecurityLevel("doc-1", 2));
    }

    @Test
    void getChunkLogs_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class,
                () -> service.getChunkLogs("doc-1", null));
    }
}
```

The `TestKnowledgeDocumentServiceFactory` helper constructs `KnowledgeDocumentServiceImpl` with all collaborators mocked except `kbAccessService`. Open `KnowledgeDocumentServiceImpl.java` to read the constructor field list; mirror it in the helper. The `stubGetDoc(...)` helper configures the document-mapper mock to return the supplied DO when `selectById("doc-1")` is called.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeDocumentServiceAuthBoundaryTest`
Expected: FAIL — service has no `kbAccessService.check*` invocations yet.

- [ ] **Step 3: Add check at the 9 service entries**

Open `KnowledgeDocumentServiceImpl.java` and add the first-line check to each method, matching spec §2.4 row by row. Pattern for a representative entry:

```java
    public KnowledgeDocumentVO upload(String kbId,
                                       KnowledgeDocumentUploadRequest requestParam,
                                       MultipartFile file) {
        kbAccessService.checkManageAccess(kbId);
        // ...existing body
```

For `get(docId)`, preserve the existing controller-level "fetch then check" shape:
```java
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentVO vo = /* existing fetch-by-id logic */;
        if (vo != null) {
            kbAccessService.checkAccess(vo.getKbId());
        }
        return vo;
    }
```

For `updateSecurityLevel(docId, newLevel)`, preserve the parameter validation that today lives at `KnowledgeDocumentController.java:159-161`:
```java
    public void updateSecurityLevel(String docId, Integer newLevel) {
        if (newLevel == null || newLevel < 0 || newLevel > 3) {
            throw new ClientException("newLevel 必须在 0-3 之间");
        }
        kbAccessService.checkDocSecurityLevelAccess(docId, newLevel);
        // ...existing body
```
(Or: keep the validation in the controller and only move the check; either is fine — pick whichever keeps the diff minimal. Spec §2.4 lists "param validation (0-3) + checkDocSecurityLevelAccess" as the service-level contract, but the controller can keep the validation as defensive duplication.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeDocumentServiceAuthBoundaryTest`
Expected: PASS — all 9 tests green.

### Task 4.4: Repeat for KnowledgeChunkService (6 entries)

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeChunkServiceAuthBoundaryTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java`

- [ ] **Step 1: Create the chunk service boundary test**

```java
/*
 * Licensed under Apache 2.0.
 */
package com.knowledgebase.ai.ragent.knowledge.service;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.framework.security.port.KbMetadataReader;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.knowledgebase.ai.ragent.user.service.KbAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class KnowledgeChunkServiceAuthBoundaryTest {

    private KbAccessService kbAccessService;
    private KbMetadataReader kbMetadataReader;
    private KnowledgeChunkService service;

    @BeforeEach
    void setUp() {
        kbAccessService = mock(KbAccessService.class);
        kbMetadataReader = mock(KbMetadataReader.class);
        service = TestKnowledgeChunkServiceFactory.withMockedAccess(kbAccessService, kbMetadataReader);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void pageQuery_propagates_check_failure() {
        when(kbMetadataReader.getKbIdOfDoc("doc-1")).thenReturn("kb-1");
        doThrow(new ClientException("denied")).when(kbAccessService).checkAccess("kb-1");
        assertThrows(ClientException.class,
                () -> service.pageQuery("doc-1", new KnowledgeChunkPageRequest()));
    }

    @Test
    void create_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class,
                () -> service.create("doc-1", new KnowledgeChunkCreateRequest()));
    }

    @Test
    void update_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class,
                () -> service.update("doc-1", "chunk-1", new KnowledgeChunkUpdateRequest()));
    }

    @Test
    void delete_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class, () -> service.delete("doc-1", "chunk-1"));
    }

    @Test
    void enableChunk_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class, () -> service.enableChunk("doc-1", "chunk-1", true));
    }

    @Test
    void batchToggleEnabled_propagates_check_failure() {
        doThrow(new ClientException("denied")).when(kbAccessService).checkDocManageAccess("doc-1");
        assertThrows(ClientException.class,
                () -> service.batchToggleEnabled("doc-1", new KnowledgeChunkBatchRequest(), true));
    }
}
```

The `TestKnowledgeChunkServiceFactory.withMockedAccess(kbAccessService, kbMetadataReader)` constructs `KnowledgeChunkServiceImpl` with all collaborators mocked except those two. Mirror the constructor signature.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeChunkServiceAuthBoundaryTest`
Expected: FAIL on every test — chunk service has no checks yet.

- [ ] **Step 3: Inject `KbMetadataReader` + `KbAccessService` into chunk service**

Open `KnowledgeChunkServiceImpl.java`. Add to the `@RequiredArgsConstructor`-managed fields:
```java
    private final KbAccessService kbAccessService;
    private final KbMetadataReader kbMetadataReader;
```

(plus the corresponding imports).

- [ ] **Step 4: Add check at 6 user entries**

Pattern for each:

```java
    public IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam) {
        String kbId = kbMetadataReader.getKbIdOfDoc(docId);
        if (kbId == null) {
            throw new ClientException("文档不存在: " + docId);
        }
        kbAccessService.checkAccess(kbId);
        // ...existing body
```

For `create / update / delete / enableChunk`:
```java
    public KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest request) {
        kbAccessService.checkDocManageAccess(docId);
        // ...existing body
```

For `batchToggleEnabled(docId, request, enabled)` — single docId per call by interface (verified):
```java
    public void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest request, boolean enabled) {
        kbAccessService.checkDocManageAccess(docId);
        // ...existing body
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -pl bootstrap test -Dtest=KnowledgeChunkServiceAuthBoundaryTest`
Expected: PASS.

### Task 4.5: Repeat for RoleService role-binding entries (2 entries)

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/RoleServiceAuthBoundaryTest.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java`

- [ ] **Step 1: Create the test**

```java
/*
 * Licensed under Apache 2.0.
 */
package com.knowledgebase.ai.ragent.user.service;

import com.knowledgebase.ai.ragent.framework.context.LoginUser;
import com.knowledgebase.ai.ragent.framework.context.UserContext;
import com.knowledgebase.ai.ragent.framework.exception.ClientException;
import com.knowledgebase.ai.ragent.knowledge.controller.KnowledgeBaseController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RoleServiceAuthBoundaryTest {

    private KbAccessService kbAccessService;
    private RoleService service;

    @BeforeEach
    void setUp() {
        kbAccessService = mock(KbAccessService.class);
        service = TestRoleServiceFactory.withMockedAccess(kbAccessService);
        UserContext.set(LoginUser.builder().userId("u-1").username("alice").build());
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void getKbRoleBindings_propagates_check_failure() {
        doThrow(new ClientException("denied"))
                .when(kbAccessService).checkKbRoleBindingAccess("kb-1");
        assertThrows(ClientException.class, () -> service.getKbRoleBindings("kb-1"));
    }

    @Test
    void setKbRoleBindings_propagates_check_failure() {
        doThrow(new ClientException("denied"))
                .when(kbAccessService).checkKbRoleBindingAccess("kb-1");
        assertThrows(ClientException.class,
                () -> service.setKbRoleBindings("kb-1", List.of()));
    }
}
```

`TestRoleServiceFactory.withMockedAccess(kbAccessService)` mirrors `RoleServiceImpl`'s constructor; mock everything except `kbAccessService`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl bootstrap test -Dtest=RoleServiceAuthBoundaryTest`
Expected: FAIL.

- [ ] **Step 3: Add check at the 2 role-binding entries**

Open `RoleServiceImpl.java`. Inject `KbAccessService` if not already present:
```java
    private final KbAccessService kbAccessService;
```

Add first-line check to `getKbRoleBindings(kbId)` at line 407:
```java
    public List<KnowledgeBaseController.KbRoleBindingVO> getKbRoleBindings(String kbId) {
        kbAccessService.checkKbRoleBindingAccess(kbId);
        // ...existing body
```

Add first-line check to `setKbRoleBindings(kbId, bindings)` at line 447:
```java
    public void setKbRoleBindings(String kbId,
                                   List<KnowledgeBaseController.KbRoleBindingRequest> bindings) {
        kbAccessService.checkKbRoleBindingAccess(kbId);
        // ...existing body
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl bootstrap test -Dtest=RoleServiceAuthBoundaryTest`
Expected: PASS.

### Task 4.6: Remove inline checks from controllers (14 sites)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java`

- [ ] **Step 1: Remove 5 inline checks from `KnowledgeBaseController.java`**

Delete the lines listed in spec §2.3:
- Line 76: `kbAccessService.checkManageAccess(kbId);`
- Line 86: `kbAccessService.checkManageAccess(kbId);`
- Line 96: `kbAccessService.checkAccess(kbId);`
- Line 147: `kbAccessService.checkKbRoleBindingAccess(kbId);`
- Line 154: `kbAccessService.checkKbRoleBindingAccess(kbId);`

Leave `applyKbScope(...)` and its supporting `kbAccessService.isSuperAdmin() / isDeptAdmin() / getAccessibleKbIds(...)` calls alone — those are PR2 territory.

- [ ] **Step 2: Remove 9 inline checks from `KnowledgeDocumentController.java`**

Delete:
- Line 72: `kbAccessService.checkManageAccess(kbId);`
- Line 81: `kbAccessService.checkDocManageAccess(docId);`
- Line 91: `kbAccessService.checkDocManageAccess(docId);`
- Line 103: `kbAccessService.checkAccess(doc.getKbId());` (and the `if (doc != null)` wrapper if it now becomes a no-op — but the doc-fetch must remain because it's the response payload)
- Line 114: `kbAccessService.checkDocManageAccess(docId);`
- Line 125: `kbAccessService.checkAccess(kbId);`
- Line 148: `kbAccessService.checkDocManageAccess(docId);`
- Line 162: `kbAccessService.checkDocSecurityLevelAccess(docId, requestParam.getNewLevel());` (the `newLevel` 0-3 validation is also moved/duplicated — see Task 4.3 step 3)
- Line 178: `kbAccessService.checkDocManageAccess(docId);`

Leave the `getAccessibleKbIds(...)` call in the `search(...)` endpoint — PR2 territory.

- [ ] **Step 3: Compile check**

Run: `mvn -pl bootstrap compile`
Expected: BUILD SUCCESS. (If `kbAccessService` becomes unused in either controller after these removals, leave the field — `applyKbScope(...)` in `KnowledgeBaseController` still uses it; `KnowledgeDocumentController.search(...)` still uses it.)

### Task 4.7: Run full bootstrap test suite + commit

- [ ] **Step 1: Run full bootstrap tests**

Run: `mvn -pl bootstrap test`
Expected: BUILD SUCCESS, only the 5 known baseline-red tests fail (`MilvusCollectionTests`, `InvoiceIndexDocumentTests`, `PgVectorStoreServiceTest.testChineseCharacterInsertion`, `IntentTreeServiceTests.initFromFactory`, `VectorTreeIntentClassifierTests`).

- [ ] **Step 2: Run the rg verification script (manual at this stage)**

Run:
```bash
rg -n "kbAccessService\.(checkAccess|checkManageAccess|checkDocManageAccess|checkDocSecurityLevelAccess|checkKbRoleBindingAccess)\(" \
   bootstrap/src/main/java/com/nageoffer/ai/ragent -g "*Controller.java"
```
Expected: empty output.

- [ ] **Step 3: Stage and commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeDocumentController.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeDocumentServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeChunkServiceImpl.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeBaseServiceAuthBoundaryTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeDocumentServiceAuthBoundaryTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeChunkServiceAuthBoundaryTest.java \
        bootstrap/src/test/java/com/nageoffer/ai/ragent/user/service/RoleServiceAuthBoundaryTest.java
git commit -m "$(cat <<'EOF'
refactor(security): controller→service permission boundary thinning (PR1)

Move 14 inline kbAccessService.check* calls out of controllers and
establish 20 service-method authorization entries:
  KnowledgeBaseService:     rename / delete / queryById                       (3)
  KnowledgeDocumentService: upload / startChunk / delete / get / update /
                            page / enable / updateSecurityLevel / getChunkLogs(9)
  KnowledgeChunkService:    pageQuery / create / update / delete /
                            enableChunk / batchToggleEnabled                  (6)
  RoleService:              getKbRoleBindings / setKbRoleBindings             (2)

KnowledgeChunkService gains KbMetadataReader to resolve docId→kbId for
read access; chunk batchToggleEnabled is single-docId by controller
contract (verified).

KnowledgeChunkController @SaCheckRole("SUPER_ADMIN") preserved → HTTP
semantics unchanged. Service-level boundary is wider (admits DEPT_ADMIN
on own-dept chunks); whether to widen the HTTP gate is a separate
product decision.

applyKbScope / SpacesController / search-scope compute / KnowledgeBase-
ServiceImpl.update remain unchanged — deferred to PR2.

Four parameterized boundary test classes (20 cases) ensure each entry
propagates the upstream ClientException.

Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md (commit 4)
EOF
)"
```

---

## Commit 5 — Verification closure (T1 fail-closed + rg script + smoke doc)

### Task 5.1: T1 fail-closed contract test

**Files:**
- Create: `bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/PageQueryFailClosedTest.java`

- [ ] **Step 1: Write the failing-closed contract test**

```java
/*
 * Licensed under Apache 2.0.
 */
package com.knowledgebase.ai.ragent.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.knowledgebase.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.knowledgebase.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.knowledgebase.ai.ragent.knowledge.service.impl.KnowledgeBaseServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageQueryFailClosedTest {

    @Test
    void pageQuery_returns_empty_page_when_accessibleKbIds_is_empty_non_null() {
        KnowledgeBaseService service = TestKnowledgeBaseServiceFactory.withMockedAccess(
                /* kbAccessService irrelevant for this path */ org.mockito.Mockito.mock(
                        com.knowledgebase.ai.ragent.user.service.KbAccessService.class));

        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        request.setAccessibleKbIds(Set.of());     // empty, non-null = USER with zero RBAC

        IPage<KnowledgeBaseVO> page = service.pageQuery(request);

        assertEquals(0, page.getTotal(), "USER with empty accessibleKbIds must see empty page");
        assertEquals(0, page.getRecords().size(), "records must be empty");
    }

    @Test
    void documentSearch_returns_empty_list_when_accessibleKbIds_is_empty_non_null() {
        KnowledgeDocumentService service = TestKnowledgeDocumentServiceFactory.withMockedAccess(
                org.mockito.Mockito.mock(
                        com.knowledgebase.ai.ragent.user.service.KbAccessService.class));

        java.util.List<com.knowledgebase.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO> result =
                service.search("anything", 8, Set.of());

        assertEquals(0, result.size(),
                "USER with empty accessibleKbIds must see empty search list");
    }
}
```

Both assertions verify the existing `KnowledgeBaseServiceImpl.pageQuery:259-261` and `KnowledgeDocumentServiceImpl.search:659-661` fail-closed early returns continue to work after PR1's changes. Reuse `TestKnowledgeBaseServiceFactory` and `TestKnowledgeDocumentServiceFactory` introduced in commit 4.

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn -pl bootstrap test -Dtest=PageQueryFailClosedTest`
Expected: PASS — both fail-closed paths already exist in code; this test merely locks them in.

### Task 5.2: Add `rg` verification script

**Files:**
- Create: `docs/dev/verification/permission-pr1-controllers-clean.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Permission PR1 verification: assert no controller still calls kbAccessService.check*
# Run from repo root. Exit 1 if any inline check has leaked back into a controller.
#
# Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md §3.3

set -euo pipefail

PATTERN='kbAccessService\.(checkAccess|checkManageAccess|checkDocManageAccess|checkDocSecurityLevelAccess|checkKbRoleBindingAccess)\('
TARGET='bootstrap/src/main/java/com/nageoffer/ai/ragent'

if rg -n "${PATTERN}" "${TARGET}" -g '*Controller.java' --quiet; then
  echo "FAIL: kbAccessService.check* still present in controllers:"
  rg -n "${PATTERN}" "${TARGET}" -g '*Controller.java'
  exit 1
fi

echo "OK: no kbAccessService.check* found in controllers."
```

- [ ] **Step 2: Make executable**

```bash
chmod +x docs/dev/verification/permission-pr1-controllers-clean.sh
```

- [ ] **Step 3: Run it locally**

Run: `bash docs/dev/verification/permission-pr1-controllers-clean.sh`
Expected: `OK: no kbAccessService.check* found in controllers.` and exit 0.

### Task 5.3: Document manual smoke checklist

**Files:**
- Create or modify: `docs/dev/verification/permission-pr1-smoke.md` (create new file)

- [ ] **Step 1: Write the smoke doc**

```markdown
# Permission PR1 — Manual Smoke Checklist

> Run before merging PR1; reproduce post-merge in any subsequent PR2/PR3 changes that
> touch the affected paths.
>
> Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md §3.4

## Setup

Have these accounts ready:
- **OPS_ADMIN** — `role_type=DEPT_ADMIN`, `dept_id=OPS`
- **FICC_USER** — `role_type=USER`, `dept_id=FICC`
- An OPS-owned KB (`kb.dept_id=OPS`)

## Path 1: OPS_ADMIN renames own KB → 200

`PUT /knowledge-base/{ops-kb-id}` with body `{"name": "renamed"}`, OPS_ADMIN session.
Expected: HTTP 200, KB renamed.

## Path 2: FICC_USER renames OPS KB → 403

`PUT /knowledge-base/{ops-kb-id}` with body `{"name": "hijacked"}`, FICC_USER session.
Expected: HTTP 403 (or `ClientException` translated by GlobalExceptionHandler) — service
boundary rejects identically to pre-PR1 behavior.

## Path 3: HTTP upload → MQ → executeChunk completes

Upload a small `.txt` to an OPS KB as OPS_ADMIN. Confirm the document transitions
through PENDING → CHUNKING → SUCCESS in the database without any
`ClientException("missing user context")` or permission error in logs.

This validates the MQ path: chunk consumer sets `system=true`, so KbAccessService
allows the deep-stack call.

## Path 4: Anonymous + missing-context paths

- HTTP `GET /knowledge-base/{kb-id}` with no token → HTTP 401 (Sa-Token).
- Direct service call with `UserContext.clear()` → throws
  `ClientException("missing user context")` (validated in unit tests; this is a
  **code-review confirmation**, not an HTTP path).

## Sign-off

- [ ] Path 1 OK
- [ ] Path 2 OK
- [ ] Path 3 OK
- [ ] Path 4 reviewed in code
```

### Task 5.4: Commit verification artifacts

- [ ] **Step 1: Stage and commit**

```bash
git add bootstrap/src/test/java/com/nageoffer/ai/ragent/knowledge/service/PageQueryFailClosedTest.java \
        docs/dev/verification/permission-pr1-controllers-clean.sh \
        docs/dev/verification/permission-pr1-smoke.md
git commit -m "$(cat <<'EOF'
test(security): PR1 verification closure — T1 fail-closed + rg script + smoke doc

- PageQueryFailClosedTest locks the existing fail-closed contracts at
  KnowledgeBaseServiceImpl.pageQuery:259-261 and
  KnowledgeDocumentServiceImpl.search:659-661 (USER with empty
  accessibleKbIds → empty page / empty list).
- docs/dev/verification/permission-pr1-controllers-clean.sh asserts no
  controller still calls kbAccessService.check* (PR1 grep gate).
- docs/dev/verification/permission-pr1-smoke.md documents the four
  manual smoke paths required pre-merge.

Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md (commit 5)
EOF
)"
```

---

## Commit 6 (optional) — Internal-helper javadoc + `update` deferral marker

### Task 6.1: Add internal-helper javadoc to `KnowledgeChunkService`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeChunkService.java`

- [ ] **Step 1: Add javadoc to `batchCreate`, `deleteByDocId`, `updateEnabledByDocId`, `listByDocId`**

For each of those four methods, prepend the javadoc:
```java
    /**
     * Internal method. Caller (KnowledgeDocumentService / chunk pipeline) must
     * have already validated document / KB authorization. Do NOT expose to HTTP
     * entry without first establishing a controller-level or service-level
     * authorization gate.
     */
```

If a method already has an existing javadoc, prepend the warning paragraph above the existing description.

### Task 6.2: Add deferral marker to `KnowledgeBaseServiceImpl.update`

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java`

- [ ] **Step 1: Locate `update(KnowledgeBaseUpdateRequest)` (around line 126) and add javadoc**

```java
    /**
     * Public write path with **no production caller** as of PR1. Deferred to a
     * future PR — when an HTTP / MQ caller is introduced, add
     * {@code kbAccessService.checkManageAccess(requestParam.getId())} as the
     * first line to establish the trust boundary, mirroring rename/delete.
     *
     * Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md §2.6
     */
    public void update(KnowledgeBaseUpdateRequest requestParam) {
```

### Task 6.3: Commit (optional)

- [ ] **Step 1: Compile + commit**

Run: `mvn -pl bootstrap compile`
Expected: BUILD SUCCESS.

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/KnowledgeChunkService.java \
        bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/service/impl/KnowledgeBaseServiceImpl.java
git commit -m "docs(security): mark chunk internal helpers + KnowledgeBase update as deferred-trust-boundary"
```

---

## Final Verification (run before opening the PR)

- [ ] **Step 1: Full bootstrap test pass**

Run: `mvn -pl bootstrap test`
Expected: BUILD SUCCESS or only the 5 known baseline-red tests fail.

- [ ] **Step 2: Spotless formatting**

Run: `mvn spotless:check`
Expected: BUILD SUCCESS. If failure: `mvn spotless:apply` then re-stage formatted files in a new commit `style: spotless apply`.

- [ ] **Step 3: rg verification script**

Run: `bash docs/dev/verification/permission-pr1-controllers-clean.sh`
Expected: `OK: no kbAccessService.check* found in controllers.`

- [ ] **Step 4: Manual smoke checklist**

Walk through `docs/dev/verification/permission-pr1-smoke.md`. Mark all four paths sign-off boxes.

- [ ] **Step 5: Open PR using description template from spec §4.4**

```markdown
## PR1 — Permission Boundary Thinning (Controller → Service)

### Scope
- Remove 14 inline kbAccessService.check* calls from controllers
  (KnowledgeBaseController + KnowledgeDocumentController)
- Add check at 20 service user entries
  (KB 3 + Document 9 + Chunk 6 + RoleBinding 2)
- Add system actor (LoginUser.system + UserContext.isSystem()) for MQ/Schedule
- Zero change to permission decision logic; zero change to HTTP semantics

### Out of Scope (deferred PRs)
- KbScopeResolver / applyKbScope unification → PR2
- AccessServiceImpl:325 + :183 caller-context leak → PR2 (with KbAccessCalculator)
- RetrievalScopeBuilder + OpenSearch filter hardening → PR3 + PR4
- Sharing service / audit_log / access_request → PR5+

### Verification
- [x] T1 / T2a / T2b / T3 contract tests green
- [x] 4 parameterized boundary tests green (20 entries)
- [x] grep script: no kbAccessService.check* in **/controller/*.java
- [x] Manual smoke: rename own KB / cross-dept rename / chunk via MQ /
      missing-user reviewed
```

---

## Self-Review Notes (run after writing the plan)

1. **Spec coverage** — every section of the spec has at least one task:
   - §1 invariants A/B/C/D — encoded across commit 3 (B), commit 4 (A, D), commit 4 controller-retain (C).
   - §2.1 framework — commit 1.
   - §2.2 KbAccessService guard — commit 3.
   - §2.3 controller removals — commit 4 task 4.6.
   - §2.4 20 service entries — commit 4 tasks 4.1-4.5.
   - §2.5 system entries — commit 2.
   - §2.6 explicit deferrals — commit 6 (optional) plus PR description.
   - §3.1 T1/T2a/T2b/T3 — commits 2, 3, 5.
   - §3.2 4 parameterized test classes — commit 4.
   - §3.3 rg script — commit 5.
   - §3.4 smoke — commit 5.
   - §3.5 not-tested deferrals — explicit in PR description.
   - §3.6 baseline-red tolerance — final verification step 1.
   - §4 commit sequence — top-level structure.
   - §5 risks — covered by guard structure (R2 watch via "missing user context" log) + double-gate (R1) + comments (R3 PR description rule).

2. **Placeholder scan** — no TBD/TODO/"add appropriate handling" remain. Two test bodies (`PageQueryFailClosedTest.documentSearch_returns_empty_list_*`) are sketched and explicitly flagged as "flesh out using TestKnowledgeDocumentServiceFactory pattern" with the existing line numbers (659-661) given. The factory helpers (`TestKnowledgeBaseServiceFactory` etc.) are described concretely with the field-mirroring instruction.

3. **Type / signature consistency** — `bypassIfSystemOrAssertActor()` referenced consistently (commit 3 helper, commit 3 method bodies, T2a/T3 implicit through method calls). `system(true)` builder syntax consistent commit 1 → commit 2 → commit 3. `KbAccessService.check*` method names match spec §2.2 throughout.

4. **Ambiguity** — chunk `batchToggleEnabled` resolved (single-docId per controller signature, verified). Chunk `pageQuery` docId→kbId resolution made concrete via inline `kbMetadataReader.getKbIdOfDoc(docId)` (no new API).
