# Reproducibility

> Every run produced by this software is bit-exact reproducible from a small manifest. This is a non-negotiable design property — papers citing results obtained with this code must be re-runnable five years from now.

## The manifest

Every simulation run, regardless of mode (Live, Capture, Headless), persists a JSON manifest into:

- the HDF5 file's `/Header/Manifest` group
- a JSON column in PostgreSQL `simulation_runs.manifest`

The manifest schema:

```json
{
  "run_id": "uuid-v4",
  "created_at": "ISO-8601",
  "kernel": {
    "git_sha": "abc1234...",
    "binary_sha256": "deadbeef...",
    "compiler": "gfortran 14.2.0",
    "compile_flags": "-O3 -march=native -fopenmp -fimplicit-none",
    "openmp_threads": 8
  },
  "scenario": {
    "source": "scenarios/pleiades.yaml" | "gaia/M67" | "custom",
    "scenario_sha256": "..."
  },
  "parameters": {
    "n_bodies": 3000,
    "dt": 0.001,
    "softening": 0.01,
    "n_steps": 10000,
    "integrator": "leapfrog",
    "force_calculator": "brute_force_o2",
    "initial_condition": "plummer",
    "rng_seed": 42,
    "units": "henon"
  },
  "hardware": {
    "cpu": "Apple M3 Max",
    "cores": 14,
    "ram_gb": 36,
    "os": "Darwin 25.4.0"
  },
  "software": {
    "java_version": "21.0.5",
    "spring_boot_version": "3.4.2",
    "service_git_sha": "...",
    "service_name": "simulation-service" | "export-service"
  }
}
```

## Reproducing a past run

```bash
./scripts/reproduce-run.sh <run_id_or_manifest.json>
```

The script:

1. Verifies the kernel git SHA is currently checked out (or warns if not).
2. Recompiles the kernel with the recorded compile flags.
3. Verifies the resulting binary's SHA-256 matches.
4. Loads the recorded scenario YAML / Gaia query.
5. Executes the run with the recorded parameters and RNG seed.
6. Compares output snapshots bit-for-bit against the original HDF5.

A run is **bit-exact reproducible** if:

- Kernel binary SHA matches.
- OpenMP thread count is identical (because parallel reductions of floating-point are not associative).
- Hardware floating-point determinism is preserved (modern x86_64 and ARM64 are deterministic for our op set).

If thread count differs, results agree to floating-point round-off but not bit-exact. We document this clearly in the comparison report.

## What you can NOT do and still claim reproducibility

- Use a different compiler version → loses bit-exactness (different SIMD codegen).
- Disable `-march=native` → loses bit-exactness.
- Change OpenMP thread count → loses bit-exactness (still numerically valid).
- Run on a different ISA (x86 vs ARM) → loses bit-exactness.

For paper-grade reproducibility, the manifest pins all the above, and the comparison report explicitly states which dimensions matched.

## FAIR data

Every published run satisfies FAIR:

- **F**indable: UUIDv4 run_id; optional DOI via Zenodo for archived runs.
- **A**ccessible: HDF5 + manifest downloadable from the UI / API.
- **I**nteroperable: HDF5 layout follows GADGET-like convention readable by `yt-project`, `h5py`, `astropy`.
- **R**eusable: MIT license, manifest enables exact replay.
