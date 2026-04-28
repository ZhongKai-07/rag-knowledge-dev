#!/usr/bin/env bash
# PR4 verification: RetrievalScopeBuilder isolation + mapper retirement.
# Spec: docs/superpowers/specs/2026-04-28-permission-pr4-retrieval-scope-builder-design.md §3.2
# Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 Stage B / PR4

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${REPO_ROOT}"

failures=0

if command -v rg >/dev/null 2>&1 && rg --version >/dev/null 2>&1; then
  USE_RG=1
else
  USE_RG=0
fi

if [ "${USE_RG}" -eq 0 ] && ! git --version >/dev/null 2>&1; then
  echo "ERROR: neither executable rg nor git grep is available; cannot run PR4 grep gates." >&2
  exit 2
fi

grep_files() {
  local pattern="$1"
  shift
  if [ "${USE_RG}" -eq 1 ]; then
    rg -l "${pattern}" "$@" || true
  else
    git grep -l -E "${pattern}" -- "$@" || true
  fi
}

grep_lines() {
  local pattern="$1"
  shift
  if [ "${USE_RG}" -eq 1 ]; then
    rg -n "${pattern}" "$@" || true
  else
    git grep -n -E "${pattern}" -- "$@" || true
  fi
}

grep_quiet() {
  local pattern="$1"
  shift
  local matches
  matches="$(grep_lines "${pattern}" "$@")"
  [ -n "${matches}" ]
}

grep_count() {
  local pattern="$1"
  shift
  grep_lines "${pattern}" "$@" | awk 'END {print NR+0}'
}

# Gate 1: PR4-1 - new RetrievalScope( only appears in the production allowlist.
EXPECTED_CTOR=$(cat <<'EOF'
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilderImpl.java
EOF
)
ACTUAL_CTOR=$(grep_files 'new RetrievalScope\(' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent | sort || true)
if [ "${ACTUAL_CTOR}" != "${EXPECTED_CTOR}" ]; then
  echo "FAIL: PR4-1 - 'new RetrievalScope(' constructor used outside allowlist:"
  echo "Expected:"
  echo "${EXPECTED_CTOR}"
  echo "Actual:"
  echo "${ACTUAL_CTOR}"
  failures=1
fi

# Gate 2: PR4-3 - rag/core/retrieve, including channel/, must not depend on KnowledgeBaseMapper.
if grep_quiet 'import.*knowledge\.dao\.mapper\.KnowledgeBaseMapper' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve; then
  echo "FAIL: PR4-3 - rag/core/retrieve still imports KnowledgeBaseMapper:"
  grep_lines 'import.*knowledge\.dao\.mapper\.KnowledgeBaseMapper' \
    bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve
  failures=1
fi

# Gate 3: PR4-4 - getMaxSecurityLevelsForKbs has one rag-domain caller: RetrievalScopeBuilderImpl.
COUNT=$(grep_count 'getMaxSecurityLevelsForKbs\(' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag)
if [ "${COUNT}" -ne 1 ]; then
  echo "FAIL: PR4-4 - getMaxSecurityLevelsForKbs called ${COUNT} times in rag (expected 1):"
  grep_lines 'getMaxSecurityLevelsForKbs\(' bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag
  failures=1
fi

# Gate 4: review P1#2 - ChatForEvalService must not inject RetrievalScopeBuilder.
if grep_quiet 'private[[:space:]]+final[[:space:]]+RetrievalScopeBuilder' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalService.java; then
  echo "FAIL: review P1#2 - ChatForEvalService injects RetrievalScopeBuilder; eval must use sentinel scope:"
  grep_lines 'RetrievalScopeBuilder' bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/ChatForEvalService.java
  failures=1
fi

# Gate 5: review P1 - RetrievalScope.all( sentinel static factory production allowlist.
# Allowed production holders:
#   - RetrievalScope.java       (record factory itself)
#   - RetrievalScopeBuilderImpl (SUPER_ADMIN production request path)
#   - EvalRunExecutor           (eval/system path holder, spec §15.3)
EXPECTED_SENTINEL=$(cat <<'EOF'
bootstrap/src/main/java/com/knowledgebase/ai/ragent/eval/service/EvalRunExecutor.java
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScopeBuilderImpl.java
EOF
)
ACTUAL_SENTINEL=$(
  {
    grep_files 'RetrievalScope\.all\(' bootstrap/src/main/java/com/knowledgebase/ai/ragent
    grep_files 'static[[:space:]]+RetrievalScope[[:space:]]+all[[:space:]]*\(' \
      bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/scope/RetrievalScope.java
  } | sort -u || true
)
if [ "${ACTUAL_SENTINEL}" != "${EXPECTED_SENTINEL}" ]; then
  echo "FAIL: review P1 - 'RetrievalScope.all(' static factory called outside allowlist:"
  echo "Expected (production callers):"
  echo "${EXPECTED_SENTINEL}"
  echo "Actual:"
  echo "${ACTUAL_SENTINEL}"
  echo ""
  echo "If a new caller legitimately needs sentinel scope, add it to the allowlist and spec §1.2 PR4-1."
  failures=1
fi

if [ "${failures}" -ne 0 ]; then
  exit 1
fi

echo "PR4 grep gates passed"
