#!/usr/bin/env bash
# scripts/test-all.sh — run all test suites across layers.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

failed=0

echo "==> kernel tests"
make -C kernel test || failed=1

echo "==> services tests"
(cd services && mvn -q test) || failed=1

echo "==> apps tests"
(cd apps/shell-app && npm test) || failed=1
(cd apps/simulation-mfe && npm test) || failed=1

echo "==> python tests"
(cd python && python -m pytest) || failed=1

echo "==> e2e tests"
if [ -f tests/e2e/playwright.config.ts ]; then
  (cd tests/e2e && npx playwright test) || failed=1
else
  echo "  (e2e tests not yet configured — skipping)"
fi

if [ $failed -ne 0 ]; then
  echo
  echo "FAILURE: one or more layers failed."
  exit 1
fi

echo
echo "All tests passed."
