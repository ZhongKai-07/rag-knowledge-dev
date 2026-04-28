# RBAC Permission Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 5 RBAC vulnerabilities (security_level cross-KB leakage, DEPT_ADMIN blocked endpoints, privilege escalation, deptId change, missing KB sharing API) across backend and frontend.

**Architecture:** Two-phase approach — Phase 1 patches the high-severity retrieval security_level leak by introducing per-KB security level resolution (new `t_role_kb_relation.max_security_level` column + resolver function replacing global scalar). Phase 2 opens DEPT_ADMIN read endpoints, adds KB-centric sharing API, blocks privilege escalation, and adapts the frontend.

**Tech Stack:** Java 17 / Spring Boot 3.5 / MyBatis Plus / PostgreSQL / Redis / Sa-Token / React 18 / TypeScript / Vite

**Design Spec:** `docs/dev/design/2026-04-13-rbac-permission-fixes-design.md`

---

## File Map

### Phase 1 — Security Fix (A + E)

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `resources/database/schema_pg.sql` | Add `max_security_level` to `t_role_kb_relation` |
| Modify | `resources/database/full_schema_pg.sql` | Same (pg_dump style) |
| Modify | `resources/database/init_data_pg.sql` | Seed data for new column |
| Modify | `bootstrap/.../user/dao/entity/RoleKbRelationDO.java` | New `maxSecurityLevel` field |
| Modify | `bootstrap/.../user/controller/RoleController.java` | `RoleKbBindingRequest` add `maxSecurityLevel` |
| Modify | `bootstrap/.../user/service/impl/RoleServiceImpl.java` | Write/read `maxSecurityLevel` in KB bindings |
| Modify | `bootstrap/.../user/service/KbAccessService.java` | New `getMaxSecurityLevelForKb()` method |
| Modify | `bootstrap/.../user/service/impl/KbAccessServiceImpl.java` | Implementation + Redis Hash cache |
| Modify | `bootstrap/.../rag/core/retrieve/channel/SearchContext.java` | Replace `maxSecurityLevel` with `kbSecurityLevelResolver` |
| Modify | `bootstrap/.../rag/core/retrieve/MultiChannelRetrievalEngine.java` | `buildMetadataFilters(ctx, kbId)` + update `buildSearchContext` |
| Modify | `bootstrap/.../rag/core/retrieve/channel/VectorGlobalSearchChannel.java` | Per-KB filters in collection tasks |
| Modify | `bootstrap/.../rag/core/retrieve/channel/IntentDirectedSearchChannel.java` | Pass `SearchContext` instead of shared filters |
| Modify | `bootstrap/.../rag/core/retrieve/channel/strategy/IntentParallelRetriever.java` | Per-intent KB filter resolution |
| Modify | `frontend/src/services/roleService.ts` | `RoleKbBinding` add `maxSecurityLevel` |
| Modify | `frontend/src/pages/admin/roles/RoleListPage.tsx` | KB binding dialog add level dropdown |

### Phase 2 — Functional Fixes (B + C + D + F)

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `bootstrap/.../user/controller/RoleController.java` | Open `GET /role` + `GET /user/{userId}/roles` to DEPT_ADMIN |
| Modify | `bootstrap/.../user/controller/SysDeptController.java` | Class→method level `@SaCheckRole` |
| Modify | `bootstrap/.../user/service/KbAccessService.java` | New `validateRoleAssignment()` + `checkKbRoleBindingAccess()` |
| Modify | `bootstrap/.../user/service/impl/KbAccessServiceImpl.java` | Implementation |
| Modify | `bootstrap/.../knowledge/controller/KnowledgeBaseController.java` | New `GET/PUT /knowledge-base/{kbId}/role-bindings` |
| Modify | `bootstrap/.../user/service/RoleService.java` | New `getKbRoleBindings()` + `setKbRoleBindings()` |
| Modify | `bootstrap/.../user/service/impl/RoleServiceImpl.java` | Implementation |
| Modify | `bootstrap/.../user/service/impl/UserServiceImpl.java` | deptId change guard |
| Modify | `frontend/src/utils/permissions.ts` | DEPT_ADMIN sees "roles" menu + `canAssignRole` excludes DEPT_ADMIN |
| Modify | `frontend/src/router.tsx` | `/admin/roles` → `RequireMenuAccess("roles")` |
| Modify | `frontend/src/pages/admin/roles/RoleListPage.tsx` | DEPT_ADMIN read-only mode |
| Modify | `frontend/src/pages/admin/users/UserListPage.tsx` | deptId disabled for DEPT_ADMIN |
| Modify | `frontend/src/services/knowledgeService.ts` | KB role-binding API functions |
| Create | `frontend/src/pages/admin/knowledge/KbSharingTab.tsx` | KB sharing management UI |
| Modify | Knowledge detail page (if exists) or `KnowledgeDocumentPage.tsx` | Integrate sharing tab |

---

## Phase 1: Security Fix

### Task 1: Schema + Entity + DTO — add `max_security_level` to `t_role_kb_relation`

**Files:**
- Modify: `resources/database/schema_pg.sql:73-93`
- Modify: `resources/database/full_schema_pg.sql` (t_role_kb_relation section)
- Modify: `resources/database/init_data_pg.sql`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java:39-62`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java:112-117`

- [ ] **Step 1: Update `schema_pg.sql` — add column to `t_role_kb_relation`**

In `resources/database/schema_pg.sql`, add `max_security_level` column to the `t_role_kb_relation` CREATE TABLE between `permission` and `created_by`:

```sql
CREATE TABLE t_role_kb_relation (
    id          VARCHAR(20) NOT NULL PRIMARY KEY,
    role_id     VARCHAR(20) NOT NULL,
    kb_id       VARCHAR(20) NOT NULL,
    permission  VARCHAR(16) NOT NULL DEFAULT 'READ',
    max_security_level SMALLINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(64),
    create_time TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
    deleted     INTEGER     DEFAULT 0
);
```

Add comment after existing comments:

```sql
COMMENT ON COLUMN t_role_kb_relation.max_security_level
  IS '该角色对该 KB 可访问的最高安全等级（0-3），检索时按此值过滤';
```

- [ ] **Step 2: Update `full_schema_pg.sql` — same change in pg_dump style**

Find the `t_role_kb_relation` CREATE TABLE in `full_schema_pg.sql` and add:

```sql
    max_security_level smallint DEFAULT 0 NOT NULL,
```

between `permission` and `created_by`. Add matching COMMENT ON COLUMN line.

- [ ] **Step 3: Update `init_data_pg.sql` — no changes needed**

The init data has no `t_role_kb_relation` inserts (only role + user + user_role). Verify and skip.

- [ ] **Step 4: Add `maxSecurityLevel` field to `RoleKbRelationDO.java`**

In `RoleKbRelationDO.java`, add between `permission` (line ~51) and `createdBy`:

```java
    /**
     * 该角色对该 KB 可访问的最高安全等级（0-3）
     */
    private Integer maxSecurityLevel;
```

- [ ] **Step 5: Add `maxSecurityLevel` field to `RoleKbBindingRequest`**

In `RoleController.java`, update the inner class `RoleKbBindingRequest` (lines 112-117):

```java
    @Data
    public static class RoleKbBindingRequest {
        private String kbId;
        /** 权限级别：READ / WRITE / MANAGE */
        private String permission;
        /** 该角色对该 KB 的最高安全等级（0-3），可选，默认取 role.maxSecurityLevel */
        private Integer maxSecurityLevel;
    }
```

- [ ] **Step 6: Commit**

```bash
git add resources/database/schema_pg.sql resources/database/full_schema_pg.sql bootstrap/src/main/java/com/nageoffer/ai/ragent/user/dao/entity/RoleKbRelationDO.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java
git commit -m "$(cat <<'EOF'
feat(rbac): add max_security_level to t_role_kb_relation

Phase 1 foundation — per-KB security level column enables fine-grained
retrieval filtering instead of global MAX across all roles.
EOF
)"
```

---

### Task 2: RoleService — write/read `maxSecurityLevel` in KB bindings

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java:134-167`

- [ ] **Step 1: Update `setRoleKnowledgeBases()` to write `maxSecurityLevel`**

In `RoleServiceImpl.java`, modify the `setRoleKnowledgeBases()` method (lines 134-152). After line where `relation` is built, add `maxSecurityLevel` resolution:

```java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setRoleKnowledgeBases(String roleId, List<RoleController.RoleKbBindingRequest> bindings) {
        // 删除旧的关联
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));

        if (bindings != null && !bindings.isEmpty()) {
            // 加载角色天花板用于默认值和上界校验
            RoleDO role = roleMapper.selectById(roleId);
            int roleCeiling = (role != null && role.getMaxSecurityLevel() != null)
                    ? role.getMaxSecurityLevel() : 0;

            for (RoleController.RoleKbBindingRequest binding : bindings) {
                int level = (binding.getMaxSecurityLevel() != null)
                        ? binding.getMaxSecurityLevel()
                        : roleCeiling;
                // 上界校验：不超角色天花板
                if (level > roleCeiling) {
                    level = roleCeiling;
                }

                RoleKbRelationDO relation = RoleKbRelationDO.builder()
                        .roleId(roleId)
                        .kbId(binding.getKbId())
                        .permission(binding.getPermission() != null ? binding.getPermission() : "MANAGE")
                        .maxSecurityLevel(level)
                        .build();
                roleKbRelationMapper.insert(relation);
            }
        }

        // 清除所有持有该角色的用户缓存
        evictCacheForRole(roleId);
    }
```

- [ ] **Step 2: Update `getRoleKnowledgeBases()` to return `maxSecurityLevel`**

In `RoleServiceImpl.java`, modify the `getRoleKnowledgeBases()` method (lines 155-167):

```java
    @Override
    public List<RoleController.RoleKbBindingRequest> getRoleKnowledgeBases(String roleId) {
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getRoleId, roleId));
        return relations.stream().map(r -> {
            RoleController.RoleKbBindingRequest req = new RoleController.RoleKbBindingRequest();
            req.setKbId(r.getKbId());
            req.setPermission(r.getPermission() != null ? r.getPermission() : "MANAGE");
            req.setMaxSecurityLevel(r.getMaxSecurityLevel());
            return req;
        }).toList();
    }
```

- [ ] **Step 3: Compile check**

Run: `mvn clean compile -pl bootstrap -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java
git commit -m "$(cat <<'EOF'
feat(rbac): write/read maxSecurityLevel in role-KB bindings

setRoleKnowledgeBases defaults to role ceiling when not specified,
clamps to ceiling as upper bound. getRoleKnowledgeBases returns the
stored per-KB level.
EOF
)"
```

---

### Task 3: `KbAccessService.getMaxSecurityLevelForKb()` + Redis cache

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1: Add interface method**

In `KbAccessService.java`, add after the `evictCache` method (around line 72):

```java
    /**
     * 获取用户对指定 KB 的最高安全等级。
     * <ul>
     *   <li>SUPER_ADMIN → 3</li>
     *   <li>DEPT_ADMIN 且 kb.dept_id == self.dept_id → MAX(自身角色天花板)</li>
     *   <li>其他 → MAX(t_role_kb_relation.max_security_level) through user's roles</li>
     *   <li>无权限 → 0（防御性兜底，不应到达检索阶段）</li>
     * </ul>
     */
    Integer getMaxSecurityLevelForKb(String userId, String kbId);
```

- [ ] **Step 2: Implement with Redis Hash cache**

In `KbAccessServiceImpl.java`, add constants and method. First update the cache key prefix area (around line 58):

```java
    private static final String KB_ACCESS_CACHE_PREFIX = "kb_access:";
    private static final String KB_SECURITY_LEVEL_CACHE_PREFIX = "kb_security_level:";
    private static final long CACHE_TTL_MINUTES = 30;
```

Then add the implementation method:

```java
    @Override
    public Integer getMaxSecurityLevelForKb(String userId, String kbId) {
        if (userId == null || kbId == null) {
            return 0;
        }

        // SUPER_ADMIN: always max
        LoginUser current = UserContext.get();
        if (current != null && current.getRoleTypes() != null
                && current.getRoleTypes().contains(RoleType.SUPER_ADMIN)) {
            return 3;
        }

        // DEPT_ADMIN implicit access to same-dept KBs: use role ceiling
        if (current != null && current.getRoleTypes() != null
                && current.getRoleTypes().contains(RoleType.DEPT_ADMIN)) {
            KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
            if (kb != null && current.getDeptId() != null
                    && current.getDeptId().equals(kb.getDeptId())) {
                return current.getMaxSecurityLevel();
            }
        }

        // Regular path: check Redis Hash cache
        String cacheKey = KB_SECURITY_LEVEL_CACHE_PREFIX + userId;
        RBucket<Map<String, Integer>> bucket = redissonClient.getBucket(cacheKey);
        Map<String, Integer> cached = bucket.get();
        if (cached != null && cached.containsKey(kbId)) {
            return cached.get(kbId);
        }

        // Compute from DB
        List<UserRoleDO> userRoles = userRoleMapper.selectList(
                Wrappers.lambdaQuery(UserRoleDO.class).eq(UserRoleDO::getUserId, userId));
        List<String> roleIds = userRoles.stream().map(UserRoleDO::getRoleId).toList();

        int level = 0;
        if (!roleIds.isEmpty()) {
            List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                    Wrappers.lambdaQuery(RoleKbRelationDO.class)
                            .in(RoleKbRelationDO::getRoleId, roleIds)
                            .eq(RoleKbRelationDO::getKbId, kbId));
            for (RoleKbRelationDO rel : relations) {
                if (rel.getMaxSecurityLevel() != null && rel.getMaxSecurityLevel() > level) {
                    level = rel.getMaxSecurityLevel();
                }
            }
        }

        // Write to cache (hash entry)
        if (cached == null) {
            cached = new java.util.HashMap<>();
        }
        cached.put(kbId, level);
        bucket.set(cached, Duration.ofMinutes(CACHE_TTL_MINUTES));

        return level;
    }
```

Add the import at the top of the file:

```java
import java.time.Duration;
import java.util.Map;
```

- [ ] **Step 3: Update `evictCache()` to also clear security level cache**

In `KbAccessServiceImpl.java`, find the existing `evictCache` method and add the security level cache cleanup:

```java
    @Override
    public void evictCache(String userId) {
        String accessKey = KB_ACCESS_CACHE_PREFIX + userId;
        redissonClient.getBucket(accessKey).delete();
        String securityLevelKey = KB_SECURITY_LEVEL_CACHE_PREFIX + userId;
        redissonClient.getBucket(securityLevelKey).delete();
    }
```

- [ ] **Step 4: Compile check**

Run: `mvn clean compile -pl bootstrap -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "$(cat <<'EOF'
feat(rbac): add getMaxSecurityLevelForKb with per-user Redis Hash cache

SUPER_ADMIN=3, DEPT_ADMIN same-dept=role ceiling, others=MAX from
role_kb_relation. Cache uses Redis Hash keyed by userId, evicted on
role/binding changes.
EOF
)"
```

---

### Task 4: SearchContext + `buildMetadataFilters` refactor

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java:69-74`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java:250-289`

- [ ] **Step 1: Replace `maxSecurityLevel` with `kbSecurityLevelResolver` in SearchContext**

In `SearchContext.java`, replace the `maxSecurityLevel` field (lines 69-74):

Old:
```java
    /**
     * 用户可见的最高安全等级（metadata.security_level <= maxSecurityLevel）。
     * null 表示不做安全等级过滤（如系统内部调用、未登录场景）。
     */
    private Integer maxSecurityLevel;
```

New:
```java
    /**
     * 按 kbId 解析该用户对该 KB 的最高安全等级。
     * 返回 null 表示不做安全等级过滤（如系统内部调用、未登录场景）。
     * 返回 0 表示只看 level=0 的文档。
     */
    private java.util.function.Function<String, Integer> kbSecurityLevelResolver;
```

- [ ] **Step 2: Update `buildSearchContext()` in MultiChannelRetrievalEngine**

In `MultiChannelRetrievalEngine.java`, replace `buildSearchContext()` (lines 250-261).

The current signature is `buildSearchContext(List<SubQuestionIntent> subIntents, int topK, Set<String> accessibleKbIds)`. Keep the same signature, only replace `.maxSecurityLevel(resolveMaxSecurityLevel())` with the resolver:

```java
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents, int topK,
                                              Set<String> accessibleKbIds) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .topK(topK)
                .accessibleKbIds(accessibleKbIds)
                .kbSecurityLevelResolver(kbId -> {
                    if (!UserContext.hasUser()) return null;
                    return kbAccessService.getMaxSecurityLevelForKb(UserContext.getUserId(), kbId);
                })
                .build();
    }
```

- [ ] **Step 3: Delete `resolveMaxSecurityLevel()` method**

Delete the `resolveMaxSecurityLevel()` method (lines 268-273) — it is no longer needed.

- [ ] **Step 4: Update `buildMetadataFilters()` to accept kbId**

Replace the static method (lines 279-289):

```java
    /**
     * 构建元数据过滤条件（按 KB 解析安全等级）。
     *
     * @param ctx  检索上下文（包含 kbSecurityLevelResolver）
     * @param kbId 当前检索的知识库 ID
     * @return 过滤条件列表
     */
    public static List<MetadataFilter> buildMetadataFilters(SearchContext ctx, String kbId) {
        List<MetadataFilter> filters = new ArrayList<>();
        if (ctx.getKbSecurityLevelResolver() != null && kbId != null) {
            Integer level = ctx.getKbSecurityLevelResolver().apply(kbId);
            if (level != null) {
                filters.add(new MetadataFilter(
                        "security_level",
                        MetadataFilter.FilterOp.LTE_OR_MISSING,
                        level));
            }
        }
        return filters;
    }
```

- [ ] **Step 5: Update single-KB retrieval path in `retrieveKnowledgeChannels()`**

In `retrieveKnowledgeChannels()` (around lines 80-101), find the single-KB branch where `buildMetadataFilters(context)` is called and change to `buildMetadataFilters(context, knowledgeBaseId)`.

Find:
```java
buildMetadataFilters(context)
```

Replace with:
```java
buildMetadataFilters(context, knowledgeBaseId)
```

(This occurs in the `knowledgeBaseId != null` branch of `retrieveKnowledgeChannels`.)

- [ ] **Step 6: Compile check**

Run: `mvn clean compile -pl bootstrap -DskipTests`
Expected: Compile errors in VectorGlobalSearchChannel and IntentDirectedSearchChannel (they still call old signature). This is expected — Task 5 fixes them.

- [ ] **Step 7: Commit (WIP — downstream callers updated in Task 5)**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/SearchContext.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MultiChannelRetrievalEngine.java
git commit -m "$(cat <<'EOF'
refactor(rbac): replace global maxSecurityLevel with per-KB resolver

SearchContext now carries a Function<String, Integer> resolver instead
of a single Integer. buildMetadataFilters takes kbId parameter.
WIP: downstream channels updated in next commit.
EOF
)"
```

---

### Task 5: Retrieval paths B + C — per-KB filters in channels

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java:105-197`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java:175-185`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java:53-66`

- [ ] **Step 1: VectorGlobalSearchChannel — return KBs with IDs instead of just collection names**

In `VectorGlobalSearchChannel.java`, change `getAllKBCollections()` (lines 161-182) to return `List<KnowledgeBaseDO>`:

```java
    private List<KnowledgeBaseDO> getAccessibleKBs(SearchContext context) {
        List<KnowledgeBaseDO> kbs = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class));
        if (context.getAccessibleKbIds() != null && !context.getAccessibleKbIds().isEmpty()) {
            kbs = kbs.stream()
                    .filter(kb -> context.getAccessibleKbIds().contains(kb.getId()))
                    .toList();
        }
        return kbs.stream()
                .filter(kb -> kb.getCollectionName() != null && !kb.getCollectionName().isBlank())
                .toList();
    }
```

- [ ] **Step 2: VectorGlobalSearchChannel — update `retrieveFromAllCollections()` to build per-KB filters**

Replace `retrieveFromAllCollections()` (lines 188-197):

```java
    private List<RetrievedChunk> retrieveFromAllCollections(String question,
                                                            List<KnowledgeBaseDO> kbs,
                                                            SearchContext context,
                                                            int topK) {
        List<CollectionParallelRetriever.CollectionTask> tasks = kbs.stream()
                .map(kb -> new CollectionParallelRetriever.CollectionTask(
                        kb.getCollectionName(),
                        MultiChannelRetrievalEngine.buildMetadataFilters(context, kb.getId())))
                .toList();
        return parallelRetriever.executeParallelRetrieval(question, tasks, topK);
    }
```

- [ ] **Step 3: VectorGlobalSearchChannel — update `search()` to use new signatures**

In `search()` (lines 105-155), update the calls:

1. Change `Set<String> collections = getAllKBCollections(context);` to `List<KnowledgeBaseDO> kbs = getAccessibleKBs(context);`
2. Update the empty-check: `if (kbs.isEmpty()) {` (instead of `collections.isEmpty()`)
3. Update the log message to use `kbs.size()` instead of `collections.size()`
4. Update the retrieval call from:
   ```java
   retrieveFromAllCollections(context.getMainQuestion(), collections,
       MultiChannelRetrievalEngine.buildMetadataFilters(context), effectiveTopK)
   ```
   to:
   ```java
   retrieveFromAllCollections(context.getMainQuestion(), kbs, context, effectiveTopK)
   ```

Add import if needed:
```java
import com.knowledgebase.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
```

- [ ] **Step 4: IntentParallelRetriever — accept SearchContext instead of shared filters**

In `IntentParallelRetriever.java`, change the `executeParallelRetrieval` overload (lines 53-66):

```java
    public List<RetrievedChunk> executeParallelRetrieval(
            String question,
            List<NodeScore> targets,
            int fallbackTopK,
            double topKMultiplier,
            SearchContext context) {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> new IntentTask(
                        nodeScore,
                        resolveIntentTopK(nodeScore, fallbackTopK, topKMultiplier),
                        MultiChannelRetrievalEngine.buildMetadataFilters(
                                context, nodeScore.getNode().getKbId())))
                .toList();
        return executeParallelRetrieval(question, intentTasks, fallbackTopK);
    }
```

Add imports:
```java
import com.knowledgebase.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.knowledgebase.ai.ragent.rag.core.retrieve.channel.SearchContext;
```

- [ ] **Step 5: IntentDirectedSearchChannel — pass SearchContext**

In `IntentDirectedSearchChannel.java`, update `retrieveByIntents()` (lines 175-185):

Change from:
```java
    private List<RetrievedChunk> retrieveByIntents(SearchContext context,
                                                   List<NodeScore> targets,
                                                   int fallbackTopK,
                                                   double topKMultiplier) {
        return parallelRetriever.executeParallelRetrieval(
                context.getMainQuestion(),
                targets,
                fallbackTopK,
                topKMultiplier,
                MultiChannelRetrievalEngine.buildMetadataFilters(context));
    }
```

To:
```java
    private List<RetrievedChunk> retrieveByIntents(SearchContext context,
                                                   List<NodeScore> targets,
                                                   int fallbackTopK,
                                                   double topKMultiplier) {
        return parallelRetriever.executeParallelRetrieval(
                context.getMainQuestion(),
                targets,
                fallbackTopK,
                topKMultiplier,
                context);
    }
```

- [ ] **Step 6: Compile check**

Run: `mvn clean compile -pl bootstrap -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/VectorGlobalSearchChannel.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/IntentDirectedSearchChannel.java bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/channel/strategy/IntentParallelRetriever.java
git commit -m "$(cat <<'EOF'
fix(rbac): per-KB security_level filtering in all retrieval paths

Path B (VectorGlobal): resolves kbId per collection, builds per-KB
metadata filters. Path C (IntentDirected): passes SearchContext to
IntentParallelRetriever, resolves per-intent KB filters.

Fixes cross-KB security_level leakage (vulnerability A).
EOF
)"
```

---

### Task 6: Phase 1 Frontend — KB binding dialog add `maxSecurityLevel` dropdown

**Files:**
- Modify: `frontend/src/services/roleService.ts:23-26`
- Modify: `frontend/src/pages/admin/roles/RoleListPage.tsx:177-233`

- [ ] **Step 1: Update `RoleKbBinding` type in `roleService.ts`**

In `roleService.ts`, update the `RoleKbBinding` interface (lines 23-26):

```typescript
export interface RoleKbBinding {
  kbId: string;
  permission: "READ" | "WRITE" | "MANAGE";
  maxSecurityLevel?: number;
}
```

- [ ] **Step 2: Add `maxSecurityLevel` dropdown in RoleListPage KB dialog**

In `RoleListPage.tsx`, find the KB configuration dialog section where each KB checkbox row shows the permission dropdown (around lines 436-472). After the permission `<Select>`, add a security level `<Select>`:

In the `openKbDialog` function (around line 178-197), update the binding state to include `maxSecurityLevel`:

When loading existing bindings, map `maxSecurityLevel`:
```typescript
const mapped = currentBindings.map((b: RoleKbBinding) => ({
  kbId: b.kbId,
  permission: b.permission || "MANAGE",
  maxSecurityLevel: b.maxSecurityLevel ?? selectedRole.maxSecurityLevel ?? 0,
}));
```

In `toggleKb`, set default `maxSecurityLevel`:
```typescript
const toggleKb = (kbId: string) => {
  setKbBindings((prev) => {
    const exists = prev.find((b) => b.kbId === kbId);
    if (exists) return prev.filter((b) => b.kbId !== kbId);
    return [...prev, {
      kbId,
      permission: "MANAGE" as const,
      maxSecurityLevel: editingRole?.maxSecurityLevel ?? 0
    }];
  });
};
```

Add a setter function:
```typescript
const setKbSecurityLevel = (kbId: string, level: number) => {
  setKbBindings((prev) =>
    prev.map((b) => (b.kbId === kbId ? { ...b, maxSecurityLevel: level } : b))
  );
};
```

In the JSX for each checked KB row (after the permission Select), add:

```tsx
<Select
  value={String(binding.maxSecurityLevel ?? 0)}
  onValueChange={(v) => setKbSecurityLevel(kb.id, Number(v))}
>
  <SelectTrigger className="w-24">
    <SelectValue />
  </SelectTrigger>
  <SelectContent>
    {[0, 1, 2, 3].map((lvl) => (
      <SelectItem key={lvl} value={String(lvl)}>
        Level {lvl}
      </SelectItem>
    ))}
  </SelectContent>
</Select>
```

- [ ] **Step 3: Update `handleSaveKb` to send `maxSecurityLevel`**

The existing `handleSaveKb` already sends `kbBindings` to `setRoleKnowledgeBases`. Since `RoleKbBinding` now includes `maxSecurityLevel`, ensure the mapping sends it:

```typescript
const handleSaveKb = async () => {
  if (!editingRole) return;
  try {
    await setRoleKnowledgeBases(editingRole.id, kbBindings);
    // ... success handling
  } catch { /* ... */ }
};
```

No change needed if `kbBindings` is already of type `RoleKbBinding[]` with `maxSecurityLevel`.

- [ ] **Step 4: Verify frontend compiles**

Run: `cd frontend && npm run build`
Expected: Build success

- [ ] **Step 5: Commit**

```bash
git add frontend/src/services/roleService.ts frontend/src/pages/admin/roles/RoleListPage.tsx
git commit -m "$(cat <<'EOF'
feat(rbac): KB binding dialog shows per-KB maxSecurityLevel dropdown

SUPER_ADMIN can set 0-3 security level per KB binding. Defaults to
role's maxSecurityLevel when adding new KB.
EOF
)"
```

---

### Task 7: Phase 1 — rebuild database and verify

- [ ] **Step 1: Rebuild local database**

```bash
docker exec postgres psql -U postgres -d ragent -f /path/to/schema_pg.sql
docker exec postgres psql -U postgres -d ragent -f /path/to/init_data_pg.sql
```

Or drop and recreate:
```bash
docker exec postgres psql -U postgres -c "DROP DATABASE IF EXISTS ragent;"
docker exec postgres psql -U postgres -c "CREATE DATABASE ragent;"
docker exec postgres psql -U postgres -d ragent -f /docker-entrypoint-initdb.d/schema_pg.sql
docker exec postgres psql -U postgres -d ragent -f /docker-entrypoint-initdb.d/init_data_pg.sql
```

- [ ] **Step 2: Full build**

Run: `mvn clean install -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Verify column exists**

```bash
docker exec postgres psql -U postgres -d ragent -c "\d t_role_kb_relation"
```

Expected: `max_security_level` column visible with type `smallint`, default `0`.

---

## Phase 2: Functional Fixes

### Task 8: DEPT_ADMIN endpoint access (Vulnerability B)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java:69-101`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/SysDeptController.java:41-71`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`

- [ ] **Step 1: Helper — add `checkAnyAdminAccess()` to KbAccessServiceImpl**

In `KbAccessServiceImpl.java`, add a helper:

```java
    private void checkAnyAdminAccess() {
        LoginUser current = UserContext.get();
        if (current == null) {
            throw new ClientException("未登录");
        }
        if (!isSuperAdmin() && !isDeptAdmin()) {
            throw new ClientException("需要管理员权限");
        }
    }
```

Also expose it on the interface `KbAccessService.java`:

```java
    /** 校验当前用户是 SUPER_ADMIN 或 DEPT_ADMIN，否则抛异常 */
    void checkAnyAdminAccess();
```

- [ ] **Step 2: RoleController — open `GET /role` to AnyAdmin**

In `RoleController.java`, change `listRoles()` (lines 69-73):

```java
    @GetMapping("/role")
    public Result<List<RoleDO>> listRoles() {
        kbAccessService.checkAnyAdminAccess();
        return Results.success(roleService.listRoles());
    }
```

Remove `@SaCheckRole("SUPER_ADMIN")` from this method.

- [ ] **Step 3: RoleController — open `GET /user/{userId}/roles` to AnyAdmin with dept check**

In `RoleController.java`, change `getUserRoles()` (lines 97-101):

```java
    @GetMapping("/user/{userId}/roles")
    public Result<List<RoleDO>> getUserRoles(@PathVariable("userId") String userId) {
        kbAccessService.checkAnyAdminAccess();
        kbAccessService.checkUserManageAccess(userId);
        return Results.success(roleService.getUserRoles(userId));
    }
```

Remove `@SaCheckRole("SUPER_ADMIN")` from this method.

- [ ] **Step 4: SysDeptController — class-level to method-level annotations**

In `SysDeptController.java`, remove the class-level `@SaCheckRole("SUPER_ADMIN")` (line 41).

Add method-level annotations:

```java
@RestController
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService sysDeptService;
    private final KbAccessService kbAccessService;

    @GetMapping("/sys-dept")
    public Result<List<SysDeptVO>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        kbAccessService.checkAnyAdminAccess();
        return Results.success(sysDeptService.list(keyword));
    }

    @GetMapping("/sys-dept/{id}")
    public Result<SysDeptVO> getById(@PathVariable("id") String id) {
        kbAccessService.checkAnyAdminAccess();
        return Results.success(sysDeptService.getById(id));
    }

    @SaCheckRole("SUPER_ADMIN")
    @PostMapping("/sys-dept")
    public Result<String> create(@RequestBody SysDeptCreateRequest request) {
        return Results.success(sysDeptService.create(request));
    }

    @SaCheckRole("SUPER_ADMIN")
    @PutMapping("/sys-dept/{id}")
    public Result<Void> update(@PathVariable("id") String id, @RequestBody SysDeptUpdateRequest request) {
        sysDeptService.update(id, request);
        return Results.success();
    }

    @SaCheckRole("SUPER_ADMIN")
    @DeleteMapping("/sys-dept/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        sysDeptService.delete(id);
        return Results.success();
    }
}
```

Inject `KbAccessService` (add to constructor).

- [ ] **Step 5: Compile check**

Run: `mvn clean compile -pl bootstrap -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/RoleController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/controller/SysDeptController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "$(cat <<'EOF'
fix(rbac): open GET /role, /user/{id}/roles, /sys-dept to DEPT_ADMIN

GET endpoints now use checkAnyAdminAccess() instead of @SaCheckRole.
SysDeptController moves from class-level to method-level annotations —
read ops open to AnyAdmin, CUD stays SUPER_ADMIN only.

Fixes vulnerability B: DEPT_ADMIN can now load role/dept data in
user management dialogs.
EOF
)"
```

---

### Task 9: Privilege escalation fix (Vulnerability D) + deptId guard (Vulnerability F)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java:116-148`

- [ ] **Step 1: Add `validateRoleAssignment()` to KbAccessService interface**

```java
    /**
     * 校验 DEPT_ADMIN 分配角色的合法性：
     * - 不可分配 SUPER_ADMIN 角色
     * - 不可分配 DEPT_ADMIN 角色
     * - 不可分配 maxSecurityLevel > 自身天花板的角色
     * SUPER_ADMIN 不受限制。
     */
    void validateRoleAssignment(List<String> roleIds);
```

- [ ] **Step 2: Implement `validateRoleAssignment()`**

In `KbAccessServiceImpl.java`:

```java
    @Override
    public void validateRoleAssignment(List<String> roleIds) {
        if (isSuperAdmin() || roleIds == null || roleIds.isEmpty()) {
            return;
        }
        int currentCeiling = UserContext.get().getMaxSecurityLevel();
        List<RoleDO> roles = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds));
        for (RoleDO role : roles) {
            if (RoleType.SUPER_ADMIN.name().equals(role.getRoleType())) {
                throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
            }
            if (RoleType.DEPT_ADMIN.name().equals(role.getRoleType())) {
                throw new ClientException("DEPT_ADMIN 不可分配 DEPT_ADMIN 角色");
            }
            if (role.getMaxSecurityLevel() != null && role.getMaxSecurityLevel() > currentCeiling) {
                throw new ClientException("不可分配超过自身安全等级上限的角色");
            }
        }
    }
```

- [ ] **Step 3: Update `checkCreateUserAccess()` to use `validateRoleAssignment()`**

In `KbAccessServiceImpl.java`, replace the SUPER_ADMIN-only check in `checkCreateUserAccess()` (around lines 262-271):

Find the existing block that only checks SUPER_ADMIN roles:
```java
        if (roleIds != null && !roleIds.isEmpty()) {
            long superRoleCount = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class)
                            .in(RoleDO::getId, roleIds)
                            .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
            ).size();
            if (superRoleCount > 0) {
                throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
            }
        }
```

Replace with:
```java
        validateRoleAssignment(roleIds);
```

- [ ] **Step 4: Update `checkAssignRolesAccess()` similarly**

In `KbAccessServiceImpl.java`, replace the SUPER_ADMIN-only check in `checkAssignRolesAccess()` (around lines 305-314):

Find:
```java
        if (newRoleIds != null && !newRoleIds.isEmpty()) {
            long superRoleCount = roleMapper.selectList(
                    Wrappers.lambdaQuery(RoleDO.class)
                            .in(RoleDO::getId, newRoleIds)
                            .eq(RoleDO::getRoleType, RoleType.SUPER_ADMIN.name())
            ).size();
            if (superRoleCount > 0) {
                throw new ClientException("DEPT_ADMIN 不可分配 SUPER_ADMIN 角色");
            }
        }
```

Replace with:
```java
        validateRoleAssignment(newRoleIds);
```

- [ ] **Step 5: Add deptId change guard to `UserServiceImpl.update()`**

In `UserServiceImpl.java`, find the deptId block in `update()` (around line 143-145):

Replace:
```java
        if (requestParam.getDeptId() != null) {
            record.setDeptId(requestParam.getDeptId());
        }
```

With:
```java
        if (requestParam.getDeptId() != null
                && !requestParam.getDeptId().equals(record.getDeptId())
                && !kbAccessService.isSuperAdmin()) {
            throw new ClientException("部门变更仅超级管理员可操作");
        }
        if (requestParam.getDeptId() != null && kbAccessService.isSuperAdmin()) {
            record.setDeptId(requestParam.getDeptId());
        }
```

- [ ] **Step 6: Compile check**

Run: `mvn clean compile -pl bootstrap -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/UserServiceImpl.java
git commit -m "$(cat <<'EOF'
fix(rbac): block DEPT_ADMIN privilege escalation + deptId change

validateRoleAssignment blocks DEPT_ADMIN from assigning SUPER_ADMIN,
DEPT_ADMIN, or above-ceiling roles. deptId change restricted to
SUPER_ADMIN only.

Fixes vulnerabilities D + F.
EOF
)"
```

---

### Task 10: KB-centric sharing API (Vulnerability C)

**Files:**
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/RoleService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java`
- Modify: `bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java`

- [ ] **Step 1: Add `checkKbRoleBindingAccess()` to KbAccessService**

In `KbAccessService.java`:

```java
    /**
     * 校验当前用户是否有权管理指定 KB 的角色绑定。
     * SUPER_ADMIN 任意 KB；DEPT_ADMIN 仅 kb.dept_id == self.dept_id。
     */
    void checkKbRoleBindingAccess(String kbId);
```

In `KbAccessServiceImpl.java`:

```java
    @Override
    public void checkKbRoleBindingAccess(String kbId) {
        if (isSuperAdmin()) {
            return;
        }
        if (!isDeptAdmin()) {
            throw new ClientException("需要管理员权限");
        }
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new ClientException("知识库不存在");
        }
        LoginUser current = UserContext.requireUser();
        if (!Objects.equals(current.getDeptId(), kb.getDeptId())) {
            throw new ClientException("DEPT_ADMIN 只能管理本部门知识库的角色绑定");
        }
    }
```

- [ ] **Step 2: Add service methods for KB-centric bindings**

In `RoleService.java`:

```java
    /** 获取指定 KB 的所有角色绑定 */
    List<KbRoleBindingVO> getKbRoleBindings(String kbId);

    /** 全量覆盖指定 KB 的角色绑定（仅影响此 KB，不影响其他 KB） */
    void setKbRoleBindings(String kbId, List<KbRoleBindingRequest> bindings);
```

Add VO and request classes as inner classes in `KnowledgeBaseController.java` (or a separate DTO file — follow existing pattern of inner classes in controller):

```java
    @Data
    public static class KbRoleBindingVO {
        private String roleId;
        private String roleName;
        private String roleType;
        private String permission;
        private Integer maxSecurityLevel;
    }

    @Data
    public static class KbRoleBindingRequest {
        private String roleId;
        private String permission;
        private Integer maxSecurityLevel;
    }
```

- [ ] **Step 3: Implement `getKbRoleBindings()`**

In `RoleServiceImpl.java`:

```java
    @Override
    public List<KnowledgeBaseController.KbRoleBindingVO> getKbRoleBindings(String kbId) {
        List<RoleKbRelationDO> relations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getKbId, kbId));
        if (relations.isEmpty()) {
            return List.of();
        }
        List<String> roleIds = relations.stream().map(RoleKbRelationDO::getRoleId).toList();
        Map<String, RoleDO> roleMap = roleMapper.selectList(
                Wrappers.lambdaQuery(RoleDO.class).in(RoleDO::getId, roleIds))
                .stream().collect(Collectors.toMap(RoleDO::getId, r -> r));

        return relations.stream().map(rel -> {
            KnowledgeBaseController.KbRoleBindingVO vo = new KnowledgeBaseController.KbRoleBindingVO();
            vo.setRoleId(rel.getRoleId());
            vo.setPermission(rel.getPermission());
            vo.setMaxSecurityLevel(rel.getMaxSecurityLevel());
            RoleDO role = roleMap.get(rel.getRoleId());
            if (role != null) {
                vo.setRoleName(role.getName());
                vo.setRoleType(role.getRoleType());
            }
            return vo;
        }).toList();
    }
```

Add import: `import java.util.stream.Collectors;`

- [ ] **Step 4: Implement `setKbRoleBindings()`**

In `RoleServiceImpl.java`:

```java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setKbRoleBindings(String kbId,
                                  List<KnowledgeBaseController.KbRoleBindingRequest> bindings) {
        Set<String> affectedUserIds = new HashSet<>();

        // ① 删除前先收集旧绑定涉及的用户（被移除绑定的用户也需要驱逐缓存）
        List<RoleKbRelationDO> oldRelations = roleKbRelationMapper.selectList(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getKbId, kbId));
        Set<String> oldRoleIds = oldRelations.stream()
                .map(RoleKbRelationDO::getRoleId).collect(Collectors.toSet());
        if (!oldRoleIds.isEmpty()) {
            userRoleMapper.selectList(
                    Wrappers.lambdaQuery(UserRoleDO.class).in(UserRoleDO::getRoleId, oldRoleIds))
                    .forEach(ur -> affectedUserIds.add(ur.getUserId()));
        }

        // ② 删除此 KB 的所有绑定
        roleKbRelationMapper.delete(
                Wrappers.lambdaQuery(RoleKbRelationDO.class).eq(RoleKbRelationDO::getKbId, kbId));

        // ③ 写入新绑定，同时收集新绑定涉及的用户
        if (bindings != null && !bindings.isEmpty()) {
            for (KnowledgeBaseController.KbRoleBindingRequest binding : bindings) {
                RoleDO role = roleMapper.selectById(binding.getRoleId());
                if (role == null) continue;

                int roleCeiling = (role.getMaxSecurityLevel() != null) ? role.getMaxSecurityLevel() : 0;
                int level = (binding.getMaxSecurityLevel() != null)
                        ? Math.min(binding.getMaxSecurityLevel(), roleCeiling)
                        : roleCeiling;

                // DEPT_ADMIN 额外校验：不超自身天花板
                if (!kbAccessService.isSuperAdmin()) {
                    int selfCeiling = UserContext.get().getMaxSecurityLevel();
                    if (level > selfCeiling) {
                        throw new ClientException("不可设置超过自身安全等级上限的绑定");
                    }
                }

                RoleKbRelationDO relation = RoleKbRelationDO.builder()
                        .roleId(binding.getRoleId())
                        .kbId(kbId)
                        .permission(binding.getPermission() != null ? binding.getPermission() : "READ")
                        .maxSecurityLevel(level)
                        .build();
                roleKbRelationMapper.insert(relation);

                // 收集新绑定涉及的用户
                userRoleMapper.selectList(
                        Wrappers.lambdaQuery(UserRoleDO.class)
                                .eq(UserRoleDO::getRoleId, binding.getRoleId()))
                        .forEach(ur -> affectedUserIds.add(ur.getUserId()));
            }
        }

        // ④ 统一驱逐旧+新两侧所有受影响用户的缓存
        affectedUserIds.forEach(kbAccessService::evictCache);
    }
```

Add import: `import java.util.HashSet;`

- [ ] **Step 5: Add controller endpoints**

In `KnowledgeBaseController.java`, add:

```java
    /**
     * 获取知识库的角色绑定列表
     */
    @GetMapping("/knowledge-base/{kb-id}/role-bindings")
    public Result<List<KbRoleBindingVO>> getKbRoleBindings(@PathVariable("kb-id") String kbId) {
        kbAccessService.checkKbRoleBindingAccess(kbId);
        return Results.success(roleService.getKbRoleBindings(kbId));
    }

    /**
     * 全量覆盖知识库的角色绑定
     */
    @PutMapping("/knowledge-base/{kb-id}/role-bindings")
    public Result<Void> setKbRoleBindings(@PathVariable("kb-id") String kbId,
                                          @RequestBody List<KbRoleBindingRequest> bindings) {
        kbAccessService.checkKbRoleBindingAccess(kbId);
        roleService.setKbRoleBindings(kbId, bindings);
        return Results.success();
    }
```

Inject `RoleService` in `KnowledgeBaseController`:

```java
    private final RoleService roleService;
```

- [ ] **Step 6: Compile check**

Run: `mvn clean compile -pl bootstrap -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add bootstrap/src/main/java/com/nageoffer/ai/ragent/knowledge/controller/KnowledgeBaseController.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/RoleService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/RoleServiceImpl.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
git commit -m "$(cat <<'EOF'
feat(rbac): KB-centric role binding API for cross-dept sharing

GET/PUT /knowledge-base/{kbId}/role-bindings — SUPER_ADMIN any KB,
DEPT_ADMIN own-dept KBs only. Scoped to single KB, doesn't affect
other KB bindings. Validates maxSecurityLevel against role ceiling
and operator ceiling.

Fixes vulnerability C.
EOF
)"
```

---

### Task 11: Frontend — permissions + routing for DEPT_ADMIN

**Files:**
- Modify: `frontend/src/utils/permissions.ts:19,44-50`
- Modify: `frontend/src/router.tsx:145-151`

- [ ] **Step 1: Update `permissions.ts` — DEPT_ADMIN can see roles menu**

In `permissions.ts`, update `DEPT_VISIBLE` (line 19):

```typescript
const DEPT_VISIBLE: AdminMenuId[] = ["dashboard", "knowledge", "users", "roles"];
```

Update `canAssignRole` in `getPermissions()` (around line 44-50). Find the `canAssignRole` function and update to exclude both SUPER_ADMIN and DEPT_ADMIN for non-super users:

```typescript
    canAssignRole: (role: { roleType: string }) => {
      if (user?.isSuperAdmin) return true;
      // DEPT_ADMIN cannot assign SUPER_ADMIN or DEPT_ADMIN roles
      return role.roleType !== "SUPER_ADMIN" && role.roleType !== "DEPT_ADMIN";
    },
```

- [ ] **Step 2: Update `router.tsx` — roles route uses RequireMenuAccess**

In `router.tsx`, find the roles route (around line 147):

Change from:
```tsx
{ path: "roles", element: <RequireSuperAdmin><RoleListPage /></RequireSuperAdmin> },
```

To:
```tsx
{ path: "roles", element: <RequireMenuAccess menuId="roles"><RoleListPage /></RequireMenuAccess> },
```

- [ ] **Step 3: Verify frontend compiles**

Run: `cd frontend && npm run build`
Expected: Build success

- [ ] **Step 4: Commit**

```bash
git add frontend/src/utils/permissions.ts frontend/src/router.tsx
git commit -m "$(cat <<'EOF'
feat(rbac): DEPT_ADMIN can access /admin/roles (read-only)

permissions.ts adds "roles" to DEPT_VISIBLE, canAssignRole excludes
DEPT_ADMIN role type. router.tsx switches roles route from
RequireSuperAdmin to RequireMenuAccess.
EOF
)"
```

---

### Task 12: Frontend — RoleListPage DEPT_ADMIN read-only + UserListPage deptId lock

**Files:**
- Modify: `frontend/src/pages/admin/roles/RoleListPage.tsx`
- Modify: `frontend/src/pages/admin/users/UserListPage.tsx`

- [ ] **Step 1: RoleListPage — DEPT_ADMIN read-only mode**

In `RoleListPage.tsx`, add permission check at the top of the component:

```typescript
const { isSuperAdmin } = usePermissions();
```

Hide all mutation buttons when `!isSuperAdmin`:
- **Create button** (header): wrap with `{isSuperAdmin && <Button>...</Button>}`
- **KB config button** (table row): wrap with `{isSuperAdmin && ...}`
- **Edit button** (table row): wrap with `{isSuperAdmin && ...}`
- **Delete button** (table row): wrap with `{isSuperAdmin && ...}`

Skip `getRoleKnowledgeBases` calls in data loading when `!isSuperAdmin` (DEPT_ADMIN shouldn't see KB binding counts from the restricted endpoint):

```typescript
// In loadRoles, conditionally load KB counts
if (isSuperAdmin) {
  // existing KB count loading logic
} else {
  setKbCounts({});
}
```

- [ ] **Step 2: UserListPage — deptId disabled for DEPT_ADMIN**

In `UserListPage.tsx`, the `deptLocked` logic already exists (around line 116-117):

```typescript
const deptLocked = !isSuperAdmin && isDeptAdmin;
```

Verify that in the edit dialog, the department field is disabled when `deptLocked`:
- In the edit dialog, find the department `<Select>` and ensure `disabled={deptLocked}` is applied
- For edit mode specifically, the deptId should be read-only (the current code may already handle this for create mode but not for edit)

If not already present, add to the department Select in edit mode:

```tsx
<Select
  value={formData.deptId}
  onValueChange={(v) => setFormData((f) => ({ ...f, deptId: v }))}
  disabled={deptLocked || dialogMode === "edit"}
>
```

Actually, per the design: DEPT_ADMIN sees deptId disabled in BOTH create and edit. For edit, SUPER_ADMIN can change it but DEPT_ADMIN cannot. So:

```tsx
disabled={deptLocked}
```

is sufficient — it's already true for DEPT_ADMIN in both modes.

- [ ] **Step 3: Verify frontend compiles**

Run: `cd frontend && npm run build`
Expected: Build success

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/admin/roles/RoleListPage.tsx frontend/src/pages/admin/users/UserListPage.tsx
git commit -m "$(cat <<'EOF'
feat(rbac): RoleListPage read-only for DEPT_ADMIN + deptId lock

DEPT_ADMIN sees role list but no create/edit/delete/KB-config buttons.
UserListPage department field disabled for DEPT_ADMIN in both create
and edit dialogs.
EOF
)"
```

---

### Task 13: Frontend — KB sharing tab

**Files:**
- Create: `frontend/src/pages/admin/knowledge/KbSharingTab.tsx`
- Modify: `frontend/src/services/knowledgeService.ts`
- Modify: Knowledge document page (integrate tab)

- [ ] **Step 1: Add API functions to `knowledgeService.ts`**

In `knowledgeService.ts`, add at the end:

```typescript
// KB 角色绑定管理
export interface KbRoleBindingVO {
  roleId: string;
  roleName: string;
  roleType: string;
  permission: string;
  maxSecurityLevel: number;
}

export interface KbRoleBindingRequest {
  roleId: string;
  permission: string;
  maxSecurityLevel?: number;
}

export async function getKbRoleBindings(kbId: string): Promise<KbRoleBindingVO[]> {
  return api.get<KbRoleBindingVO[], KbRoleBindingVO[]>(`/knowledge-base/${kbId}/role-bindings`);
}

export async function setKbRoleBindings(
  kbId: string,
  bindings: KbRoleBindingRequest[]
): Promise<void> {
  await api.put(`/knowledge-base/${kbId}/role-bindings`, bindings);
}
```

- [ ] **Step 2: Create `KbSharingTab.tsx` component**

Create `frontend/src/pages/admin/knowledge/KbSharingTab.tsx`:

```tsx
import { useEffect, useState } from "react";
import { Plus, Trash2, Save } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { getRoles, RoleItem } from "@/services/roleService";
import {
  getKbRoleBindings,
  setKbRoleBindings,
  KbRoleBindingVO,
  KbRoleBindingRequest,
} from "@/services/knowledgeService";

interface Props {
  kbId: string;
}

const PERMISSION_OPTIONS = ["READ", "WRITE", "MANAGE"] as const;

export default function KbSharingTab({ kbId }: Props) {
  const [bindings, setBindings] = useState<KbRoleBindingRequest[]>([]);
  const [allRoles, setAllRoles] = useState<RoleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const [roles, existing] = await Promise.all([getRoles(), getKbRoleBindings(kbId)]);
        setAllRoles(roles);
        setBindings(
          existing.map((b: KbRoleBindingVO) => ({
            roleId: b.roleId,
            permission: b.permission,
            maxSecurityLevel: b.maxSecurityLevel,
          }))
        );
      } catch {
        toast.error("加载失败");
      } finally {
        setLoading(false);
      }
    })();
  }, [kbId]);

  const addBinding = () => {
    const usedIds = new Set(bindings.map((b) => b.roleId));
    const available = allRoles.find((r) => !usedIds.has(r.id));
    if (!available) {
      toast.info("所有角色已添加");
      return;
    }
    setBindings((prev) => [
      ...prev,
      { roleId: available.id, permission: "READ", maxSecurityLevel: 0 },
    ]);
  };

  const removeBinding = (idx: number) => {
    setBindings((prev) => prev.filter((_, i) => i !== idx));
  };

  const updateBinding = (idx: number, field: string, value: string | number) => {
    setBindings((prev) =>
      prev.map((b, i) => (i === idx ? { ...b, [field]: value } : b))
    );
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await setKbRoleBindings(kbId, bindings);
      toast.success("保存成功");
    } catch {
      toast.error("保存失败");
    } finally {
      setSaving(false);
    }
  };

  const getRoleName = (roleId: string) =>
    allRoles.find((r) => r.id === roleId)?.name ?? roleId;

  if (loading) return <div className="p-4 text-muted-foreground">加载中...</div>;

  return (
    <div className="space-y-4 p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-medium">共享管理</h3>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={addBinding}>
            <Plus className="mr-1 h-4 w-4" /> 添加角色
          </Button>
          <Button size="sm" onClick={handleSave} disabled={saving}>
            <Save className="mr-1 h-4 w-4" /> {saving ? "保存中..." : "保存"}
          </Button>
        </div>
      </div>

      {bindings.length === 0 ? (
        <p className="text-sm text-muted-foreground">暂无角色绑定</p>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="pb-2">角色</th>
              <th className="pb-2">权限</th>
              <th className="pb-2">安全等级</th>
              <th className="pb-2 w-16"></th>
            </tr>
          </thead>
          <tbody>
            {bindings.map((b, idx) => (
              <tr key={idx} className="border-b">
                <td className="py-2">
                  <Select
                    value={b.roleId}
                    onValueChange={(v) => updateBinding(idx, "roleId", v)}
                  >
                    <SelectTrigger className="w-48">
                      <SelectValue>{getRoleName(b.roleId)}</SelectValue>
                    </SelectTrigger>
                    <SelectContent>
                      {allRoles.map((r) => (
                        <SelectItem key={r.id} value={r.id}>
                          {r.name} ({r.roleType})
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </td>
                <td className="py-2">
                  <Select
                    value={b.permission}
                    onValueChange={(v) => updateBinding(idx, "permission", v)}
                  >
                    <SelectTrigger className="w-32">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {PERMISSION_OPTIONS.map((p) => (
                        <SelectItem key={p} value={p}>{p}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </td>
                <td className="py-2">
                  <Select
                    value={String(b.maxSecurityLevel ?? 0)}
                    onValueChange={(v) => updateBinding(idx, "maxSecurityLevel", Number(v))}
                  >
                    <SelectTrigger className="w-24">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {[0, 1, 2, 3].map((lvl) => (
                        <SelectItem key={lvl} value={String(lvl)}>
                          Level {lvl}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </td>
                <td className="py-2">
                  <Button variant="ghost" size="icon" onClick={() => removeBinding(idx)}>
                    <Trash2 className="h-4 w-4 text-destructive" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Integrate KbSharingTab into KB detail/document page**

Find the knowledge base document management page (likely `KnowledgeDocumentPage.tsx` or similar) and add a tab for "共享管理" that renders `<KbSharingTab kbId={kbId} />`.

This integration depends on the existing page structure — if there's a tab system, add a new tab. If not, add a collapsible section or a button that opens the sharing panel.

At minimum, add the import and render:
```tsx
import KbSharingTab from "./KbSharingTab";

// In the page, after the document list or as a new tab:
{(isSuperAdmin || isDeptAdmin) && <KbSharingTab kbId={kbId} />}
```

- [ ] **Step 4: Verify frontend compiles**

Run: `cd frontend && npm run build`
Expected: Build success

- [ ] **Step 5: Commit**

```bash
git add frontend/src/services/knowledgeService.ts frontend/src/pages/admin/knowledge/KbSharingTab.tsx
git commit -m "$(cat <<'EOF'
feat(rbac): KB sharing tab for role binding management

KbSharingTab component lets admins add/remove/edit role bindings
per KB with permission and maxSecurityLevel controls. Calls
GET/PUT /knowledge-base/{kbId}/role-bindings.
EOF
)"
```

---

## Verification Matrix

After all tasks complete, verify these scenarios:

### Phase 1 Verification

| # | Scenario | Expected | How to verify |
|---|----------|----------|---------------|
| 1 | User has role A (kb=KB1, level=3) + role B (kb=KB2, level=1), searches KB2 | Only level<=1 docs from KB2 | RAG chat in KB2 space, check trace |
| 2 | Same user searches KB1 | level<=3 docs from KB1 | RAG chat in KB1 space |
| 3 | SUPER_ADMIN searches any KB | All docs (level=3) | RAG chat |
| 4 | SUPER_ADMIN sets KB binding level=2 in role management | Value persists in DB | `GET /role/{id}/knowledge-bases` returns level=2 |
| 5 | Cache eviction on binding change | Next search uses new level | Modify binding, verify retrieval changes |

### Phase 2 Verification

| # | Scenario | Expected | How to verify |
|---|----------|----------|---------------|
| 6 | DEPT_ADMIN opens user management → create user | Role list loads (no 403), SUPER_ADMIN/DEPT_ADMIN roles hidden | UI check |
| 7 | DEPT_ADMIN opens /admin/roles | Read-only list, no create/edit/delete buttons | UI check |
| 8 | DEPT_ADMIN creates user with DEPT_ADMIN role | Backend rejects "不可分配 DEPT_ADMIN 角色" | `POST /users` with DEPT_ADMIN roleId |
| 9 | DEPT_ADMIN creates user with ceiling=3 role, self ceiling=2 | Backend rejects "不可分配超过自身安全等级上限的角色" | `POST /users` |
| 10 | DEPT_ADMIN edits user, changes deptId | Backend rejects "部门变更仅超级管理员可操作" | `PUT /users/{id}` |
| 11 | DEPT_ADMIN opens own-dept KB sharing tab | Loads bindings | UI check |
| 12 | DEPT_ADMIN opens other-dept KB sharing tab | Rejects | `GET /knowledge-base/{kbId}/role-bindings` |
| 13 | DEPT_ADMIN sets binding level=3, self ceiling=2 | Backend rejects | `PUT /knowledge-base/{kbId}/role-bindings` |
| 14 | DEPT_ADMIN opens /admin/departments | Route blocked (RequireSuperAdmin) | UI check |
