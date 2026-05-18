"""
``nbody-sim`` — command-line entry point.

Run a simulation headlessly from a scenario YAML:

    nbody-sim --scenario pleiades --steps 10000 --output run.h5
    nbody-sim --config path/to/custom.yaml --output run.h5

Without ``--scenario`` / ``--config`` it falls back to a built-in Plummer
sphere with the given N and seed::

    nbody-sim --n 1500 --steps 5000 --output run.h5
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .simulation import Simulation
from .scenarios import load_scenario, list_scenarios


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="nbody-sim",
        description="Headless N-body cluster simulator (Fortran kernel via Python wrapper)."
    )
    src = p.add_mutually_exclusive_group()
    src.add_argument("--scenario", help=f"Named scenario from the catalog. Available: {', '.join(list_scenarios())}")
    src.add_argument("--config", type=Path, help="Path to a custom scenario YAML")

    p.add_argument("-o", "--output", type=Path, required=True, help="HDF5 output path")
    p.add_argument("-n", "--n", type=int, default=1500, help="Number of bodies (ignored if --scenario / --config given)")
    p.add_argument("-s", "--steps", type=int, default=10_000, help="Number of integration steps")
    p.add_argument("--dt", type=float, default=0.005, help="Timestep (Hénon units)")
    p.add_argument("--softening", type=float, default=0.01, help="Plummer softening length")
    p.add_argument("--seed", type=int, default=42, help="RNG seed for initial conditions")
    p.add_argument("--quiet", action="store_true", help="Suppress progress bar")
    p.add_argument("--diagnostics", action="store_true",
                   help="Print initial and final K, U, E, virial ratio")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)

    # Resolve config: --scenario | --config | defaults
    if args.scenario or args.config:
        cfg = load_scenario(args.scenario) if args.scenario else _load_yaml(args.config)
        n = int(cfg["n_bodies"])
        sim_cfg = cfg.get("simulation", {})
        dt = float(sim_cfg.get("dt", args.dt))
        softening = float(sim_cfg.get("softening", args.softening))
        steps = int(sim_cfg.get("n_steps", args.steps))
        seed = int(cfg.get("initial_condition", {}).get("rng_seed", args.seed))
        scenario_name = cfg.get("name", "custom")
    else:
        n = args.n
        dt = args.dt
        softening = args.softening
        steps = args.steps
        seed = args.seed
        scenario_name = "default-plummer"

    print(f"[nbody-sim] scenario={scenario_name} N={n} dt={dt} softening={softening} steps={steps} seed={seed}")

    t0 = time.perf_counter()
    sim = Simulation(n=n, scenario="plummer", seed=seed, softening=softening)
    t_init = time.perf_counter() - t0
    print(f"[nbody-sim] Initialized Plummer in {t_init*1000:.1f} ms")

    if args.diagnostics:
        d = sim.diagnostics()
        print(f"[nbody-sim] t=0       K={d.K:+.6f}  U={d.U:+.6f}  E={d.E:+.6f}  Q={d.Q:.4f}")

    t0 = time.perf_counter()
    sim.run(steps=steps, dt=dt, progress=not args.quiet)
    t_run = time.perf_counter() - t0
    print(f"[nbody-sim] Integrated {steps} steps in {t_run:.1f} s ({steps/t_run:.0f} steps/sec)")

    if args.diagnostics:
        d = sim.diagnostics()
        print(f"[nbody-sim] t={sim.sim_time:.3f}  K={d.K:+.6f}  U={d.U:+.6f}  E={d.E:+.6f}  Q={d.Q:.4f}")

    out = sim.save(args.output)
    print(f"[nbody-sim] Wrote {out} ({out.stat().st_size:,} bytes)")
    return 0


def _load_yaml(path: Path):
    import yaml
    with path.open() as f:
        return yaml.safe_load(f)


if __name__ == "__main__":
    sys.exit(main())
