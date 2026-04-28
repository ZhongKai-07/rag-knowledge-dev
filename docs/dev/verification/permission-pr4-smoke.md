# Permission PR4 Manual Smoke Paths

> **Spec:** `docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md` §3.3
> **Prerequisite:** PR1-PR3 smoke paths from `permission-pr1-smoke.md` remain valid. Backend listens on `9090` with context path `/api/ragent`.

## S5 - FICC_USER Accesses Forbidden KB

**Purpose:** Verify `RetrievalScopeBuilder` triggers `checkReadAccess` and fails closed when `requestedKbId` is not readable by the current user.

```bash
# Step 1: log in as FICC_USER and capture a token.
TOKEN=$(curl -sX POST http://localhost:9090/api/ragent/login \
  -d 'username=ficc_user&password=...' | jq -r '.data.token')

# Step 2: request an OPS-COB KB that FICC_USER cannot read.
curl -sN -H "Authorization: ${TOKEN}" \
  "http://localhost:9090/api/ragent/rag/v3/chat?question=test&knowledgeBaseId=<OPS-COB-id>"
```

**Expected:** SSE immediately receives a rejected `Result` wrapping `ClientException`: `code != 0`, `message` contains `无权访问`. HTTP status remains `200` because the error is carried in the SSE body.

## S6 - Anonymous User Requests Specific KB

**Purpose:** Verify the fail-closed order for review P2#1: `requestedKbId != null` is checked before `user == null`, preserving PR1 HTTP semantics.

```bash
# No Authorization header.
curl -sN "http://localhost:9090/api/ragent/rag/v3/chat?question=test&knowledgeBaseId=<any-id>"
```

**Expected:** `Result.code != 0`, `message` contains `missing user context`. It must not degrade into an empty answer such as `未检索到与问题相关的文档内容。`

If an empty answer is returned, builder fail-closed ordering regressed because `user == null` short-circuited before `requestedKbId`.

## S7 - Eval Path Holds Sentinel Scope

**Purpose:** Verify `EvalRunExecutor` uses `RetrievalScope.all(kbId)` and does not route through `RetrievalScopeBuilder`.

Enable DEBUG logging for `com.knowledgebase.ai.ragent.rag.core.retrieve.scope`, trigger one ACTIVE eval run from the admin UI, and confirm logs contain no `RetrievalScopeBuilder` invocation from the eval thread.

## Reused Smoke

Keep the existing PR1-PR3 manual smoke paths in force. PR4 adds S5/S6/S7 and does not replace those checks.
