# AGENTS.md

**Agent context for this repo lives in `CLAUDE.md`** (and module-local `*/CLAUDE.md` files).

Codex and other agents should read those files directly rather than this one.
This file used to duplicate `CLAUDE.md` content, but the fork drifted out of
sync as new gotchas and architectural changes landed. The single source of
truth is now `CLAUDE.md` — see it for build commands, architecture, RBAC
model, gotchas, and core business flows.

Quick pointers:

- Root project context: [`./CLAUDE.md`](./CLAUDE.md)
- Bootstrap module (where most development happens): [`./bootstrap/CLAUDE.md`](./bootstrap/CLAUDE.md)
- Framework (cross-cutting infra + security ports): [`./framework/CLAUDE.md`](./framework/CLAUDE.md)
- AI infra (LLM / embedding / rerank routing): [`./infra-ai/CLAUDE.md`](./infra-ai/CLAUDE.md)
- Frontend (React + Vite): [`./frontend/CLAUDE.md`](./frontend/CLAUDE.md)
