# PR3 Demo Walkthrough

**Two distinct run modes** — this walkthrough and the curl matrix are designed to run independently against different rebuild baselines.

| Mode | Database baseline | Purpose |
|---|---|---|
| **UI walkthrough** (this document) | `schema_pg.sql` + `init_data_pg.sql` (**NO fixture**) | Prove the entire UI CRUD chain: admin creates departments, users, roles, assignments, KBs, documents |
| **curl matrix** (`pr3-curl-matrix.http`) | `schema_pg.sql` + `init_data_pg.sql` + `fixture_pr3_demo.sql` | Bypass-test permission rules using stable business keys (alice/bob/carol + pre-built KBs) |

The walkthrough **must not** depend on fixture data — if it does, the UI creation chain is not being exercised and the acceptance criterion in spec §10.4 is not met. Run them in separate rebuild sessions.

## Prerequisites (UI walkthrough)

- Database freshly rebuilt: `DROP / CREATE / schema_pg.sql / init_data_pg.sql` (no fixture)
- OpenSearch flushed (`curl -X DELETE http://localhost:9200/_all`)
- Redis flushed (`docker exec redis redis-cli FLUSHDB`)
- Backend running on :9090, frontend dev server (or built) on :5173
- Sample test documents ready on local disk:
  - `test-public.md` (any content; uploaded as security_level=0 for contrast)
  - `test-confidential.md` (any content; uploaded as security_level=2 for the key structural assertion in step 10)

## Steps (strict 12-step, matches spec §10.4 exactly)

### 1. admin login — 12 menu items + 超级管理员 label

Open http://localhost:5173/login, log in as `admin` / `123456`.
- Redirected to `/spaces` (per Decision 3-B). Click "进入后台" / navigate to `/admin/dashboard`.
- Verify the sidebar shows **12** menu items: Dashboard, 知识库管理, 意图管理, 数据通道, 关键词映射, 链路追踪, 评测记录, 示例问题, 部门管理, 用户管理, 角色管理, 系统设置.
- Verify header roleLabel reads **"超级管理员"**.
- On this baseline (init_data_pg.sql only), the users list contains a single row: `admin` with dept=全局部门, role_type=SUPER_ADMIN, max=3.

### 2. Create the two demo departments via UI

Go to `/admin/departments`. The list contains 1 row: GLOBAL (locked, systemReserved=true).

Click **新建部门**:
- `dept_code = RND`, `dept_name = 研发部` → save
- `dept_code = LEGAL`, `dept_name = 法务部` → save

Verify list now shows 3 rows. Try **删除 GLOBAL** → error toast "GLOBAL 部门不可删除".

### 3. Create three demo users via UI

Go to `/admin/users`. Click **新增用户** three times:

| 用户名 | 密码 | 部门 | 备注 |
|---|---|---|---|
| alice | 123456 | 研发部 | 角色下一步分配 |
| bob   | 123456 | 法务部 | 角色下一步分配 |
| carol | 123456 | 研发部 | 角色下一步分配 |

For each: the 部门 dropdown must show RND + LEGAL + GLOBAL (admin sees all). The 角色 multi-select at this point has only the seeded role "超级管理员"/"普通用户"; leave unchecked, assignment happens in step 5.

**Important:** `max_security_level` is NOT a user form field — it's derived from roles. The user form must NOT expose it; if it does, that's a Slice S2.2 bug.

### 4. Create three demo roles via UI

Go to `/admin/roles`. Verify the role dialog has **role_type** and **max_security_level** dropdowns (Slice S3.1 deliverable).

Click **新建角色** three times:

| 角色名称 | role_type | max_security_level | 描述 |
|---|---|---|---|
| 研发部管理员 | DEPT_ADMIN | 3 | 管理研发部的 KB 和用户 |
| 法务部管理员 | DEPT_ADMIN | 3 | 管理法务部的 KB 和用户 |
| 普通研发员   | USER       | 0 | 只读访问研发 KB |

- SUPER_ADMIN 选项出现在 role_type 下拉里（当前登录是 admin，Slice S3.1 允许 admin 选择）—— 但为了 Last SUPER_ADMIN 不变量演示，本步骤**不要**创建 SUPER_ADMIN 类型角色。
- After each save, verify the role list shows the new row with the role_type chip and max_security_level badge.

### 5. Assign roles to users via UI

Return to `/admin/users`. For each user, click **编辑**:

- alice → 角色多选里勾选 "研发部管理员" → 保存
- bob → 角色多选里勾选 "法务部管理员" → 保存
- carol → 角色多选里勾选 "普通研发员" → 保存

After save, the user list rows update:
- alice: 角色类型 chip = DEPT_ADMIN, 最大密级 badge = 3 (red)
- bob: 角色类型 chip = DEPT_ADMIN, 最大密级 badge = 3 (red)
- carol: 角色类型 chip = USER, 最大密级 badge = 0 (green)

**This is the step that decides carol's max=0** — not step 3 (user create). Per spec §10.4 note: "max_security_level is derived from roles".

**Sub-step 5b:** After assigning roles, go to `/admin/roles` → 普通研发员 → **知识库** button → bind 研发知识库 with permission READ. This is required for step 10 (carol can see the KB in /spaces).

### 6. alice login — 3 menu items + 研发部管理员 label

Log out admin. Log in as `alice` / `123456`.
- Sidebar shows only **3** items: Dashboard, 知识库管理, 用户管理 (matrix A, Decision 3-A).
- Header roleLabel reads **"研发部管理员"** (4-tier fallback from Slice 6; relies on `deptName` being populated via `UserProfileLoader`).

### 7. alice creates a KB via UI — dept_id locked to 研发部

Go to `/admin/knowledge`. List is empty on this baseline.

Click **新建知识库**:
- Dialog shows 部门 dropdown **locked and disabled** at "研发部" (DEPT_ADMIN self-dept lock from Slice S4).
- `name = 研发知识库`, save.
- Verify the new KB is listed and dept column shows 研发部.

### 8. alice verifies user scoping — only 研发部 visible

- /admin/users shows only **alice + carol** (admin and bob hidden).
- Click **编辑 carol**:
  - 部门 dropdown locked at 研发部, disabled.
  - 角色多选列表:
    - "研发部管理员" ✅ visible (DEPT_ADMIN type)
    - "普通研发员" ✅ visible (USER type)
    - "超级管理员" ❌ hidden (SUPER_ADMIN type filtered out by `permissions.canAssignRole`)
- Cancel out of the dialog.

Attempt delete on alice's own row: button may be shown but the backend will reject via Last SUPER_ADMIN invariant test is N/A here (alice is DEPT_ADMIN, not SUPER_ADMIN). Instead try the negative test: alice has no way to reach bob (not in list). The protection is structural.

### 9. alice uploads a confidential document via UI

- Navigate to /admin/knowledge → 研发知识库 → 文档管理 → 新增文档.
- Upload `test-confidential.md`, set **密级 = 2 机密** (Slice S5.1 upload dialog).
- Wait for chunk processing (watch status transition PENDING → RUNNING → SUCCESS).
- Verify the documents list shows the new row with **密级 badge = 2 机密 (orange)**.

(Optional for contrast: upload `test-public.md` with 密级=0 to confirm both render.)

### 10. carol login — retrieval excludes level=2 doc

- Log out alice. Log in as `carol` / `123456`.
- carol has no access to /admin (only USER role_type → `canSeeAdminMenu` = false → Navigate to /spaces).
- On /spaces, carol should see the "研发知识库" space (via RBAC: 普通研发员 role is READ-bound to 研发知识库 via the role-KB binding set in step 5b).

Once carol can see the KB, open chat for 研发知识库 and ask a question related to the confidential doc content (e.g., "机密架构的核心要点是什么？").

**Structural assertion (Decision 3-L, not text-coupled):**
- The response's retrieved sources (visible in chat UI or `/rag/traces` if observability is on) MUST NOT include a source whose `docId` matches the `test-confidential.md` upload from step 9.
- Carol's `max_security_level = 0`, doc's `security_level = 2`, OpenSearch metadata filter `security_level <= 0` excludes it.
- **Do not assert on response text** ("未找到"/"not found" etc.) — the answer wording depends on prompt templates and must not couple to this security test.

### 11. bob login — law-only scope + cross-dept forbidden

- Log out carol. Log in as `bob` / `123456`.
- Sidebar shows 3 items (same as alice).
- /admin/knowledge shows nothing — bob has no KB created or bound yet. (Contrast with fixture-based matrix which pre-binds 法务知识库.)
- /admin/users shows only **bob** (alice/carol hidden, different dept).
- Open a terminal and attempt raw curl cross-dept write (recall: header is raw token, no Bearer):

```bash
BOB_TOKEN=$(curl -s -X POST http://localhost:9090/api/ragent/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"123456"}' | jq -r '.data.token')
curl -s -X PUT http://localhost:9090/api/ragent/knowledge-base/<rnd-kb-id> \
  -H "Authorization: $BOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"偷改"}' | jq .
```

Expected response shape: `{ "code": "<non-zero>", "message": "无权管理其他部门知识库: <rnd-kb-id>", "data": null }` — **HTTP status is 200**, not 403. Confirm via the response `code` field (not status code) that the permission was rejected.

### 12. Code hygiene grep

From the repo root (use Git Bash / WSL on Windows since these are regex patterns):

```bash
grep -rn '"admin"\.equals' bootstrap/src/main/java/ | grep -v '/\*'          # should be empty or docs only
grep -rn 'LoginUser\.role\|\.getRole()' bootstrap/src/main/java/com/knowledgebase/ai/ragent/user/ bootstrap/src/main/java/com/knowledgebase/ai/ragent/knowledge/
grep -rn 'user\.role\|user?\.role' frontend/src/ --include='*.ts' --include='*.tsx'
grep -rn 'LoginUser\.role' framework/src/main/java/
```

Expected: no business-layer matches. The `t_user.role` column stays (Sa-Token compat layer reads it for legacy `UserDO.getRole()`), so `UserDO.getRole()` may still appear in DAO layer — that's acceptable as long as it's not referenced from business logic.

## Troubleshooting

- **Step 1 admin shows no dept / empty roleTypes / max=0**: `UserProfileLoader` JOIN is broken. Check `init_data_pg.sql` seeded `t_user_role(id='1', user_id='1', role_id='1')`; check `t_role(id='1')` has `role_type='SUPER_ADMIN'` and `max_security_level=3`; check `sys_dept(id='1')` exists. If all 3 seeds are present and the display is still wrong, `UserProfileLoaderImpl.load()` has a JOIN bug (Task 0.4).
- **Step 6 alice sees 12 menus instead of 3**: `permissions.canSeeMenuItem` is not being called; likely `menuGroups` in `AdminLayout.tsx` missed the `id` field. Re-check Slice 6 in Task 0.15 Step 8.
- **Step 6 alice roleLabel shows "部门管理员" instead of "研发部管理员"**: `deptName` is null on alice. Check that `UserProfileLoader` returns `deptName` from `sys_dept` JOIN; check Task 0.5 `AuthServiceImpl.login()` sets `vo.setDeptName(profile.deptName())`.
- **Step 10 carol retrieves confidential doc**: Either (a) OpenSearch metadata wasn't indexed with `security_level`, or (b) `RAGChatServiceImpl` isn't passing the `maxSecurityLevel` filter into the retrieval query. Recheck PR1 delivery; PR3 only exposed this capability in UI.
- **Stale frontend state**: clear browser localStorage (`localStorage.clear()`), force refresh. `authStore` persists to localStorage so old user shapes can cause mismatches after Task 0.15.
