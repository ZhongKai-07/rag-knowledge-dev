#!/usr/bin/env bash
# PR2 verification: KbAccessService god-service retirement complete.
# Spec: docs/superpowers/specs/2026-04-27-permission-pr2-kbaccessservice-retirement-design.md §3.4
# Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 阶段 A · PR2

set -euo pipefail
TARGET='bootstrap/src/main/java'

# Prefer rg, as planned. In this Windows/Git Bash environment rg can resolve to a
# non-Bash-executable WindowsApps binary; when rg cannot execute, fall back to
# git grep with equivalent file-level / regex semantics.
grep_files() {
  local rg_pattern="$1"
  local git_pattern="$2"
  local target="$3"
  local output
  local status

  if command -v rg >/dev/null 2>&1; then
    set +e
    output=$(rg -l "${rg_pattern}" "${target}" 2>&1)
    status=$?
    set -e
    if [ "${status}" -eq 0 ]; then
      printf '%s\n' "${output}"
      return 0
    fi
    if [ "${status}" -eq 1 ]; then
      return 1
    fi
    echo "WARN: rg execution failed (exit ${status}); falling back to git grep -l." >&2
    echo "WARN: rg output: ${output}" >&2
  else
    echo "WARN: rg not found on PATH; falling back to git grep -l." >&2
  fi

  set +e
  output=$(git grep -l -E -- "${git_pattern}" -- "${target}")
  status=$?
  set -e
  if [ "${status}" -eq 0 ]; then
    printf '%s\n' "${output}"
    return 0
  fi
  if [ "${status}" -eq 1 ]; then
    return 1
  fi
  echo "ERROR: git grep fallback failed (exit ${status})." >&2
  return "${status}"
}

grep_matches() {
  local rg_pattern="$1"
  local git_pattern="$2"
  local target="$3"
  local output
  local status

  if command -v rg >/dev/null 2>&1; then
    set +e
    output=$(rg -n "${rg_pattern}" "${target}" 2>&1)
    status=$?
    set -e
    if [ "${status}" -eq 0 ]; then
      printf '%s\n' "${output}"
      return 0
    fi
    if [ "${status}" -eq 1 ]; then
      return 1
    fi
    echo "WARN: rg execution failed (exit ${status}); falling back to git grep -n." >&2
    echo "WARN: rg output: ${output}" >&2
  else
    echo "WARN: rg not found on PATH; falling back to git grep -n." >&2
  fi

  set +e
  output=$(git grep -n -E -- "${git_pattern}" -- "${target}")
  status=$?
  set -e
  if [ "${status}" -eq 0 ]; then
    printf '%s\n' "${output}"
    return 0
  fi
  if [ "${status}" -eq 1 ]; then
    return 1
  fi
  echo "ERROR: git grep fallback failed (exit ${status})." >&2
  return "${status}"
}

# Gate 1: file-level allowlist — KbAccessService string only in 2 files
EXPECTED=$(cat <<'EOF'
bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/KbAccessService.java
bootstrap/src/main/java/com/nageoffer/ai/ragent/user/service/impl/KbAccessServiceImpl.java
EOF
)
ACTUAL=$(grep_files "KbAccessService" "KbAccessService" "${TARGET}" | sort || true)

if [ "${ACTUAL}" != "${EXPECTED}" ]; then
  echo "FAIL: KbAccessService referenced in unexpected files."
  echo "Expected:"
  echo "${EXPECTED}"
  echo "Actual:"
  echo "${ACTUAL}"
  exit 1
fi

# Gate 2: injection-level — no field of type KbAccessService in any class
FIELD_MATCHES=$(grep_matches \
  "private\s+final\s+KbAccessService\b" \
  "private[[:space:]]+final[[:space:]]+KbAccessService([^[:alnum:]_]|$)" \
  "${TARGET}" || true)
if [ -n "${FIELD_MATCHES}" ]; then
  echo "FAIL: KbAccessService still injected as field:"
  echo "${FIELD_MATCHES}"
  exit 1
fi

echo "OK: KbAccessService retired."
echo "  - Only KbAccessService.java + KbAccessServiceImpl.java reference the type"
echo "  - No 'private final KbAccessService' field anywhere in main code"
