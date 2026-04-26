# Permission PR1 — Manual Smoke Checklist

> Run before merging PR1; reproduce post-merge in any subsequent PR2/PR3 changes that
> touch the affected paths.
>
> Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md §3.4

## Response-shape note

This project does NOT translate `ClientException` to HTTP 403. `GlobalExceptionHandler#abstractException` (`framework/.../GlobalExceptionHandler.java:73-87`) catches `AbstractException` (the parent of `ClientException`) and returns `Results.failure(ex)` — i.e. **HTTP 200** with a JSON body `{"code": "<error-code>", "message": "<reason>", "success": false}`. Likewise `NotLoginException` is wrapped to HTTP 200 + `code=CLIENT_ERROR` + message `"未登录或登录已过期"`. Operators reading this doc should expect HTTP 200 with `success=false` payloads, NOT 401/403.

## Setup

`init_data_pg.sql` only seeds the SUPER_ADMIN `admin` account in the GLOBAL dept (`dept_id=1`). The DEPT_ADMIN / cross-dept fixtures below are NOT pre-seeded and must be inserted before running the checklist. Two options:

**Option A — manual SQL** (paste into psql; replace `OPS_DEPT_ID` / `FICC_DEPT_ID` / `OPS_KB_ID` with snowflake IDs from your environment):

```sql
-- Departments
INSERT INTO t_sys_dept (id, dept_name, parent_id, sort_order, status, deleted)
VALUES ('OPS_DEPT_ID', 'OPS', '1', 0, 0, 0),
       ('FICC_DEPT_ID', 'FICC', '1', 0, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- Roles (one DEPT_ADMIN role bound to OPS, one USER role bound to FICC)
INSERT INTO t_role (id, role_name, role_type, dept_id, deleted)
VALUES ('OPS_ADMIN_ROLE_ID', 'OPS Admin', 'DEPT_ADMIN', 'OPS_DEPT_ID', 0),
       ('FICC_USER_ROLE_ID', 'FICC User', 'USER',       'FICC_DEPT_ID', 0)
ON CONFLICT (id) DO NOTHING;

-- Users
INSERT INTO t_user (id, username, password, dept_id, deleted)
VALUES ('OPS_ADMIN_USER_ID', 'ops_admin',  '<bcrypt-of-ops_admin>',  'OPS_DEPT_ID', 0),
       ('FICC_USER_USER_ID', 'ficc_user',  '<bcrypt-of-ficc_user>',  'FICC_DEPT_ID', 0)
ON CONFLICT (id) DO NOTHING;

-- User-Role bindings (id is VARCHAR(20) NOT NULL, no auto-gen — provide explicit values)
INSERT INTO t_user_role (id, user_id, role_id) VALUES
  ('UR_OPS_ADMIN', 'OPS_ADMIN_USER_ID', 'OPS_ADMIN_ROLE_ID'),
  ('UR_FICC_USER', 'FICC_USER_USER_ID', 'FICC_USER_ROLE_ID')
ON CONFLICT (id) DO NOTHING;

-- Knowledge base owned by OPS dept
INSERT INTO t_knowledge_base (id, name, embedding_model, collection_name, dept_id, created_by, deleted)
VALUES ('OPS_KB_ID', 'OPS-PR1-Smoke', 'bge-m3', 'kb_ops_pr1_smoke', 'OPS_DEPT_ID', 'OPS_ADMIN_USER_ID', 0)
ON CONFLICT (id) DO NOTHING;
```

**Option B — reuse local-dev upgrade fixture**: `resources/database/upgrade_v1.5_to_v1.6.local-dev.sql` already inserts an `OPS` dept (id `2043727565494968320`). Backfill role + users + KB referencing that dept ID.

After insert, log in via `POST /api/ragent/auth/login` (Sa-Token) for each account to obtain session tokens.

Three test subjects:
- **OPS_ADMIN** — `role_type=DEPT_ADMIN`, dept = OPS (the dept owning the KB)
- **FICC_USER** — `role_type=USER`, dept = FICC (different dept)
- **OPS_KB_ID** — knowledge base with `dept_id = OPS`

## Path 1: OPS_ADMIN renames own KB → success

`PUT /api/ragent/knowledge-base/{OPS_KB_ID}`, body `{"name": "renamed"}`, OPS_ADMIN session.

**Expected**: HTTP 200, body `{"code": "0", "success": true, ...}`. KB row in DB has `name='renamed'`.

## Path 2: FICC_USER renames OPS KB → service-layer rejection

`PUT /api/ragent/knowledge-base/{OPS_KB_ID}`, body `{"name": "hijacked"}`, FICC_USER session.

**Expected**: HTTP 200 with body `{"success": false, "code": "<CLIENT_ERROR-or-similar>", "message": "无权管理其他部门知识库: ..."}`. KB row unchanged.

This proves PR1's service-layer boundary rejects the same way the controller-layer check did pre-PR1: `KnowledgeBaseServiceImpl.rename(...)` first calls `kbAccessService.checkManageAccess(kbId)`, which throws `ClientException("无权管理其他部门知识库: " + kbId)` for the cross-dept case, translated by `GlobalExceptionHandler#abstractException` to a `Results.failure(ex)` envelope.

## Path 3: HTTP upload → MQ → `executeChunk` completes

Upload a small `.txt` to `OPS_KB_ID` as OPS_ADMIN via `POST /api/ragent/knowledge-base/{OPS_KB_ID}/docs/upload` (multipart). Then trigger `POST /api/ragent/knowledge-base/docs/{doc-id}/chunk`.

**Expected**: the document transitions PENDING → CHUNKING → SUCCESS in `t_knowledge_document.status`. Application logs do NOT contain `ClientException("missing user context")` and do NOT contain any `权限拒绝` / `无权` lines for this docId.

This validates the MQ path: `KnowledgeDocumentChunkConsumer.onMessage` sets `LoginUser.builder()...system(true).build()` (commit 2), so `KbAccessService.checkXxx` calls in the deep stack short-circuit via `bypassIfSystemOrAssertActor() == true` (commit 3). If `.system(true)` were missing, the consumer would throw `ClientException("missing user context")` and chunking would fail.

## Path 4a: Anonymous HTTP request

`GET /api/ragent/knowledge-base/{OPS_KB_ID}` with no Sa-Token cookie / header.

**Expected**: HTTP 200 with body `{"success": false, "code": "...", "message": "未登录或登录已过期"}`. (Sa-Token's `NotLoginException` is wrapped by `GlobalExceptionHandler#notLoginException`.)

## Path 4b: Missing UserContext at service level (code review only)

If a future caller bypasses Sa-Token and reaches `KbAccessService.checkAccess(...)` without setting `UserContext` and without `LoginUser.system=true`, the new guard throws `ClientException("missing user context")` (commit 3). Verified by `KbAccessServiceSystemActorTest.checkAccess_throws_when_no_user_context` — no manual HTTP path required.

## Sign-off

- [ ] Path 1 OK
- [ ] Path 2 OK
- [ ] Path 3 OK
- [ ] Path 4a OK
- [ ] Path 4b reviewed in code
