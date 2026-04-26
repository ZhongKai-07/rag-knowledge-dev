# PR1 — Permission Boundary Thinning (Controller → Service)

**Date**: 2026-04-26
**Author**: brainstorming session (zk2793126229@gmail.com + Claude)
**Scope**: Move inline `kbAccessService.check*` calls from controllers down to service entries. Add `system` actor for MQ/Schedule. Zero change to permission decision logic; zero change to HTTP semantics.
**Out-of-scope marker**: This is **PR1** of an 8-PR roadmap. PR2-PR8 are explicitly deferred (see §5.2).

---

## 0. Problem Statement

The current permission surface is split between three locations:

- **Controllers** call `kbAccessService.check*` inline before delegating to services (14 inline checks across `KnowledgeBaseController` + `KnowledgeDocumentController`).
- **Services** trust controllers and re-perform no checks. Any non-HTTP caller (MQ consumer, scheduled job, internal service-to-service call, future SDK) bypasses authorization entirely.
- **Some controllers** (e.g. `KnowledgeBaseController.applyKbScope`, `SpacesController`) compute readable-KB scope inline with logic that overlaps `KbAccessService.getAccessibleKbIds`.

This PR addresses the first two by establishing the **service public method as the trust boundary**. The third (scope-resolver unification) is deferred to PR2.

The driving concern: any new entry point added to the codebase today silently bypasses authorization. This PR closes that hole without changing what any specific role can or cannot do.

---

## 1. Layering Contract

```
┌──────────────────────────────────────────────────────────┐
│ Controller                                               │
│   - HTTP-protocol responsibility only                    │
│     (param parsing / VO assembly / Result wrapping)      │
│   - Does NOT call kbAccessService.check* methods         │
│   - scope/list narrowing logic temporarily retained      │
│     (PR2 collapses into KbScopeResolver)                 │
│   - @SaCheckRole platform-level macro gates retained     │
│     (especially KnowledgeChunkController)                │
├──────────────────────────────────────────────────────────┤
│ Service (KB / Document / Chunk / Role-Binding)           │
│   - Public user-entry methods are the trust boundary     │
│   - First line calls KbAccessService.check*              │
│   - Internal public helpers retain interface visibility  │
│     but gain javadoc:                                    │
│     "Internal method. Caller must have already validated │
│      document / KB authorization. Do NOT expose to HTTP  │
│      entry."                                             │
│   - package-private migration / KnowledgeChunkInternal-  │
│     Service split deferred                               │
│   - System actor handled by KbAccessService bypass       │
├──────────────────────────────────────────────────────────┤
│ KbAccessService (PR1: only adds system bypass + guard)   │
│   if (UserContext.isSystem()) return;                    │
│   if (!UserContext.hasUser() ||                          │
│       UserContext.getUserId() == null)                   │
│       throw new ClientException("missing user context"); │
├──────────────────────────────────────────────────────────┤
│ MQ / Schedule                                            │
│   - Entry sets LoginUser.builder()...system(true).build()│
│   - UserContext.set(systemUser) → calls service          │
└──────────────────────────────────────────────────────────┘
```

### Invariants

- **A — Boundary uniqueness**: KB / Document / Chunk service public user-entry methods are the **only** authorization boundary. Once controllers stop checking, any new caller (test, internal service, future SDK) is automatically governed.
- **B — System explicitness**: `UserContext.isSystem()` returns `true` **only** when `system=true` is explicitly set on the `LoginUser`. HTTP entries that forget to attach a user produce `isSystem()=false` AND `getUserId()=null` → `ClientException("missing user context")` — they do **not** silently degrade to system bypass.
- **C — HTTP semantics unchanged**: PR1 changes no rule about "who can call which HTTP endpoint". `KnowledgeChunkController` retains its class-level `@SaCheckRole("SUPER_ADMIN")` in full.
- **D — Chunk service vs HTTP boundary separation**: `KnowledgeChunkService` user-entry methods gain `checkDocManageAccess(docId)` to establish the **service-level** business boundary (where a `DEPT_ADMIN` managing a chunk inside their own dept's KB would be semantically valid). Because the controller still gates on `@SaCheckRole("SUPER_ADMIN")`, the **external HTTP behavior remains SUPER_ADMIN-only**. Whether to open chunk management to `DEPT_ADMIN` in the UI is a separate PR decision and **not in PR1 scope**.

---

## 2. Per-File Inventory

### 2.1 framework — new `system` actor expression

| File | Change |
|---|---|
| `framework/.../context/LoginUser.java` | Add `boolean system` field (Lombok `@Builder.Default(false)`) |
| `framework/.../context/UserContext.java` | Add `static boolean isSystem()` — `LoginUser u = get(); return u != null && u.isSystem();` |

The `system` field is **never** persisted, never serialized to VO/Result; it lives only in the ThreadLocal. Frontend never sees it.

### 2.2 KbAccessService — system bypass + missing-user guard

| File | Change |
|---|---|
| `user/service/impl/KbAccessServiceImpl.java` | Add private helper + first-line invocation in 5 decision-check methods |

**Helper**:
```java
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

**Applied to (5 methods)**: `checkAccess`, `checkManageAccess`, `checkDocManageAccess`, `checkDocSecurityLevelAccess`, `checkKbRoleBindingAccess`. Each gets a first-line:
```java
if (bypassIfSystemOrAssertActor()) {
    return;
}
// ...existing decision logic, unchanged
```

**Not guarded directly**: `checkReadAccess(kbId)` is a delegator to `checkAccess(kbId)`; reusing the underlying method's guard avoids a duplicate entry.

**Decision logic untouched**: lines 85-194 (`getAccessibleKbIds` / `computeRbacKbIds` / `computeDeptAdminAccessibleKbIds`) and 327-380 (`getMaxSecurityLevelForKb`) are not modified.

`ClientException` is the existing exception type used by `KbAccessServiceImpl` for permission denials — PR1 introduces no new exception semantics.

### 2.3 Controller — remove inline checks (HTTP semantics unchanged)

| File | Lines to remove | Notes |
|---|---|---|
| `KnowledgeBaseController.java` | `:76` `checkManageAccess(kbId)` (rename) <br> `:86` `checkManageAccess(kbId)` (delete) <br> `:96` `checkAccess(kbId)` (query) <br> `:147` `checkKbRoleBindingAccess(kbId)` (getRoleBindings) <br> `:154` `checkKbRoleBindingAccess(kbId)` (setRoleBindings) | `applyKbScope` (lines 111-131) **retained** — PR2 |
| `KnowledgeDocumentController.java` | `:72` `checkManageAccess(kbId)` (upload) <br> `:81` `checkDocManageAccess(docId)` (startChunk) <br> `:91` `checkDocManageAccess(docId)` (delete) <br> `:103` `checkAccess(doc.getKbId())` (get) <br> `:114` `checkDocManageAccess(docId)` (update) <br> `:125` `checkAccess(kbId)` (page) <br> `:148` `checkDocManageAccess(docId)` (enable) <br> `:162` `checkDocSecurityLevelAccess(docId, newLevel)` (updateSecurityLevel) <br> `:178` `checkDocManageAccess(docId)` (getChunkLogs) | `:135-138` `search` accessibleKbIds compute **retained** — PR2 |
| `KnowledgeChunkController.java` | (no inline check to remove) | Class-level `@SaCheckRole("SUPER_ADMIN")` **fully retained** (Invariant C) |
| `SpacesController.java` | (no change) | Scope narrowing deferred to PR2 |
| `RoleController.java` (role-binding endpoints) | (no change) | Class-level `@SaCheckRole` already covers; PR1 leaves alone |

**Total inline checks removed**: 14 (KB 5 + Document 9).

### 2.4 Service — user entries gain check (trust boundary established)

**Total user entries adding check**: **20** (KB 3 + Document 9 + Chunk 6 + RoleBinding 2).

| File | Method | Inserted call |
|---|---|---|
| `KnowledgeBaseServiceImpl.java` | `rename(kbId, request)` | `kbAccessService.checkManageAccess(kbId)` |
| ↑ | `delete(kbId)` | `kbAccessService.checkManageAccess(kbId)` |
| ↑ | `queryById(kbId)` | `kbAccessService.checkAccess(kbId)` |
| `KnowledgeDocumentServiceImpl.java` | `upload(kbId, request, file)` | `kbAccessService.checkManageAccess(kbId)` |
| ↑ | `startChunk(docId)` | `kbAccessService.checkDocManageAccess(docId)` |
| ↑ | `delete(docId)` | `kbAccessService.checkDocManageAccess(docId)` |
| ↑ | `get(docId)` | Fetch doc; if non-null call `kbAccessService.checkAccess(doc.getKbId())`; if null return null without calling check (preserves current `KnowledgeDocumentController.java:101-105` semantics — "doc not found" returns null rather than throws permission error) |
| ↑ | `update(docId, request)` | `kbAccessService.checkDocManageAccess(docId)` |
| ↑ | `page(kbId, request)` | `kbAccessService.checkAccess(kbId)` |
| ↑ | `enable(docId, enabled)` | `kbAccessService.checkDocManageAccess(docId)` |
| ↑ | `updateSecurityLevel(docId, newLevel)` | Param validation (0-3) + `kbAccessService.checkDocSecurityLevelAccess(docId, newLevel)` |
| ↑ | `getChunkLogs(docId, page)` | `kbAccessService.checkDocManageAccess(docId)` |
| `KnowledgeChunkServiceImpl.java` | `pageQuery(docId, request)` | `kbAccessService.checkAccess(<docId → kbId>)` |
| ↑ | `create(docId, request)` | `kbAccessService.checkDocManageAccess(docId)` |
| ↑ | `update(chunkId, ...)` | Fetch chunk → `kbAccessService.checkDocManageAccess(chunk.docId)` |
| ↑ | `delete(chunkId)` | (same pattern) |
| ↑ | `enableChunk(chunkId, enabled)` | (same pattern) |
| ↑ | `batchToggleEnabled(...)` | Fetch input chunks → deduplicate `docId`s → call `checkDocManageAccess(docId)` for **each** unique `docId`. If any check throws, the whole batch fails (conservative: chunks spanning multiple docs require every doc to satisfy the check). |
| `RoleServiceImpl.java` | `getKbRoleBindings(kbId)` | `kbAccessService.checkKbRoleBindingAccess(kbId)` |
| ↑ | `setKbRoleBindings(kbId, bindings)` | `kbAccessService.checkKbRoleBindingAccess(kbId)` |

**Internal helpers (interface signature preserved, javadoc only)**:

`KnowledgeChunkService` / `KnowledgeChunkServiceImpl` methods `batchCreate` / `deleteByDocId` / `updateEnabledByDocId` / `listByDocId` are called from `KnowledgeDocumentService` and the chunk pipeline. They gain javadoc:
```
/**
 * Internal method. Caller (KnowledgeDocumentService / chunk pipeline) must
 * have already validated document / KB authorization. Do NOT expose to HTTP
 * entry.
 */
```
A `package-private` reduction or split into `KnowledgeChunkInternalService` is deferred to a future PR — Java interface methods are inherently `public`, so visibility narrowing requires interface restructuring beyond PR1 scope.

### 2.5 System-state entries — explicit `system=true`

| File | Current | Change |
|---|---|---|
| `knowledge/mq/KnowledgeDocumentChunkConsumer.java:52` | `LoginUser.builder().username(event.operator).build()` | `LoginUser.builder().username(event.operator).system(true).build()` |
| `knowledge/schedule/ScheduleRefreshProcessor.java:211` | (similar half-built `LoginUser`) | Add `.system(true)` |

**Existing `try / finally UserContext.clear()` is preserved** — note the API name is `clear()`, **not** `remove()` (delegates to `CONTEXT.remove()` internally).

`KnowledgeDocumentSecurityLevelRefreshConsumer.java:48` does **not** set `UserContext` and does not call any KB/Doc/Chunk service write path (only `VectorStoreService.updateChunksMetadata`). Not modified.

`MessageFeedbackConsumer` lives in the rag feedback domain, not in the KB/Doc/Chunk authorization perimeter. Not modified.

### 2.6 Files explicitly NOT modified (PR description must enumerate)

- `framework/security/port/*` — the 7 ports themselves are not touched.
- `KbAccessServiceImpl` decision logic (lines 85-194 + 327-380) — unchanged.
- `AccessServiceImpl:325` `computeRbacKbIdsFor` duplication — deferred PR2 (with `KbAccessCalculator`).
- `AccessServiceImpl:183` `getMaxSecurityLevelForKb` caller-context leak — deferred PR2.
- `KnowledgeBaseController.applyKbScope` + `KnowledgeBaseService.pageQuery`'s `accessibleKbIds` DTO field — deferred PR2.
- `SpacesController` scope compute — deferred PR2.
- `KnowledgeDocumentController.search`'s accessibleKbIds compute — deferred PR2.
- All frontend code — PR1 does not touch frontend.
- OpenSearch / `role_kb_relation` / database schema — PR1 does not touch storage.
- `KnowledgeBaseServiceImpl.update(requestParam)` — public write path with no production caller; not in PR1 scope. A future PR adds the trust boundary when a caller is introduced.

---

## 3. Test & Acceptance Contracts

### 3.1 Mandatory contract tests (CI gate)

#### T1 — Regular user with empty accessibleKbIds → fail-closed

```
Given:  USER login with no role_kb_relation entries
When:   GET /knowledge-base?scope=  (default RBAC scope)
Then:   empty page (total=0), NOT a full listing
And:    GET /knowledge-base/docs/search?keyword=x  → empty list
```

**Fixture caveat**: do **not** reuse `fixture_pr3_demo.sql`'s `carol` (already bound to `kb-rnd-001`). Build an isolated user: `role_type=USER` + `t_user_role` row + **zero `t_role_kb_relation` rows** + clear `kb_access:{userId}` cache (or mock `getAccessibleKbIds` returning empty set).

Locks intent: when PR2 rewrites the scope resolver, a wiring mistake triggers immediate red.

#### T2a — KbAccessService system bypass returns early

```
Given:  UserContext.set(LoginUser.builder().username("op").system(true).build())
When:   call each of the 5 guarded methods:
        checkAccess / checkManageAccess / checkDocManageAccess
        / checkDocSecurityLevelAccess / checkKbRoleBindingAccess
Then:   none throw
And:    verifyNoInteractions(kbMetadataReader, userRoleMapper, roleKbRelationMapper)
        — proves it was an early return, not an "accidental allow after DB lookup"
```

#### T2b — MQ/Schedule entries actually set system actor

```
Given:  KnowledgeDocumentChunkConsumer with mocked documentService
When:   consumer.onMessage(message)
Then:   the mock's answer asserts UserContext.isSystem() == true
And:    after onMessage returns, UserContext.hasUser() == false
        (proves the finally-clear ran)
```

For `ScheduleRefreshProcessor`: if an existing test class exists, add the same assertion. Otherwise call out as a manual / code-review checkpoint in the PR description.

#### T3 — Missing UserContext → throw, not bypass

```
Given:  UserContext.clear()  (neither user nor system)
When:   knowledgeBaseService.delete(kbId)
Then:   throws ClientException("missing user context")
```

Locks Invariant B: HTTP entries that forget to attach a user produce **explicit failure**, not silent bypass.

### 3.2 Entry-coverage matrix (parameterized, 4 test classes)

Grouped by service, **not** 20 hand-written cases:

| Test class | Data matrix | Entries |
|---|---|---|
| `KnowledgeBaseServiceAuthBoundaryTest` | `rename / delete / queryById` | 3 |
| `KnowledgeDocumentServiceAuthBoundaryTest` | `upload / startChunk / delete / get / update / page / enable / updateSecurityLevel / getChunkLogs` | 9 |
| `KnowledgeChunkServiceAuthBoundaryTest` | `pageQuery / create / update / delete / enableChunk / batchToggleEnabled` | 6 |
| `RoleServiceAuthBoundaryTest` | `getKbRoleBindings / setKbRoleBindings` | 2 |

Pattern:
```java
@ParameterizedTest
@MethodSource("entryPoints")
void entryPoint_propagates_check_failure(EntryInvocation entry) {
    when(kbAccessService.checkXxx(any())).thenThrow(new ClientException("denied"));
    // entries that fetch entity-then-check (e.g. chunk.update) need minimal entity fixture
    assertThrows(ClientException.class, () -> entry.invoke());
}
```

### 3.3 "No missed migration" verification script (in PR description)

```bash
rg -n "kbAccessService\.(checkAccess|checkManageAccess|checkDocManageAccess|checkDocSecurityLevelAccess|checkKbRoleBindingAccess)\(" \
   bootstrap/src/main/java/com/nageoffer/ai/ragent -g "*Controller.java"
```

Expected output: empty.

Note: this script does **not** flag `applyKbScope`'s internal `isSuperAdmin` / `isDeptAdmin` / `getAccessibleKbIds` calls — those are PR2's territory.

### 3.4 Integration smoke (manual, document in PR demo section)

Four fixed paths, all green:

1. **OPS_ADMIN** renames their own KB → 200
2. **FICC_USER** renames OPS's KB → 403 (service rejects, identical to pre-PR1)
3. **Trigger document chunking** (HTTP upload → MQ → executeChunk) → completes without permission stall
4. **Anonymous call to `/knowledge-base/{kbId}`** (no token) → 401 (Sa-Token); if `UserContext` is forcibly cleared and service is called directly → `ClientException("missing user context")`

### 3.5 Tests explicitly NOT written (PR description must enumerate)

- `KbScopeResolver` / `RetrievalScopeBuilder` tests — PR2/PR3.
- OpenSearch metadata filter / `security_level` fail-closed — PR4.
- `audit_log` behavior — PR6.
- `KnowledgeBaseServiceImpl.update` check — out of PR1 scope.

### 3.6 Regression baseline

PR1 must not introduce new test failures. Known baseline-red tests (per project `CLAUDE.md`):

- `MilvusCollectionTests`
- `InvoiceIndexDocumentTests`
- `PgVectorStoreServiceTest.testChineseCharacterInsertion`
- `IntentTreeServiceTests.initFromFactory`
- `VectorTreeIntentClassifierTests`

These remain allowed to fail at PR1 acceptance; everything else green.

---

## 4. Commit Sequence & Rollback Strategy

### 4.1 Commit sequence (each commit independently compiles + tests green)

```
commit 1: framework layer
  - LoginUser.system field (@Builder.Default(false))
  - UserContext.isSystem()
  - unit tests covering new API
  → zero behavioral change

commit 2: MQ/Schedule entries adopt system=true first
  - KnowledgeDocumentChunkConsumer.java:52 add .system(true)
  - ScheduleRefreshProcessor.java:211 add .system(true)
  - T2b test (onMessage internal isSystem()==true; after-return hasUser()==false)
  → isSystem() exists, but KbAccessService doesn't yet consume it
  → semantically zero change; pre-stages system actor for commit 3

commit 3: KbAccessService adds bypassIfSystemOrAssertActor() guard
  - 5 check methods gain first-line guard call
  - T2a (system bypass early-return + verifyNoInteractions)
  - T3 (missing user context throws)
  → MQ/Schedule already set system=true (commit 2), guard does not break them
  → HTTP entries still have inline checks (commit 4 removes them); normal paths green

commit 4: Controller thinning + service entry checks
  - Remove 14 inline kbAccessService.check* from controllers
  - Add check at 20 service user entries (KB 3 + Doc 9 + Chunk 6 + RoleBinding 2)
  - Section 3.2 4 parameterized boundary test classes
  → trust boundary moves from controller to service

commit 5: Verification closure
  - T1 (regular user empty accessibleKbIds → fail-closed)
  - rg verification script committed to .github/workflows/ or
    docs/dev/verification/
  - Manual smoke documented (4 fixed paths)

commit 6 (optional): Documentation comments
  - KnowledgeBaseServiceImpl.update javadoc:
    "no production caller; deferred to future PR"
  - Chunk internal helpers javadoc:
    "Internal method. Caller must have already validated authorization."
```

Each commit individually passes `mvn -pl bootstrap test` (excluding the 5 known baseline-red tests).

### 4.2 Rollback strategy (two independent units)

**commit 4 — service-boundary migration unit**
- Trigger: a service entry's semantics break (typo / missed entry / over-applied check / parameterized test missed a case).
- Action: `revert commit 4` restores complete pre-PR1 controller→service boundary.
- Risk: low; commits 1-3 remain as inert infrastructure.

**commit 3 — fail-closed guard unit**
- Trigger: after the guard goes live, an **unknown background entry** (not in the audited MQ/Schedule list) raises `ClientException("missing user context")`.
- **Preferred strategy**: roll forward — add `.system(true)` to that entry rather than revert.
- **Fallback strategy**: if roll-forward is infeasible, revert commit 3 (guard disappears; reverts to old "no-user silently bypassed" default).

**commit 1 / commit 2 are not rollback units** — pure infrastructure additions, leaving them in place is zero-cost. If a specific MQ/Schedule line in commit 2 is wrong, revert that single line, not the whole commit.

### 4.3 Invariant verification (run pre-merge AND post-merge)

```
✓ T1 / T2a / T2b / T3 all green
✓ 4 parameterized boundary test classes green (covering 20 entries)
✓ rg verification script returns empty
✓ bootstrap mvn test fully green except the 5 known baseline-red tests
✓ 4 manual smoke paths pass
```

### 4.4 PR description template

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
- [ ] T1 / T2a / T2b / T3 contract tests green
- [ ] 4 parameterized boundary tests green (20 entries)
- [ ] grep script: no kbAccessService.check* in **/controller/*.java
- [ ] Manual smoke: rename own KB / cross-dept rename / chunk via MQ /
      missing-user throws
```

---

## 5. Risks & Known Gaps

### 5.1 Known PR1 risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Missing a controller inline check (KB 5 + Doc 9 = **14 sites**) OR missing a service entry's added check (KB 3 + Doc 9 + Chunk 6 + RoleBinding 2 = **20 entries**) | `rg` verification script locks controllers to empty + 20-entry parameterized tests lock service boundary; double gate |
| R2 | An unknown background entry (test fixture / future `@Async` / third-party component) trips the missing-user throw | commit 3 roll-forward by adding `.system(true)`; observe ERROR logs containing `"missing user context"` for one week post-merge |
| R3 | `system=true` misuse spread (developers treat `.system(true)` as a universal bypass) | PR description rule: "**`system=true` is permitted only for MQ consumers / `@Scheduled` / framework-level callbacks**"; ArchUnit class rule lock-down deferred to PR2 |
| R4 | Chunk service public helpers protected only by javadoc | Acknowledged tradeoff; package-private split / `KnowledgeChunkInternalService` extraction deferred to PR2/PR3 |
| R5 | Maintenance burden when a service entry's signature changes | 4 grouped parameterized test classes update centrally; not 20 hand-written cases |

### 5.2 Explicit deferrals (PR description "Out of Scope" mirror)

- `KbAccessCalculator` extraction (target-aware, no caller-context dependency) → **PR2**
- `AccessServiceImpl:325` `computeRbacKbIdsFor` duplication → **PR2**
- `AccessServiceImpl:183` `getMaxSecurityLevelForKb` caller-context leak → **PR2**
- `KnowledgeBaseController.applyKbScope` + `SpacesController.applyKbScope` + `KnowledgeDocumentController.search` accessibleKbIds compute → **PR2 (KbScopeResolver)**
- `KnowledgeBasePageRequest.accessibleKbIds` DTO field → **PR2 (delete)**
- `RetrievalScopeBuilder` + RAG entry scope unification → **PR3**
- OpenSearch metadata filter hardening + mandatory `kb_id` / `security_level` / `status` / `enabled` → **PR4**
- `KbSharingService` extraction + role-binding productization → **PR5**
- `audit_log` → **PR6**
- `access_request` flow → **PR7**
- `KnowledgeBaseServiceImpl.update` adding check → no production caller; future PR when one is introduced

### 5.3 Out of any PR's scope (architectural boundary statement)

- Introducing OpenFGA / OPA / Casbin or other third-party authorization frameworks — not on the roadmap unless `role_kb_relation` becomes inexpressive (see `docs/dev/research/enterprise_permissions_architecture.md` Phase 8).

---

## 6. Decision Log

Captured during the brainstorming session, for future readers wondering why a particular shape was chosen.

- **Why not extract `KbAccessCalculator` in PR1?** PR1's downsteam points (KB / Doc / Chunk write paths) all run with `caller == target` semantics, so they don't need a target-aware calculator. The calculator is only needed for `AccessServiceImpl`'s admin-views-target paths, which are deferred to PR2. Adding the calculator in PR1 would inflate scope without unlocking any PR1 acceptance criterion.

- **Why explicit `system=true` instead of `LoginUser.isSystem()` heuristic ("no userId means system")?** A heuristic encourages fail-open: any HTTP filter regression that loses `UserContext` would silently degrade to system bypass. The explicit boolean makes "this is a system actor" a deliberate construction-site decision, greppable as `system(true)`.

- **Why not `*AsSystem` paired-API on `KbAccessService`?** Doubles the API surface (5 check methods → 10), pushes the system/normal decision to every caller, and offers no advantage over a `UserContext.isSystem()` flag set once at the entry boundary.

- **Why `ClientException` for the missing-user guard, not `ServiceException`?** `KbAccessServiceImpl` already throws `ClientException` for permission denials. PR1's no-new-semantics rule keeps the exception family consistent.

- **Why retain `KnowledgeChunkController` `@SaCheckRole("SUPER_ADMIN")` even after adding service-level `checkDocManageAccess`?** Invariant C requires HTTP semantics to be unchanged in PR1. The service-level check is wider than `SUPER_ADMIN` (it admits `DEPT_ADMIN` for own-dept chunks), but the controller annotation continues to gate HTTP narrower. Whether to widen the HTTP gate is a separate product decision.

- **Why does `KnowledgeBaseServiceImpl.update` not gain a check?** It has no production caller. Adding a check would be the right move when a caller is introduced; doing it now would silently shape future call sites that haven't been designed yet.
