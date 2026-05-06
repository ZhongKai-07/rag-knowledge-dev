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
- **`StreamChatEventHandler.onComplete` 埋点顺序硬性**（2026-04-22 PR3 起）：`updateTraceTokenUsage()`（overwrite 走 `updateRunExtraData(String)`）必须在 `mergeCitationStatsIntoTrace()`（merge 走 `mergeRunExtraData(Map)`）**之前**执行。颠倒会让 merge 结果被后续 overwrite 清掉。任何将来新增"先合后写"字段都必须考虑这个顺序依赖。**规则**：若要往 `t_rag_trace_run.extra_data` 里合入字段，先确认不会被之后的 overwrite 写清。`StreamChatEventHandlerCitationTest` 用 `Mockito.InOrder` 锁此顺序；根治见 backlog SRC-9（把 `updateTraceTokenUsage` 也改成 merge 写）。
- **sources 判定锚点永不用位置索引**（2026-04-21 PR2 / 2026-04-22 PR3）：后端 `CitationStatsCollector.scan` 用 `indexSet.contains(n)`，前端 `CitationBadge` 用 `indexMap.get(n)`。**绝不**用 `cards[n-1]` 或 `1..N` range —— 对齐"index 非位置"契约，保未来过滤后非连续 index 的语义不动。`RAGPromptService.appendCitationRule` 当前用 `cards.size()` 作 range 上界只在 `SourceCardBuilder` 保证 index 1..N 连续的前提下正确；若未来 cards 非连续必须改为 `max(card.index)`。

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
- **MyBatis Plus `.select()` projection 全 NULL 行映射为 null entity**: `LambdaQueryWrapper.select(col1, col2, ...)` 投影出的结果集，**当某行所有被选列均为 NULL 且未选 id**，MyBatis 会把整 row 映射成 `null`（不是 fields-all-null 的 entity）。下游 `for (E r : list) { r.getX() }` 直接 NPE。**修法**：projection 必须包含 id 这种"绝不为 NULL"的列；防御性 `if (r == null) continue;` 作 belt-and-suspenders。**实战栈**：`EvalRunExecutor.computeMetricsSummary`（PR E3 E2E 实际命中 5+ 行 4 metric 全 NULL）。
- **`MyMetaObjectHandler` 仅填 createTime/updateTime/deleted，不填 *_by**: 项目 metaObjectHandler 没注 `createdBy/updatedBy` 自动填充，DO 入库时调用方必须显式 `.createdBy(principalUserId).updatedBy(...)`，否则 NOT NULL 列违反 PSQLException。**实战栈**：`EvalRunServiceImpl` PR E3 T8 missed，`GoldDatasetServiceImpl` 是参考模板。

---

## 3. 安全 / RBAC / Sa-Token

- **Admin RBAC special case**: `KbAccessService.getAccessibleKbIds()` for `SUPER_ADMIN` returns all KBs (enforced inside the service, not at controller layer). Use `kbAccessService.isSuperAdmin()` to check admin status — do NOT use `"admin".equals(UserContext.getRole())` (that string is gone since PR1). `@SaCheckRole` annotations use `"SUPER_ADMIN"` not `"admin"`.
- **Sa-Token auth header is raw token, no Bearer prefix**: `Authorization: <token>` (NOT `Authorization: Bearer <token>`). See `application.yaml` `sa-token.token-name: Authorization` and `api.ts:15`. All permission rejections (NotRoleException, ClientException) return **HTTP 200** with `code != "0"` in the `Result` body — NOT HTTP 403/409. Assert on `code` field, never on HTTP status code.
- **Every controller needs explicit authorization**: `SaInterceptor` only enforces `StpUtil.checkLogin()` (login check), NOT role checks. New controllers must add their own `@SaCheckRole` or programmatic `kbAccessService` checks. `DashboardController` was audited and fixed for this in PR3.
- **Per-KB security_level filtering**: `t_role_kb_relation.max_security_level` (SMALLINT, 0-3) controls per-KB retrieval filtering. Current-user read paths use `KbReadAccessPort.getMaxSecurityLevelsForKbs(kbIds)`; target-aware paths use `KbAccessSubjectFactory.forTargetUser(userId)` + `KbAccessCalculator.computeMaxSecurityLevels(subject, kbIds)`. SUPER_ADMIN=3, DEPT_ADMIN same-dept=role ceiling, others=MAX from relation. Evict alongside `kb_access:` cache when role-KB bindings change.
- **KB-centric sharing API**: `GET/PUT /knowledge-base/{kb-id}/role-bindings` (note: hyphenated `kb-id` in path, not `kbId`). SUPER_ADMIN any KB, DEPT_ADMIN own-dept only. Uses `checkKbRoleBindingAccess()`.
- **DEPT_ADMIN implicit MANAGE on same-dept KBs**: `checkManageAccess()` and `checkAccess()` both pass for `kb.dept_id == self.dept_id` without needing `role_kb_relation` entries. Cross-dept access requires explicit binding.
- **RBAC changes that add new metadata-field filters require OS index rebuild**: AuthzPostProcessor fail-closes chunks missing the new field. Ship order is: (1) write path populates the field, (2) deploy, (3) `curl -X DELETE` each collection + re-run ingestion for all docs, (4) enable the reader-side check. Skipping step 3 makes authenticated sessions return empty answers.
- **`bypassIfSystemOrAssertActor()` 翻转为 fail-closed（PR1 commit e9afef4）**：`KbAccessServiceImpl.bypassIfSystemOrAssertActor()`（`KbAccessServiceImpl.java:694`）在 PR1 后语义从"无用户 = 放行"翻转为"无用户 = `throw new ClientException("missing user context")`"。`UserContext.isSystem()` 仅当 `LoginUser.system=true` 显式置位才返回 `true`；仅缺失 user 而未显式置 system 的调用立即被拒。**MQ 消费者、`@Scheduled`、`@Async` 异步路径**在调入任何需要权限的 service 方法前，必须显式 `UserContext.set(LoginUser.builder()...system(true).build())`，否则 fail-closed 阻断整个异步流程。参见 `KbAccessServiceSystemActorTest`（T2a/T3）。

---

## 4. 向量存储 / OpenSearch

- **security_level filter only implemented in OpenSearch**: `MilvusRetrieverService` and `PgRetrieverService` accept `metadataFilters` parameter but silently ignore it. Switching `rag.vector.type` to `milvus` or `pg` disables document security_level enforcement at retrieval time. Fix these implementations before using non-OpenSearch backends in production.
- **OpenSearch delete/update ops must be idempotent for missing index**: Manual `curl -X DELETE /<collection>` is a valid ops path (schema rebuilds, dev cleanup). Any `VectorStoreService` method hitting OS must catch `index_not_found_exception` and treat as no-op — see `OpenSearchVectorStoreService.isIndexNotFound`. Don't assume the index exists just because a KB record exists in PG.
- **CHUNK-mode write path needs explicit `ensureVectorSpace`**: Unlike PIPELINE mode (routed through `IndexerNode.execute`), `KnowledgeDocumentServiceImpl.persistChunksAndVectorsAtomically` doesn't auto-ensure the index. If index is dropped externally, OpenSearch auto-create applies **dynamic mapping** (e.g. `kb_id` becomes `text` instead of declared `keyword`), breaking downstream term queries. Any new vector-write entry point must inject `VectorStoreAdmin` and call `ensureVectorSpace` before the DB transaction.
- **OpenSearch mapping upgrades need explicit rebuild + reindex**: `ensureVectorSpace()` only creates a missing index; it does NOT patch mappings for an existing one. After adding auth/source metadata fields such as `kb_id`, `doc_id`, `chunk_index`, or `security_level`, existing indexes keep the old schema and old chunks keep the old `_source.metadata`. Required rollout is: inspect `_mapping` -> `DELETE /<collection>` -> re-chunk/re-embed/re-index all docs in that KB -> re-sample `_source.metadata` to confirm the new fields are present. **Symptom observed 2026-04-23 (PR2 Step 9.9)**: legacy KB → OpenSearch returns 30 chunks → `AuthzPostProcessor` logs 30× `kbId is null/blank (non-OpenSearch backend?)` WARN + one ERROR `dropped 30 chunks – retriever filter failure detected`. Fix = this bullet's rollout (rebuild collection + re-ingest docs) or create a new KB.
- **`AuthzPostProcessor dropped N chunks` ERROR is a production alert**: Normal path = 0 drops (retriever already filtered). Non-zero drops mean (a) retriever filter broken / stale cache, (b) index schema drifted (missing `kb_id`), or (c) non-OpenSearch backend active. Investigate — don't suppress.
- **`rag.sources.min-top-score` default is backend-specific, not universal**: `RagSourcesProperties` defaults to `0.55D`, and `hasRelevantKbEvidence()` in `RAGChatServiceImpl` gates BOTH source cards AND suggested questions on `maxScore >= min-top-score`. `0.55` was written against a reranker/embedding whose scores land in `[0.4, 0.9]`; OpenSearch hybrid BM25+vector scores typically sit in `[0.05, 0.3]`, so the default silently suppresses both features on every query. Symptom: answer streams fine but no sources, no suggestions. Rule: when switching or tuning the retrieval backend, sample real `maxScore` via the `[sources-gate]` log line in `RAGChatServiceImpl.java:209` across a handful of queries, then set `min-top-score` just below the "relevant query" floor. Current OpenSearch baseline: `0.1`. Do NOT set `0.0` — that degrades to "any non-empty retrieval passes," which lets obviously-irrelevant chunks render as source cards.

---

## 5. 消息队列 / RocketMQ

- **`MessageQueueProducer.send(topic, ...)` takes the raw string** — only `@Value` and `@RocketMQMessageListener` resolve `${...}` placeholders. Passing a literal like `"foo_topic${unique-name:}"` to `send()` silently routes to a non-existent topic while the consumer subscribes to the resolved one — no error, messages lost. Always inject the topic via `@Value("foo_topic${unique-name:}")` into a field, then pass the field. See `chunkTopic` / `feedbackTopic` / `securityLevelRefreshTopic` fields in `KnowledgeDocumentServiceImpl` and `MessageFeedbackServiceImpl` for the canonical pattern.

---

## 6. 前端 React / Vite

- **Frontend HMR vs Backend restart**: Vite dev server hot-reloads frontend changes instantly. Spring Boot requires manual restart (`mvn -pl bootstrap spring-boot:run`) after any Java code change. Always confirm backend is restarted before verifying backend changes.
- **Frontend permission-gated components must handle backend rejection**: Components rendered for `isAnyAdmin` that call DEPT_ADMIN-restricted endpoints should catch errors and hide gracefully (e.g., `KbSharingTab` sets `noAccess=true` and returns `null`), not show error toasts. The backend is the authorization boundary; the frontend optimistically renders and fails gracefully.
- **Deleting an admin page is a 5-file cascade**: page component + `router.tsx` import/route + `pages/admin/AdminLayout.tsx` sidebar item (+ now-unused `lucide-react` icon imports) + `utils/permissions.ts` `AdminMenuId` union + `DEPT_VISIBLE` array. Missing any one leaves dead sidebar links or orphan TS types.
- **Answer Sources rollback 契约 — `hasSources` 必须对称 gate `remarkPlugins` 和 `components.cite` 两侧（2026-04-22 PR3）**：`MarkdownRenderer.tsx` 里 `hasSources = Array.isArray(sources) && sources.length > 0` 单一闸门必须**同时**控制：(1) `remarkPlugins = hasSources ? [remarkGfm, remarkCitations] : [remarkGfm]`，(2) `components.cite` 映射只在 `hasSources=true` 时存在。任何一侧未 gate 都会把答案里字面 `[^n]`（教程示例 / 幻觉 marker）错误渲染为 `<sup>` 上标，破坏"flag off 等同 PR2"的字节级回滚契约。`MarkdownRenderer.test.tsx` case (a) 显式锁此契约。
- **`remarkPlugins` 顺序硬固定 `[remarkGfm, remarkCitations]`（2026-04-22 PR3）**：反了的话 remark-gfm 无法把已解析的定义块归并到 footnoteReference 节点，`remarkCitations` 的 footnoteReference visit 走空。`remarkCitations` 内部三段 visit 顺序（footnoteReference → footnoteDefinition → text）也不能颠倒：第 2 步 splice 删除定义子树是第 3 步 text visit 不会误抓定义体内 `[^n]` 的前提。
- **`[^n]` citation 渲染严禁字符串 preprocess（2026-04-22 PR3）**：走 `remarkCitations` mdast 插件；`SKIP_PARENT_TYPES = {inlineCode, code, link, image, linkReference}` 硬约束，代码块 / 行内代码 / 链接 / 图片 / 链接引用里的 `[^n]` 字面量保持原样不转 cite 节点。`citationAst.ts` 是 SSOT。**症状**：曾被讨论过用 `content.replace(/\[\^(\d+)\]/g, ...)` 预处理，被否决 —— 会把代码示例里的 `[^1]` 也转了，且不尊重 mdast 结构。
- **`MessageItem` citation click timer 必须 unmount cleanup（2026-04-22 PR3）**：`handleCitationClick` 走 `setTimeout(1500ms)` 清 highlight，组件卸载时 `useEffect` cleanup 必须 `window.clearTimeout(timerRef.current)`，否则卸载后 setState 触发 React act warning。`MessageItem.test.tsx` 有锁此行为的用例。
- **Deep-link tab 参数必须在定位完成后清掉 URL (2026-04-20, `RolesTab.tsx`)**：症状：`/admin/access?tab=roles&roleId=xxx` 打开后高频 XHR（35 次/秒，`/access/roles` + `/access/roles/{id}/usage`），页面"一闪一闪"；继续点其他部门节点立即被拉回原角色所在部门。根因两层：
  - (a) `useEffect#1(reload-on-dept)` 把 `pendingRoleId` 放进依赖数组，导致 `pendingRoleId → null` 这一个 state 变化就反向触发 `setSelectedRoleId(null)`；
  - (b) `useEffect#5(searchParams)` 检测到 `selectedRoleId === null && pendingRoleId === null`，从 URL 里再读 `roleId` 重新跑 `getRoleUsage` 并 `setPendingRoleId` + `setSelectedDeptId(原 dept)` —— 回到 (a)，形成闭环。
  修复：
  - ① 用 `useRef` 读取 `pendingRoleId`，E#1 deps 只留 `selectedDeptId + loadRoles`；
  - ② 定位完成后 `setSearchParams(next, { replace: true })` 从 URL 删掉 `roleId` 参数。
  规则：任何承担 deep link 参数定位的 tab，参数消费完立刻 clear，否则和常驻的 state effect 会互相拉扯。新增 deep-link tab 必须 review 这两条。
- **Tab filter 跨刷新 / 跨 session 持久化：URL 是 SSOT，localStorage 是 fallback (2026-04-26 PR E3 EvalRunsTab/EvalTrendsTab)**：用户从侧栏直进 `?tab=runs`（无 datasetId）或刷新 trends 页时本地 `useState` 即丢。**模式**：`useSearchParams` 持有 datasetId（URL = SSOT，可 share link / 浏览器历史）+ `localStorage` 仅在首次访问 / 直进 tab 没 URL 参数时灌回；任何 set 同时写两边。`utils.ts` 的 `readStoredDatasetId/writeStoredDatasetId` swallow 掉隐身 / 配额异常（不影响 URL 路径）。**反模式**：把 localStorage 作 SSOT，URL 只是同步器——会破坏 share link 语义。

---

## 7. 开发环境 / 运维

- **`mvn spring-boot:run` does NOT recompile stale classes after branch switch**: After `git checkout`, old `.class` files in `target/` remain. Run `mvn -pl bootstrap -am clean spring-boot:run` on first run in a new branch or new machine to force full recompilation with `-parameters`.
- **Cross-module source changes require reactor build (`-am`) or install**: Editing `framework` or `infra-ai` source then running `mvn -pl bootstrap spring-boot:run` can compile bootstrap against a stale local Maven repo jar. Use `mvn -pl bootstrap -am spring-boot:run` for dev startup, or run `mvn clean install -DskipTests` from root first. This is distinct from the branch-switch stale-class issue.
- **`curl` to OpenSearch (localhost:9201) requires `NO_PROXY`**: bash `NO_PROXY=localhost,127.0.0.1 curl ...`, or PowerShell `$env:NO_PROXY='localhost,127.0.0.1'`. Without it, curl routes through local HTTP proxy and returns 503. Same rule applies to any localhost infra calls (RustFS, PG via non-docker, Redis web UIs).
- **Restart Spring Boot on port 9090 (Windows)**: `powershell "Get-NetTCPConnection -LocalPort 9090 -State Listen | % { Stop-Process -Id \$_.OwningProcess -Force }"` then relaunch. Needed after every Java change.

---

## 8. 文档解析 / Parser 域（Phase 2.5 PR 1+2 起）

跨 PR 不变量集中区。改动 `bootstrap/.../core/parser/` 或 `ingestion/node/ParserNode.java` 前先扫一遍。memory: `project_parser_pr1.md`，plan: `docs/superpowers/plans/2026-05-05-parser-enhancement-docling.md`。

- **`ParseResult` 2-arg ctor + `ofText` / `of` 工厂永久保留**：4-arg ctor `(text, metadata, pages, tables)` 是 PR 1 引入；老调用方（`TikaDocumentParser` / `MarkdownDocumentParser`）一律继续用 2-arg。改 `ParseResult` 别强制迁移老代码 —— 现有 `record` 的 compact ctor 已把 null 转 `Map.of()` / `List.of()`，2-arg 重载内部转 4-arg 调用。
- **`@JsonIgnoreProperties(ignoreUnknown=true)` 在 `ParserSettings` 上不可删**：`NodeConfig.settings` 是单一 JSON 节点，同时承载 `parseMode` + `rules` 字段；注解去掉会爆 `UnrecognizedPropertyException`。新加路由字段（如 PR 5 的 `doclingTimeout`）要么建模到 `ParserSettings`，要么靠注解兜底。
- **`ParserNode.readParseMode` 是 null-safe 入口**：`null` / missing / blank → `ParseMode.BASIC`（不抛）；未知字符串 → `IllegalArgumentException`（由 `ParseMode.fromValue` 抛）。加新模式（如 `OCR`）必须扩 `ParseMode` enum，**不绕过** `readParseMode`。
- **ENHANCED → fallback 对外行为不可弱化**：Docling 未注册时由 `DocumentParserSelector.buildEnhancedParser()` 用 `FallbackParserDecorator.degraded(...)` 兜底；ingestion 永远不应整链路因 Docling 不可用而失败。`parse_engine_requested` / `parse_engine_actual` / `parse_fallback_reason` 三个 metadata 必须 stamp 给前端读以渲染降级提示。PR 5 接真 Docling 时只是把 selector 内的 degraded 分支替换为 healthy ctor，对外 metadata 行为对称等强。
- **layout 数据结构是 engine-neutral**：`BlockType` / `LayoutBlock` / `DocumentPageText` / `LayoutTable` 不依赖任何具体引擎字段；换引擎（marker / unstructured）走适配器层 mapping（PR 5 的 `DoclingResponseAdapter`），这四个 record 不动。
- **`FallbackParserDecorator` 不可注册成 Spring bean**（PR 2 起）：`DocumentParserSelector` 构造期 `instanceof FallbackParserDecorator` 后 `IllegalArgumentException` fail-fast。Decorator 由 selector 内部 `buildEnhancedParser()` 一次性建好缓存为 `enhancedParser` final 字段；走 Spring 注入会让 `decorator.getParserType() == "Docling"` 在 `strategyMap` 里和真正的 Docling parser 互相遮蔽（key 冲突 + 顺序不确定）。PR 5 加 `@Component DoclingDocumentParser` 时不能误把 decorator 也声明成 bean。
- **metadata 键的单一真相源在 decorator 类上**（PR 2 起）：`FallbackParserDecorator.META_ENGINE_REQUESTED` / `META_ENGINE_ACTUAL` / `META_FALLBACK_REASON` / `REASON_PRIMARY_FAILED`（健康模式失败用）/ `REASON_PRIMARY_UNAVAILABLE`（degraded factory 用）—— 5 个 public static final。**不要拼字面量**。如果 PR 5+ 多个消费方（多个 parser / adapter / 前端 mapper）开始引用，按 `VectorMetadataFields` 模式抽到 `ParserMetadataFields`。
- **Docling DTO ↔ sidecar 版本强耦合（PR 5 起）**：`DoclingClient` 是项目自己手写的 OkHttp 客户端（不是 docling-java SDK，infra-ai pom 里没有任何 Docling SDK 依赖），multipart 字段名（`files` 复数 / `to_formats=json` + `to_formats=text`）和响应体结构（`DoclingConvertResponse` DTO + `DoclingResponseAdapter`）都是对着 **Docling Python sidecar v0.5.1 实测响应**摸出来的（见 `quay.io/ds4sd/docling-serve:v0.5.1`，`docling.compose.yaml` 钉死版本）。**症状（如果不感知此耦合）**：升 sidecar 镜像后字段重命名 / 字段消失 / response shape 漂移会让 `DoclingResponseAdapter` 静默吐 null `pages` / null `tables`，下游 ENHANCED 路径的 page-level metadata 全为空但 ingestion 不 fail（因为 `FallbackParserDecorator` 不会把"成功响应但语义残缺"识别成失败）。**规则**：(1) 任何升级 `docling-serve` 镜像的 PR 必须附带跑一遍 `bootstrap/src/test/resources/docling/sample-convert-response.json` 重抓 + `DoclingResponseAdapterTest` 全过的证据；(2) 不要随手把 compose 里 `v0.5.1` 改成 `latest`，quay.io 也不维护 latest tag；(3) 如果未来引官方 docling-java SDK，要同时干掉 `DoclingClient` / `AbstractRemoteParser` / `DoclingConvertResponse` / `DoclingResponseAdapter` 这一整条手写链。参考 commit `54f82136 fix(docling): align DTO/adapter/client with actual v0.5.1 /convert response`。
- **PR 6 起 layout 字段双路径写入**（chunk 阶段写 `VectorChunk.metadata` Map → OS catch-all 透传写入索引；
  persist 阶段经 `KnowledgeChunkLayoutMapper.copyToCreateRequest` 抽到 `KnowledgeChunkCreateRequest`
  的 9 字段 → DO → DB）。**DB 与 OS 必须保持双侧一致**。任何从 DB 读 `KnowledgeChunkDO` 重建
  `VectorChunk` 写回 OS 的 re-index 路径（enable/disable/单 chunk 编辑等）必须紧跟
  `KnowledgeChunkLayoutMapper.copyFromDO(do, vc)` 把 9 字段反向回填到 metadata Map，
  否则 OS 端 layout 会丢。Sweep `VectorChunk.builder()` 在 `KnowledgeChunkServiceImpl` /
  `KnowledgeDocumentServiceImpl` 的 5 处调用点（PR 6 已加；新增类似路径必须延续此模式）。
- **手工编辑 chunk content 后 layout 字段处理（PR 6）**：`KnowledgeChunkServiceImpl.update`
  保留 5 个 location 字段（`pageNumber / pageStart / pageEnd / headingPath / blockType` —
  chunk 仍属该页该章节），清空 4 个 extraction 字段（`sourceBlockIds / bboxRefs /
  textLayerType / layoutConfidence` — 已不忠于改写后的文本）。新增手工编辑入口须延续此切分。

---

## 新增坑点指南

当你修完一个非显而易见的 bug，问自己：
1. 下一个开发者会踩同样的坑吗？
2. 代码本身能自解释吗？（如果 yes，不用写；如果 no，写进来）
3. 应归入哪一组？（按上方 7 大主题，找不到就新起一组）

追加格式：`**症状 / 上下文**：...。**根因**：...。**规则 / 修复**：...`
