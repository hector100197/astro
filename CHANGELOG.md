# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-05-11

First public release. The full vertical slice works end-to-end: simulate →
visualize → batch → validate → export.

### Added

#### Numerical kernel
- Fortran 2018 + OpenMP brute-force O(N²) integrator with the symplectic
  leapfrog scheme and Plummer softening
- Plummer initial-condition generator (seedable, deterministic)
- Multi-snapshot HDF5 writer with a GADGET-like layout
  (`/Snapshots/NNNNN/{Coordinates,Velocities,Masses}` + `/Header` group)
- C-ABI entry points consumed from the JVM via the Foreign Function & Memory
  API (zero-copy)

#### Backend (Spring Boot)
- `simulation-service` (:8081): live WebSocket streaming + batch jobs REST API
- `export-service` (:8082): HDF5 export endpoint
- Hexagonal architecture (`domain` / `application` / `infrastructure`)
- PostgreSQL persistence with Flyway migrations
- 9 pre-loaded scenarios: `pleiades`, `gaia_pleiades`, `gaia_m67`,
  `gaia_hyades`, `aarseth_standard`, `three_body_figure8`,
  `three_body_pythagorean`, `henon_heiles`, `solar_system`

#### Frontend (Angular 21)
- `shell-app` (:4200), `simulation-mfe` (:4201), `export-mfe` (:4202)
- Native Federation for independently deployable MFEs
- Three-mode UI: live visualization, video capture, headless batch
- Time scrubber with Lagrangian radii overlay
- Comparison mode for side-by-side runs
- Click-to-follow particle with trail rendering

#### Post-processing & reports
- In-memory analyser computes per-snapshot energetics, Lagrangian radii,
  binary catalog (O(N²) gated by separation cutoff), and escaper events
- Downloadable report bundle per finished job: `report.pdf` (PDFBox),
  `report.tex` (compilable LaTeX), `report.json` (raw analysis)
- `GET /api/jobs/{id}/report.{pdf,tex,json}` endpoints
- Top-10 tightest binaries table with hard/soft flag

#### Validation badge
- Six-check `ValidationService`: `|ΔE/E₀|` final and worst, `|ΔL/L₀|`,
  virial offset `|⟨Q⟩−1|` on the second half, half-mass radius stability,
  escaper fraction
- Aggregate verdict `pass` / `warn` / `fail` rendered as a coloured badge in
  the UI with an expandable per-check panel
- `GET /api/jobs/{id}/validation` serves the structured report

#### Reproducibility
- JSON manifest in both PostgreSQL and `/Header/Manifest` group: git SHA,
  binary SHA-256, compiler version, OpenMP thread count, hardware
  description, scenario YAML hash, all simulation parameters incl. RNG seed
- Bit-exact runs given matching kernel binary and thread count

#### Python wrapper & CLI
- `pip install astro-nbody` package with thin `_kernel.py` FFI shim
- `nbody-sim` standalone CLI
- Gaia DR3 cluster importer (by name)

#### Quality
- 41 unit tests in the simulation-service (ValidationService, analyser,
  renderers, FFM-driven Kepler closure, Plummer energy conservation)
- 9 Playwright end-to-end specs (smoke, badge flow, report downloads)
- 7 GitHub Actions workflows (kernel, services, frontends, python, docs,
  paper, e2e)
- JOSS paper draft with a validation overview figure derived from a real
  `pleiades` run (N=3000)

[0.1.0]: https://github.com/hector100197/astro/releases/tag/v0.1.0
