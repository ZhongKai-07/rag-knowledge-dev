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
