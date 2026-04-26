#!/usr/bin/env bash
# Permission PR1 verification: assert no controller still calls kbAccessService.check*
# Run from repo root. Exit 1 if any inline check has leaked back into a controller.
#
# Spec: docs/superpowers/specs/2026-04-26-permission-pr1-controller-thinning-design.md §3.3

set -euo pipefail

PATTERN='kbAccessService\.(checkAccess|checkManageAccess|checkDocManageAccess|checkDocSecurityLevelAccess|checkKbRoleBindingAccess)\('
TARGET='bootstrap/src/main/java/com/nageoffer/ai/ragent'

if rg -n "${PATTERN}" "${TARGET}" -g '*Controller.java' --quiet; then
  echo "FAIL: kbAccessService.check* still present in controllers:"
  rg -n "${PATTERN}" "${TARGET}" -g '*Controller.java'
  exit 1
fi

echo "OK: no kbAccessService.check* found in controllers."
