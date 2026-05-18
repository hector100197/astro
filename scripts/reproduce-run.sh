#!/usr/bin/env bash
# scripts/reproduce-run.sh — re-execute a past run from its manifest.
#
# Usage:
#   reproduce-run.sh <run_id|manifest.json>
#
# Steps:
#   1. Load manifest (from PostgreSQL by run_id, or from a JSON file)
#   2. Verify kernel git SHA matches; warn if not
#   3. Recompile kernel with recorded compile flags
#   4. Verify resulting binary's SHA-256 matches
#   5. Pin OMP_NUM_THREADS, RNG seed, and parameters
#   6. Execute and compare HDF5 output bit-for-bit (or document diffs)

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "usage: $0 <run_id|manifest.json>"
  exit 2
fi

INPUT="$1"
echo "TODO Sem 4: implement manifest loading from"
echo "  - Postgres simulation_runs.manifest WHERE id = '$INPUT', or"
echo "  - JSON file at '$INPUT'"
echo
echo "Then verify SHA, recompile, run, diff."
exit 0
