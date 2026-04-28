#!/usr/bin/env bash
# PR3 verification: leak-free access calculator and handler boundaries.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${REPO_ROOT}"

if ! command -v rg >/dev/null 2>&1; then
  echo "ERROR: ripgrep (rg) not found on PATH; cannot run PR3 grep gates." >&2
  exit 2
fi

failures=0

assert_absent() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  local status

  set +e
  matches="$(rg -n "${pattern}" "$@" 2>&1)"
  status=$?
  set -e

  if [ "${status}" -eq 0 ]; then
    echo "FAIL: ${label}"
    echo "${matches}"
    failures=1
    return
  fi
  if [ "${status}" -ne 1 ]; then
    echo "ERROR: rg failed while checking ${label}" >&2
    echo "${matches}" >&2
    exit "${status}"
  fi
}

assert_absent \
  "PR3-3 AccessServiceImpl must not call kbReadAccess directly" \
  "kbReadAccess\." \
  "bootstrap/src/main/java/com/knowledgebase/ai/ragent/user/service/impl/AccessServiceImpl.java"

assert_absent \
  "PR3-5 singular getMaxSecurityLevelForKb must be absent" \
  "getMaxSecurityLevelForKb\b" \
  "bootstrap/src/main/java" \
  "bootstrap/src/test/java"

assert_absent \
  "PR3-2 KbReadAccessPort must be current-user-only, without String userId" \
  "String\s+userId" \
  "framework/src/main/java/com/knowledgebase/ai/ragent/framework/security/port/KbReadAccessPort.java"

assert_absent \
  "PR3-4 handler package must not import UserContext" \
  "import\s+com\.knowledgebase\.ai\.ragent\.framework\.context\.UserContext;" \
  "bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/service/handler"

if [ "${failures}" -ne 0 ]; then
  exit 1
fi

echo "PR3 grep gates passed"
