# Key Gotchas — 项目历史坑点与修复记录

> 把曾经踩过的坑 + 配套规则集中在此文件，避免复犯。动手前按主题检索；修完新坑立即回填。
>
> 每条格式：**症状** → **根因** → **规则 / 修复**。
>
> 关联：CLAUDE.md 只保留指向本文件的一句话（渐进式披露，控上下文）。

---

## 1. 后端 Java / Lombok / Spring Boot

- **SSE streaming is async**: `ChatClient.streamChat()` returns immediately via `StreamAsyncExecutor`; `StreamCallback` methods run on `modelStreamExecutor` thread pool. Do NOT read ThreadLocal values set by streaming callbacks from the calling thread — they won't be there yet.
- **OpenAI SSE usage frame order**: `finish_reason` frame → usage frame (empty choices) → `[DONE]`. Loop must break on `[DONE]` (`streamEnded`), not on `finish_reason` (`finished`), otherwise usage data is missed.
- **RagTraceContext ThreadLocal cleared early**: `ChatRateLimitAspect.finally` clears context before streaming completes. Capture `traceId` etc. in handler constructor, not at callback time.
- **`extra_data TEXT` JSON pattern (Gson 读 / Jackson 写)**: Used in `t_rag_trace_run` and `t_rag_trace_node` for extensible metrics without schema migration. Query path uses Gson (`getAsInt()` coerces both `5228` and `5228.0`). **Merge/write path MUST use Jackson** — `gson.fromJson(.., Map.class)` parses all numbers as `Double`, so round-tripping `{"totalTokens":5228}` emits `"5228.0"`, which breaks the Dashboard's `CAST(... AS INTEGER)` SQL. `sumTokensInWindow` defensively does `CAST(CAST(... AS NUMERIC) AS BIGINT)` for legacy rows.
- **`@Data @Builder` alone breaks Jackson deserialization**: Lombok's interaction between `@RequiredArgsConstructor` (from `@Data`) and `@Builder`'s all-args constructor doesn't reliably expose a public no-arg constructor. Any class going through Jackson (`ObjectMapper.readValue`, Redis cache via `GenericJackson2`, `@RequestBody`, MQ events) must add explicit **`@NoArgsConstructor @AllArgsConstructor`**. `IntentNode` hit this — every request logged `Cannot construct instance of IntentNode` and fell back to `IntentTreeFactory` rebuild. DO classes using MyBatis Plus are exempt (MP uses its own reflection path).
- **`@RequiredArgsConstructor` + `@Qualifier` is SAFE**: `lombok.config` has `copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier`, so field-level `@Qualifier("beanName")` IS copied to the Lombok-generated constructor parameter. Explicit constructors are NOT required for ambiguous bean types (supersedes previous guidance).
- **`-parameters` flag: IntelliJ vs Maven divergence**: IntelliJ adds `-parameters` automatically; Maven doesn't (compiler-plugin `<parameters>` isn't set). Without it, `@RequestParam`/`@PathVariable` without explicit `value=` throw `IllegalArgumentException` at runtime. **Rule**: always write explicit `value="..."` on all `@RequestParam`/`@PathVariable` annotations.
- **Sweep for bare annotations** (run after adding any controller): `grep -rEn '@(RequestParam|PathVariable)\s+(required\s*=[^,)]+,\s*)?[A-Z]' --include="*Controller.java" bootstrap/src/main/java | grep -v 'value\s*='`
- **Spotless runs on every `mvn compile`**, not just `mvn spotless:apply`: the `default` execution is wired to apply mode. A routine `mvn -pl bootstrap clean compile` can silently reformat unrelated files (e.g., collapsing an explicit constructor into `@RequiredArgsConstructor`). Always `git status` after compile and commit reformats separately.
- **API signature changes require full-text search**: When backend adds/changes required parameters (e.g., adding `@RequestParam String kbId`), grep ALL frontend callers — not just the ones listed in the plan. Missing callers cause runtime 400 errors.
- **Java 17 baseline — no pattern matching for switch**: `switch (x) { case Foo(var y) -> ... }` (record pattern, JEP 440) and `switch (x) { case String s -> ... }` (type pattern, JEP 441) are Java 21 finalized, NOT enabled here. Use `if (x instanceof Foo y)` (JEP 394, Java 16+) instead. Sweep: `grep -rEn 'case\s+\w+\.\w+\s*\(' bootstrap/src/main/java` should be zero.
- **DO→VO via `BeanUtil.toBean` is reflection by field name**: `KnowledgeDocumentServiceImpl.get/page` (and similar read endpoints) use `BeanUtil.toBean(do, Vo.class)`. Adding a new field on the DO is NOT enough — the matching field must exist on the VO or the frontend gets `undefined`. Always sweep the VO classes when adding a column.
- **`BeanUtil.toBean` can't populate cross-table fields like `deptName`**: `deptId` copies fine (same column on the DO); `deptName` lives in `sys_dept` so it's always `null` after `toBean`. Any VO exposing `deptName` (`KnowledgeBaseVO` / `KbRoleBindingVO` / `AccessRoleVO` / `RoleUsageVO`) must enrich in service layer: `sysDeptMapper.selectBatchIds(deptIds).forEach(d -> map.put(d.getId(), d.getDeptName()))` then `vo.setDeptName(map.get(do.getDeptId()))`. Silent failure shows as `部门：—` in UI.
- **Unmapped Spring route = HTTP 200 + `{"code":"B000001","message":"系统执行出错"}`**, not 404. When verifying an endpoint was deleted/renamed via `curl`, this body is the "route is gone" signal — easy to mistake for a working endpoint returning empty. Distinct from Sa-Token/ClientException codes (also HTTP 200 but with semantic codes).

---

## 2. 数据库 / Schema / MyBatis Plus

- **Database access via Docker**: `docker exec postgres psql -U postgres -d ragent -c "SQL"`. User is `postgres`, not `ragent`.
- **Two schema files maintained independently**: `schema_pg.sql` (clean DDL) and `full_schema_pg.sql` (pg_dump style) must BOTH be updated when changing table schemas. Forgetting one causes init/upgrade divergence.
- **`full_schema_pg.sql` COMMENT placement**: COMMENTs are in separate blocks (not inline after CREATE TABLE). Each has its own `-- Name: COLUMN ...; Type: COMMENT` header block. When adding columns, add the COMMENT in a new block near existing comments for the same table.
- **@TableLogic auto-filter**: MyBatis Plus entities with `@TableLogic` on `deleted` field automatically append `WHERE deleted=0`. Do NOT add redundant `.eq(::getDeleted, 0)` conditions in queries.
- **软删表的 UNIQUE 约束必须带 `deleted` 列**：`@TableLogic` 表在业务列加 `UNIQUE (col)` 会和软删语义冲突 —— 软删行仍占该 `col`，"删后重建同名"会被 DB 直接拒绝；但 App 层的 `.eq(::getDeleted, 0)` 查重会通过（认为可复用），两层不一致。正确写法 `UNIQUE (col, deleted)`，参考 `t_ingestion_pipeline.uk_ingestion_pipeline_name`。`t_knowledge_base.uk_collection_name` 于 2026-04-19 按此修复（见 `upgrade_v1.4_to_v1.5.sql`）。新建带软删的表一律套用此模式。
- **PostgreSQL folds unquoted identifiers to lowercase**: `selectMaps` with `.select("kb_id AS kbId")` produces map key `kbid`, not `kbId`. Always use snake_case aliases (`AS kb_id`, `AS doc_count`) and `row.get("kb_id")` — never camelCase.
- **Entity column additions require DB migration before startup**: Adding fields to MyBatis Plus `@TableName` entities without running the corresponding `upgrade_*.sql` script causes `PSQLException: column does not exist` at runtime. Always pair entity field additions with a migration script in `resources/database/` and remind to execute it.
- **pgvector extension not installed on `postgres:16` image**: `schema_pg.sql` contains `embedding vector(1536)` for `t_knowledge_vector`. `CREATE EXTENSION vector` + subsequent CREATE TABLE will error during init — **expected and safe** when `rag.vector.type=opensearch` (or `milvus`). The table simply won't exist; all other tables create successfully.
- **Table naming convention is inconsistent**: Most tables use `t_` prefix (`t_user`, `t_role`, `t_knowledge_base`), but the department table is `sys_dept` (with entity `SysDeptDO`, mapper `SysDeptMapper`). When searching for department-related code, grep for `sys_dept` / `SysDept`, NOT `t_department` / `Dept`.
- **Seed data is not blank**: `init_data_pg.sql` wires admin user with `dept_id='1'` (GLOBAL), role `超级管理员` (`role_type=SUPER_ADMIN`, `max_security_level=3`), and `t_user_role` linking them. A fresh DB with `schema_pg.sql + init_data_pg.sql` already has a fully-privileged admin — not a "no dept / no role / max=0" user.
- **`@TableField(typeHandler = ...)` only fires in entity-based CRUD** (`insert` / `updateById` / `selectById`). `LambdaUpdateWrapper.set(col, val)` binds via default JDBC type mapping (`String → VARCHAR`) regardless of annotation — for jsonb columns (e.g. `t_knowledge_document.chunk_config`) this raises `column X is of type jsonb but expression is of type character varying`. Fix: use `updateById(entity)` for non-null values; secondary `LambdaUpdateWrapper.set(col, null)` for NULL clearing (NULL bindings don't trigger jsonb type mismatch). See `KnowledgeDocumentServiceImpl.update` for the hybrid pattern.
- **Migration filename rule**: Pure DDL migrations (`ALTER TABLE`, `ADD COLUMN`, `SET NOT NULL`) keep generic `upgrade_vX_to_vY.sql`. Any migration with data-dependent `UPDATE` (id mappings, snowflake-keyed backfills, role→dept assignments) MUST carry `.<env>.sql` suffix (`upgrade_vX_to_vY.local-dev.sql`). Generic filename + env-specific UPDATEs silently matches zero rows on other envs — no error until the follow-up `SET NOT NULL` migration pre-check fails.

---

## 3. 安全 / RBAC / Sa-Token

- **Admin RBAC special case**: `KbAccessService.getAccessibleKbIds()` for `SUPER_ADMIN` returns all KBs (enforced inside the service, not at controller layer). Use `kbAccessService.isSuperAdmin()` to check admin status — do NOT use `"admin".equals(UserContext.getRole())` (that string is gone since PR1). `@SaCheckRole` annotations use `"SUPER_ADMIN"` not `"admin"`.
- **Sa-Token auth header is raw token, no Bearer prefix**: `Authorization: <token>` (NOT `Authorization: Bearer <token>`). See `application.yaml` `sa-token.token-name: Authorization` and `api.ts:15`. All permission rejections (NotRoleException, ClientException) return **HTTP 200** with `code != "0"` in the `Result` body — NOT HTTP 403/409. Assert on `code` field, never on HTTP status code.
- **Every controller needs explicit authorization**: `SaInterceptor` only enforces `StpUtil.checkLogin()` (login check), NOT role checks. New controllers must add their own `@SaCheckRole` or programmatic `kbAccessService` checks. `DashboardController` was audited and fixed for this in PR3.
- **Per-KB security_level filtering**: `t_role_kb_relation.max_security_level` (SMALLINT, 0-3) controls per-KB retrieval filtering. `KbAccessService.getMaxSecurityLevelForKb(userId, kbId)` resolves it (SUPER_ADMIN=3, DEPT_ADMIN same-dept=role ceiling, others=MAX from relation). Cached in Redis Hash `kb_security_level:{userId}`, evicted alongside `kb_access:` cache.
- **KB-centric sharing API**: `GET/PUT /knowledge-base/{kb-id}/role-bindings` (note: hyphenated `kb-id` in path, not `kbId`). SUPER_ADMIN any KB, DEPT_ADMIN own-dept only. Uses `checkKbRoleBindingAccess()`.
- **DEPT_ADMIN implicit MANAGE on same-dept KBs**: `checkManageAccess()` and `checkAccess()` both pass for `kb.dept_id == self.dept_id` without needing `role_kb_relation` entries. Cross-dept access requires explicit binding.
- **RBAC changes that add new metadata-field filters require OS index rebuild**: AuthzPostProcessor fail-closes chunks missing the new field. Ship order is: (1) write path populates the field, (2) deploy, (3) `curl -X DELETE` each collection + re-run ingestion for all docs, (4) enable the reader-side check. Skipping step 3 makes authenticated sessions return empty answers.

---

## 4. 向量存储 / OpenSearch

- **security_level filter only implemented in OpenSearch**: `MilvusRetrieverService` and `PgRetrieverService` accept `metadataFilters` parameter but silently ignore it. Switching `rag.vector.type` to `milvus` or `pg` disables document security_level enforcement at retrieval time. Fix these implementations before using non-OpenSearch backends in production.
- **OpenSearch delete/update ops must be idempotent for missing index**: Manual `curl -X DELETE /<collection>` is a valid ops path (schema rebuilds, dev cleanup). Any `VectorStoreService` method hitting OS must catch `index_not_found_exception` and treat as no-op — see `OpenSearchVectorStoreService.isIndexNotFound`. Don't assume the index exists just because a KB record exists in PG.
- **CHUNK-mode write path needs explicit `ensureVectorSpace`**: Unlike PIPELINE mode (routed through `IndexerNode.execute`), `KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically` doesn't auto-ensure the index. If index is dropped externally, OpenSearch auto-create applies **dynamic mapping** (e.g. `kb_id` becomes `text` instead of declared `keyword`), breaking downstream term queries. Any new vector-write entry point must inject `VectorStoreAdmin` and call `ensureVectorSpace` before the DB transaction.
- **`AuthzPostProcessor dropped N chunks` ERROR is a production alert**: Normal path = 0 drops (retriever already filtered). Non-zero drops mean (a) retriever filter broken / stale cache, (b) index schema drifted (missing `kb_id`), or (c) non-OpenSearch backend active. Investigate — don't suppress.

---

## 5. 消息队列 / RocketMQ

- **`MessageQueueProducer.send(topic, ...)` takes the raw string** — only `@Value` and `@RocketMQMessageListener` resolve `${...}` placeholders. Passing a literal like `"foo_topic${unique-name:}"` to `send()` silently routes to a non-existent topic while the consumer subscribes to the resolved one — no error, messages lost. Always inject the topic via `@Value("foo_topic${unique-name:}")` into a field, then pass the field. See `chunkTopic` / `feedbackTopic` / `securityLevelRefreshTopic` fields in `KnowledgeDocumentServiceImpl` and `MessageFeedbackServiceImpl` for the canonical pattern.

---

## 6. 前端 React / Vite

- **Frontend HMR vs Backend restart**: Vite dev server hot-reloads frontend changes instantly. Spring Boot requires manual restart (`mvn -pl bootstrap spring-boot:run`) after any Java code change. Always confirm backend is restarted before verifying backend changes.
- **Frontend permission-gated components must handle backend rejection**: Components rendered for `isAnyAdmin` that call DEPT_ADMIN-restricted endpoints should catch errors and hide gracefully (e.g., `KbSharingTab` sets `noAccess=true` and returns `null`), not show error toasts. The backend is the authorization boundary; the frontend optimistically renders and fails gracefully.
- **Deleting an admin page is a 5-file cascade**: page component + `router.tsx` import/route + `pages/admin/AdminLayout.tsx` sidebar item (+ now-unused `lucide-react` icon imports) + `utils/permissions.ts` `AdminMenuId` union + `DEPT_VISIBLE` array. Missing any one leaves dead sidebar links or orphan TS types.
- **Deep-link tab 参数必须在定位完成后清掉 URL (2026-04-20, `RolesTab.tsx`)**：症状：`/admin/access?tab=roles&roleId=xxx` 打开后高频 XHR（35 次/秒，`/access/roles` + `/access/roles/{id}/usage`），页面"一闪一闪"；继续点其他部门节点立即被拉回原角色所在部门。根因两层：
  - (a) `useEffect#1(reload-on-dept)` 把 `pendingRoleId` 放进依赖数组，导致 `pendingRoleId → null` 这一个 state 变化就反向触发 `setSelectedRoleId(null)`；
  - (b) `useEffect#5(searchParams)` 检测到 `selectedRoleId === null && pendingRoleId === null`，从 URL 里再读 `roleId` 重新跑 `getRoleUsage` 并 `setPendingRoleId` + `setSelectedDeptId(原 dept)` —— 回到 (a)，形成闭环。
  修复：
  - ① 用 `useRef` 读取 `pendingRoleId`，E#1 deps 只留 `selectedDeptId + loadRoles`；
  - ② 定位完成后 `setSearchParams(next, { replace: true })` 从 URL 删掉 `roleId` 参数。
  规则：任何承担 deep link 参数定位的 tab，参数消费完立刻 clear，否则和常驻的 state effect 会互相拉扯。新增 deep-link tab 必须 review 这两条。

---

## 7. 开发环境 / 运维

- **`mvn spring-boot:run` does NOT recompile stale classes after branch switch**: After `git checkout`, old `.class` files in `target/` remain. Run `mvn clean -pl bootstrap spring-boot:run` on first run in a new branch or new machine to force full recompilation with `-parameters`.
- **Cross-module source changes require `mvn install`**: Editing `framework` or `infra-ai` source then running `mvn -pl bootstrap spring-boot:run` fails — bootstrap resolves these modules from the local Maven repo, not from source. Run `mvn clean install -DskipTests` from root first. This is distinct from the branch-switch stale-class issue.
- **`curl` to OpenSearch (localhost:9201) requires `NO_PROXY`**: bash `NO_PROXY=localhost,127.0.0.1 curl ...`, or PowerShell `$env:NO_PROXY='localhost,127.0.0.1'`. Without it, curl routes through local HTTP proxy and returns 503. Same rule applies to any localhost infra calls (RustFS, PG via non-docker, Redis web UIs).
- **Restart Spring Boot on port 9090 (Windows)**: `powershell "Get-NetTCPConnection -LocalPort 9090 -State Listen | % { Stop-Process -Id \$_.OwningProcess -Force }"` then relaunch. Needed after every Java change.

---

## 新增坑点指南

当你修完一个非显而易见的 bug，问自己：
1. 下一个开发者会踩同样的坑吗？
2. 代码本身能自解释吗？（如果 yes，不用写；如果 no，写进来）
3. 应归入哪一组？（按上方 7 大主题，找不到就新起一组）

追加格式：`**症状 / 上下文**：...。**根因**：...。**规则 / 修复**：...`
