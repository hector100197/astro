# Contributing

Thanks for your interest in contributing! This project welcomes contributions from astrophysicists, software engineers, and educators alike.

## Ground rules

- **Physics first.** Any change to the kernel must preserve passing physics validation tests (Kepler 2-body, energy/momentum conservation, virial). CI enforces this.
- **Reproducibility is sacred.** Don't introduce non-determinism (uninitialized RNG seeds, floating-point reductions with non-fixed thread count, etc.) without an opt-in flag.
- **Architecture: hexagonal.** Domain has zero infrastructure dependencies. Adapters live in `infrastructure/`.
- **Test pyramid.** Unit > integration > E2E. Don't ship a kernel change without a Fortran-side test.

## Setting up

```bash
git clone https://github.com/hector100197/astro.git
cd astro
make dev-setup    # installs build tools, downloads dependencies
make test         # confirms environment is clean
```

Required toolchain:

- gfortran ≥ 13 (for Fortran 2018 features)
- Java 21 LTS
- Node.js 22 LTS
- Python 3.12+
- Docker (for postgres)

## Layout

```
kernel/        Fortran 2018 + OpenMP numerical core
services/      Spring Boot microservices (Java 21)
apps/          Angular 21 micro-frontends
python/        Python wrapper (f2py over kernel)
cli/           Standalone CLI binary
scenarios/     Pre-loaded YAML problem definitions
gaia/          Gaia DR3 ingestion
docs-site/     Sphinx documentation
paper/         JOSS submission
```

## Pull requests

1. Branch from `main`, name it `feat/short-description` or `fix/short-description`.
2. Open a draft PR early to surface design questions.
3. Make sure CI passes (`make test` locally first).
4. Update `CHANGELOG.md` under `[Unreleased]`.
5. If you change physics, update validation thresholds explicitly in `docs/physics.md`.

## Code of conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Be respectful, be specific in critique, prefer the simpler path that passes the science.
