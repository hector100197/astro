#!/usr/bin/env bash
# scripts/dev-setup.sh — one-time setup of build tools and dependencies.

set -euo pipefail

echo "Checking required toolchain..."

check() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "  MISSING: $1 ($2)"
    return 1
  fi
  echo "  OK:      $1"
}

ok=0

check gfortran "install via 'brew install gcc' on macOS, 'apt install gfortran' on Linux"  || ok=1
check java     "install Java 21 LTS (sdkman, brew, or your package manager)"               || ok=1
check mvn      "install Maven 3.9+ ('brew install maven' or sdkman)"                        || ok=1
check node     "install Node.js 22 LTS (nvm or brew)"                                       || ok=1
check python3  "install Python 3.10+ (brew, pyenv, or system)"                              || ok=1
check docker   "install Docker Desktop or compatible runtime"                               || ok=1
check make     "install GNU make (Xcode CLI tools on macOS)"                                || ok=1

if [ $ok -ne 0 ]; then
  echo
  echo "Some tools are missing. Install them and re-run this script."
  exit 1
fi

echo
echo "All required tools found. Proceeding to install per-layer dependencies..."

echo "==> npm install (apps/)"
for app in apps/shell-app apps/simulation-mfe; do
  (cd "$app" && npm install)
done

echo "==> Python wrapper venv (python/.venv)"
(cd python && \
  if [ ! -d .venv ]; then python3 -m venv .venv; fi && \
  ./.venv/bin/pip install -q --upgrade pip && \
  ./.venv/bin/pip install -q -e .[dev,notebooks]) && \
  echo "  OK: python/.venv ready (activate: source python/.venv/bin/activate)"

echo "==> Sphinx docs venv (docs-site/.venv)"
(cd docs-site && \
  if [ ! -d .venv ]; then python3 -m venv .venv; fi && \
  ./.venv/bin/pip install -q --upgrade pip && \
  ./.venv/bin/pip install -q -r requirements.txt) && \
  echo "  OK: docs-site/.venv ready"

echo "==> Maven sanity check"
(cd services && mvn -q -v >/dev/null) || echo "  (Maven not found in PATH — install via 'brew install maven' or sdkman)"

echo
echo "Dev setup complete. Try: make dev"
