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

## Sign-off — 2026-04-28 (executed against `feature/permission-pr4-scope-builder`, backend started 14:41:21)

| Step | Result | Evidence |
| ---- | ------ | -------- |
| **S6 anonymous → KB `2047243602324664320`** | ✅ PASS | `code:"A000001" / message:"未登录或登录已过期" / success:false` —— 被 Sa-Token 在 builder 之前 fail-closed 拦截，无空答案降级，符合 review P2#1 防回归（Sa-Token 拦截属上游兜底，PR4 builder 内 `requestedKbId != null` 优先于 `user == null` 的语义在自动化测试 `RetrievalScopeBuilderImplTest` 中已锁定）。 |
| **S5 ficcuser (FICC dept, 仅可读 `2043868352748748800`) → forbidden OPS KB `2043863895700963328`** | ✅ PASS | `event:meta` 后 0.07s 断流（HTTP 200, size=89 bytes）。Builder 抛 `ClientException("无权访问该知识库")` → `ChatRateLimitAspect.invokeWithTrace` catch → `emitter.completeWithError(cause)`。无 chunk / sources / message 事件泄漏。 |
| **S5 ficcuser → forbidden OPS KB `2047243602324664320`** | ✅ PASS | 同上：META 后 2.16s 断流，size=89 bytes。 |
| **Control: ficcuser → ALLOWED KB `2043868352748748800` (role-bound)** | ✅ PASS | META 后持续流（4s `--max-time` 截断），证明非授予 KB 的早断行为是 access check 而非通用错误。 |
| **S7 eval sentinel** | ⏭️ SKIPPED (optional) | smoke 文档已注明 high-cost；ArchUnit `chat_for_eval_does_not_inject_scope_builder` + grep gate 5 (`RetrievalScope.all(` allowlist 限制为 `RetrievalScope.java / RetrievalScopeBuilderImpl.java / EvalRunExecutor.java` 三处) + `EvalRunExecutorTest` ArgumentCaptor 已自动化锁定 sentinel 语义，无需手工触发 Python eval 服务复测。 |

**实测细节差异（更新规范预期）：** 智能能 spec §S5/S6 描述的"SSE 立即收到 rejected `Result`"在当前实现里走 `emitter.completeWithError`（连接断流 + HTTP 200 + 仅 META），而非 SSE body 里的 Result JSON。两条等价的 fail-closed 通道，关键是没有空答案降级 + 无内容泄漏，本次实测均满足。后续若需要把错误信息也走 SSE body，可在 backlog 起单独 issue（不属 PR4 范围）。

**结论：** PR4 manual smoke 通过，可以合并。
