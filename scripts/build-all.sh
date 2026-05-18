#!/usr/bin/env bash
# scripts/build-all.sh — full build across all layers.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> kernel"
make -C kernel

echo "==> services"
(cd services && mvn -q -DskipTests package)

echo "==> apps/shell-app"
(cd apps/shell-app && npm install && npm run build)

echo "==> apps/simulation-mfe"
(cd apps/simulation-mfe && npm install && npm run build)

echo "==> apps/export-mfe"
(cd apps/export-mfe && npm install && npm run build)

echo "==> python wrapper"
(cd python && pip install -e . >/dev/null)

echo "==> docs site"
(cd docs-site && pip install -r requirements.txt >/dev/null && make html)

echo
echo "All artifacts built."
