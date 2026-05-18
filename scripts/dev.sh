#!/usr/bin/env bash
# scripts/dev.sh — bring up the full local dev stack.
#
# Spins up:
#   - postgres (via docker compose)
#   - simulation-service (Spring Boot, :8081)
#   - export-service (Spring Boot, :8082)
#   - shell-app (Angular, :4200)
#   - simulation-mfe (Angular, :4201)
#
# Press Ctrl+C to stop everything.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

cleanup() {
  echo
  echo "Shutting down..."
  jobs -p | xargs -I{} kill {} 2>/dev/null || true
  docker compose down
}
trap cleanup EXIT INT TERM

echo "[1/4] Starting postgres..."
docker compose up -d
sleep 2

echo "[2/4] Building kernel (if changed)..."
make -C kernel >/dev/null

echo "[3/4] Starting backend services..."
(cd services/simulation-service && mvn -q spring-boot:run) &
(cd services/export-service && mvn -q spring-boot:run) &

echo "[4/4] Starting frontend MFEs..."
(cd apps/shell-app && npm run start) &
(cd apps/simulation-mfe && npm run start) &

echo
echo "All services starting up. Open:"
echo "  http://localhost:4200  shell-app"
echo "  http://localhost:8081/actuator/health  simulation-service"
echo "  http://localhost:8082/actuator/health  export-service"
echo
echo "Press Ctrl+C to stop everything."
wait
