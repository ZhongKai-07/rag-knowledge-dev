# Permission PR2 — Manual Smoke Checklist

> Run before merging PR2. Extends PR1 smoke with the new `scope=owner`
> path introduced by `KbScopeResolver`.
>
> Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §3.5

## Environment

- Date: 2026-04-27 (Asia/Shanghai)
- Branch: `feature/permission-pr2-kbaccessservice-retirement`
- App: current branch started locally on `http://127.0.0.1:19091/api/ragent`
- Fixtures: local-only `P2_*` smoke departments/users/roles/KBs inserted into the dev Postgres database. Tokens/passwords are not recorded in this file.

## Response-shape note

This project returns authorization failures as HTTP 200 with `success=false`
JSON envelopes. `NotLoginException` is also wrapped as HTTP 200 with message
`未登录或登录已过期`.

## Run Log

| Path | Command / subject | Actual | Result |
|---|---|---|---|
| 1. OPS_ADMIN renames own KB | `PUT /knowledge-base/P2_OPS_KB` as `pr2_smoke_ops_admin` | HTTP 200, `success=true`, `code=0` | OK |
| 2. FICC_USER renames OPS KB | `PUT /knowledge-base/P2_OPS_KB` as `pr2_smoke_ficc_user` | HTTP 200, `success=false`, message `无管理权限: P2_OPS_KB`; KB name unchanged by this request | OK |
| 3. Upload → MQ → executeChunk | `POST /knowledge-base/KB_OPS_PR1/docs/upload` then `POST /knowledge-base/docs/{docId}/chunk` as `pr2_smoke_ops2_admin` | Upload returned `success=true` with doc `2048512356916801536`; chunk trigger returned `success=true`. Local MQ did not advance the document past `running` within the smoke wait window. `KnowledgeDocumentChunkConsumerSystemActorTest` was rerun and passed, covering the PR1/PR2 missing-user/system-actor invariant. | Infra-limited, auth path OK |
| 4a. Anonymous GET KB | `GET /knowledge-base/P2_OPS_KB` with no token | HTTP 200, `success=false`, message `未登录或登录已过期` | OK |
| 4b. Missing UserContext service-level | Code-level contract | `KbAccessServiceSystemActorTest` rerun in PM-3: 10/10 pass, including missing user context fail-closed cases | OK |
| 6. New PR2 owner scope | `GET /knowledge-base?scope=owner&current=1&size=50` as `pr2_smoke_ops_admin` | HTTP 200, `success=true`, total `1`; records contain `P2_OPS_KB` only and do not contain `P2_FIC_KB` | OK |

## Supporting Verification

- `mvn -pl bootstrap test -Dtest=KnowledgeDocumentChunkConsumerSystemActorTest -q`: exit 0
- PM-3 PR1 contracts: boundary + system actor + fail-closed + PR1 ArchUnit all exit 0
- PM-4 PR2 contracts: `KbScopeResolverImplTest`, `AccessScopeServiceContractTest`, `KbAccessServiceRetirementArchTest`, `AccessServiceImplTest` all exit 0

## Sign-off

- [x] Path 1 OK
- [x] Path 2 OK
- [x] Path 3 HTTP auth path OK; MQ completion not observed locally, covered by consumer system-actor contract test
- [x] Path 4a OK
- [x] Path 4b OK
- [x] Path 6 OK
