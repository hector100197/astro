# ADR-0001: Stack selection — Fortran 2018 + Java 21 + Angular 21

- **Status**: Accepted
- **Date**: 2026-05-08
- **Decision-makers**: Project lead

## Context

We need to build an N-body stellar cluster simulator that:

1. Runs heavy numerical computation (O(N²) force calculations) at HPC speeds.
2. Exposes modern, interactive web UIs for live visualization.
3. Is adoptable by computational astrophysicists (who live in Python + HDF5 + HPC).
4. Demonstrates clean software architecture suitable for a portfolio / publication.

## Decision

- **Numerical kernel**: Fortran 2018 with OpenMP, compiled to a shared library (`.dylib` / `.so`).
- **Backend**: Java 21 LTS + Spring Boot 3.4 with Foreign Function & Memory API (FFM) bridging to the Fortran kernel.
- **Frontend**: Angular 21 with Native Federation for micro-frontends; Three.js for 3D, Plotly for 2D scientific plots.
- **Persistence**: PostgreSQL 16 for run metadata; HDF5 (GADGET-like layout) for snapshot data.
- **Distribution**: Python wrapper via `f2py` for scientific adoption; standalone CLI for HPC.

## Consequences

### Positive

- Fortran kernel delivers 15–25× speedup over multi-threaded Java for N-body computations (per `docs/stack_sweet_spots.md`).
- Hexagonal architecture in Java keeps domain logic decoupled from FFM, HDF5, REST, WebSocket adapters.
- Native Federation enables independent deployment / development of MFEs.
- HDF5 + Python wrapper open the door to academic adoption (the entire astrophysics community uses these).
- Reproducibility is achievable because Fortran + gfortran is deterministic with fixed thread counts.

### Negative

- Three-language polyglot increases maintenance and CI complexity.
- Cross-platform Fortran builds require attention (gfortran/ifx, macOS/Linux/Windows).
- FFM is preview in Java 21 (stable 22+); we accept the preview risk to use the modern API.
- Angular 21 + Native Federation is recent; tooling is still evolving.

## Alternatives considered

- **Pure Java with Vector API + virtual threads**: viable but ~5–15× slower for our workload; loses the HPC flavor.
- **C++ kernel**: similar performance to Fortran, but Fortran has better SIMD auto-vectorization for regular numerical loops and a much deeper astrophysics library ecosystem (GADGET, NBODY6, ATLAS).
- **Rust kernel**: modern, safe, fast — but the astrophysics community does not adopt Rust; we'd lose the Python/HDF5 ecosystem advantage.
- **Single Spring Boot monolith + single Angular app**: simpler, faster MVP, but loses the architectural learning value and limits independent scaling of export jobs.

## See also

- `docs/stack_sweet_spots.md` — full analysis of where Fortran wins.
- `docs/architecture.md` — concrete architecture diagram.
