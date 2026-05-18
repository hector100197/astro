#!/usr/bin/env python3
"""nbody-sim — CLI entry point.

Usage:
    nbody-sim --config scenarios/pleiades.yaml --output run.h5 --steps 100000

TODO Sem 5: full implementation.
"""

import argparse
import sys


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="nbody-sim",
        description="Headless N-body cluster simulator (CLI for HPC clusters and batch jobs)."
    )
    parser.add_argument("--config", required=True, help="Path to scenario YAML")
    parser.add_argument("--output", required=True, help="Output HDF5 path")
    parser.add_argument("--steps", type=int, default=10000, help="Number of integration steps")
    parser.add_argument("--threads", type=int, default=0, help="OpenMP threads (0 = auto)")
    parser.add_argument("--seed", type=int, default=42, help="RNG seed")
    args = parser.parse_args()

    print(f"[nbody-sim] config={args.config} output={args.output} steps={args.steps}")
    print("[nbody-sim] TODO: dispatch to astro_nbody.Simulation. Not implemented yet.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
