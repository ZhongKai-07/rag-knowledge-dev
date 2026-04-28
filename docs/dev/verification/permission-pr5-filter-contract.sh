#!/usr/bin/env bash
# PR5 verification: Metadata Filter Contract Hardening.
# Spec: docs/superpowers/specs/2026-04-28-permission-pr5-metadata-filter-hardening-design.md
# Roadmap: docs/dev/design/2026-04-26-permission-roadmap.md §3 Stage B
#
# 4 grep gates lock the c1/c2 invariants:
#   G1: new MetadataFilter( only allowed in DefaultMetadataFilterBuilder
#       (and the record's own javadoc) — channel/engine/retriever can't
#       bypass the builder.
#   G2: OpenSearchRetrieverService must contain enforceFilterContract.
#   G3: enforceFilterContract must run BEFORE the first try in doSearch
#       (catch (Exception) e -> List.of() must not swallow contract violation).
#   G4: catch (IllegalStateException) explicit rethrow defends future refactors.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${REPO_ROOT}"

if command -v rg >/dev/null 2>&1 && rg --version >/dev/null 2>&1; then
  USE_RG=1
else
  USE_RG=0
fi

if [ "${USE_RG}" -eq 0 ] && ! git --version >/dev/null 2>&1; then
  echo "ERROR: neither executable rg nor git grep is available; cannot run PR5 grep gates." >&2
  exit 2
fi

failures=0

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

RETRIEVER_FILE="bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/OpenSearchRetrieverService.java"

# Gate 1: PR5 c1 - 'new MetadataFilter(' must only appear in the production
# whitelist: DefaultMetadataFilterBuilder (the only legitimate caller) and
# the record's own javadoc examples in MetadataFilter.java.
EXPECTED_CTOR=$(cat <<'EOF'
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/MetadataFilter.java
bootstrap/src/main/java/com/knowledgebase/ai/ragent/rag/core/retrieve/filter/DefaultMetadataFilterBuilder.java
EOF
)
ACTUAL_CTOR=$(grep_files 'new MetadataFilter\(' \
  bootstrap/src/main/java/com/knowledgebase/ai/ragent | tr '\\' '/' | sort || true)
if [ "${ACTUAL_CTOR}" != "${EXPECTED_CTOR}" ]; then
  echo "FAIL: PR5 G1 - 'new MetadataFilter(' constructed outside whitelist:"
  echo "Expected:"
  echo "${EXPECTED_CTOR}"
  echo "Actual:"
  echo "${ACTUAL_CTOR}"
  failures=1
fi

# Gate 2: PR5 c2 - OpenSearchRetrieverService must declare enforceFilterContract.
if ! grep_quiet 'enforceFilterContract' "${RETRIEVER_FILE}"; then
  echo "FAIL: PR5 G2 - OpenSearchRetrieverService missing enforceFilterContract guard"
  failures=1
fi

# Gate 3: PR5 c2 - enforceFilterContract(metadataFilters, ...) must run BEFORE
# the first 'try {' inside doSearch. This file also has try blocks in init(),
# so we anchor scope to lines after the doSearch signature.
DO_SEARCH_LINE=$(awk '/private List<RetrievedChunk> doSearch\(/ { print NR; exit }' \
  "${RETRIEVER_FILE}")
DO_SEARCH_LINE=${DO_SEARCH_LINE:-0}
if [ "${DO_SEARCH_LINE}" = "0" ]; then
  echo "FAIL: PR5 G3 - cannot find doSearch method signature in ${RETRIEVER_FILE}"
  failures=1
else
  ENFORCE_LINE=$(awk -v start="${DO_SEARCH_LINE}" \
    'NR > start && /enforceFilterContract\(metadataFilters/ { print NR; exit }' \
    "${RETRIEVER_FILE}")
  TRY_LINE=$(awk -v start="${DO_SEARCH_LINE}" \
    'NR > start && /^[[:space:]]+try[[:space:]]*\{/ { print NR; exit }' \
    "${RETRIEVER_FILE}")
  ENFORCE_LINE=${ENFORCE_LINE:-0}
  TRY_LINE=${TRY_LINE:-0}
  if [ "${ENFORCE_LINE}" = "0" ]; then
    echo "FAIL: PR5 G3 - enforceFilterContract(metadataFilters, ...) not invoked inside doSearch"
    failures=1
  elif [ "${TRY_LINE}" = "0" ]; then
    echo "FAIL: PR5 G3 - no 'try {' block found inside doSearch"
    failures=1
  elif [ "${ENFORCE_LINE}" -ge "${TRY_LINE}" ]; then
    echo "FAIL: PR5 G3 - enforceFilterContract must precede the first try in doSearch"
    echo "  doSearch@${DO_SEARCH_LINE}, enforce@${ENFORCE_LINE}, try@${TRY_LINE}"
    echo "  (if enforce sits inside try, catch (Exception) will swallow it into List.of())"
    failures=1
  fi
fi

# Gate 4: PR5 c2 - catch (IllegalStateException) rethrow defends future refactors
# that might widen the catch (Exception) scope to include contract violations.
if ! grep_quiet 'catch \(IllegalStateException' "${RETRIEVER_FILE}"; then
  echo "FAIL: PR5 G4 - OpenSearchRetrieverService missing 'catch (IllegalStateException)' rethrow defence"
  failures=1
fi

if [ "${failures}" -ne 0 ]; then
  exit 1
fi

echo "PR5 grep gates passed"
