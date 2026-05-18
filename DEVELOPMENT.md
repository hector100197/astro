# Development guide

For **users** (run the simulator, import a Gaia cluster, read a report)
the right entry point is [README.md](README.md). This file is for people
who want to **modify** the kernel, services, or frontends.

It assumes you completed Path C in [INSTALL.md](INSTALL.md#path-c-prerequisites)
so every required toolchain is on your `PATH`.

## Repository layout

```
astro/
├── kernel/              Fortran 2018 + OpenMP numerical kernel (libnbody.so/.dylib)
├── services/            Java 21 / Spring Boot microservices (multi-module Maven)
│   ├── shared-contracts/   DTOs shared across services
│   ├── simulation-service/ Live WebSocket + batch jobs + validation + reports
│   └── export-service/     HDF5 export endpoint
├── apps/                Angular 21 micro-frontends (Native Federation)
│   ├── shell-app/          :4200 — host, loads remotes at runtime
│   └── simulation-mfe/     :4201 — viewer + batch drawer + report downloads
├── python/              astro_nbody Python wrapper (ctypes over libnbody)
├── scenarios/           YAML scenario catalog (consumed by all layers)
├── tests/e2e/           Playwright end-to-end specs
├── paper/               JOSS paper draft + figures
├── docs-site/           Sphinx documentation source
├── infrastructure/      Compose files, init scripts
├── .github/workflows/   CI: 7 per-layer pipelines
├── scripts/             dev-setup.sh, dev.sh, build-all.sh, test-all.sh
└── data/                Generated HDF5 files (git-ignored)
```

## Per-layer development

### Kernel (Fortran)

```bash
# Build
make -C kernel
# → kernel/build/libnbody.dylib (macOS) or .so (Linux)

# Tests
make -C kernel test
```

The build picks up the system `gfortran` automatically. The OpenMP flag
is enabled by default; tune the thread count at runtime via the
`OMP_NUM_THREADS` environment variable.

Two contracts the rest of the stack relies on:

- C-ABI symbols `nbody_init_plummer`, `nbody_step`, plus the multi-snapshot
  HDF5 entry points (`open_run` / `append_snapshot` / `close_run`). Don't
  change names without updating `services/simulation-service/.../FortranKernelLoader.java`.
- A single `Simulation` state held in module-level Fortran arrays —
  one run at a time per loaded library.

### Backend (Java)

```bash
# Pin Java 21 (the FFM preview profile auto-activates on JDK 21)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
export PATH=$JAVA_HOME/bin:$PATH

# Build + test all services
cd services
mvn -B verify
```

Useful run modes:

| Command | What it does |
|---|---|
| `make run-sim-real` | Builds the kernel + runs `simulation-service` with the real Fortran kernel |
| `make run-sim-mock` | Runs `simulation-service` with the `mock` profile (no Postgres, no Fortran — handy for frontend-only iteration) |
| `make run-sim-dev` | Real kernel without Postgres (`dev` profile) — Sem 3 leftover, kept for quick physics debugging |
| `make run-export` | Runs `export-service` (:8082) |

Profiles in `services/simulation-service/src/main/resources/application.yml`:

- *(default)* — Postgres + Fortran kernel + Flyway migrations
- `mock` — disables JPA + FFM
- `dev` — disables JPA only, keeps the real kernel

### Frontend (Angular)

```bash
cd apps/simulation-mfe
npm ci
npm start                  # ng serve --port 4201
```

For the shell-app + MFEs together, use the top-level `make dev` script
which wires the federation manifests.

Tests are run by the Playwright suite in `tests/e2e/` rather than per-app
Karma, on purpose — the federated stack is more meaningful to test
end-to-end. To add Angular component tests you would need to provision
karma/jest in each app; the workflow file is set up so the test step is
intentionally skipped today.

### Python wrapper

```bash
cd python
source .venv/bin/activate
pytest tests/             # smoke tests over the native kernel binding
```

The wrapper is intentionally thin — it's a ctypes facade plus YAML
scenario parsing. Heavy lifting (analysis, plotting) lives in
`astro_nbody/report_plots.py` and is shared with the JVM service
(spawned as a subprocess).

### E2E tests (Playwright)

```bash
cd tests/e2e
npm ci
npx playwright install --with-deps    # one-time download of browsers
npx playwright test
```

The full stack must already be up (`make dev` in another terminal).
There are 9 specs covering smoke, the batch-job validation flow, and
report downloads.

## CI

Each of the 7 workflows in `.github/workflows/` triggers on changes to
its own layer's paths:

| Workflow | Triggers on | What it does |
|---|---|---|
| `kernel.yml` | `kernel/**` | gfortran build + kernel tests |
| `services.yml` | `services/**` | gfortran + JDK 21 + `mvn verify` (incl. PhysicsValidationTest via FFM) |
| `frontends.yml` | `apps/**` | `npm ci` + `npm run build` for each MFE |
| `python.yml` | `python/**` | `pip install -e .[dev]` + `pytest` |
| `docs.yml` | `docs-site/**` | Sphinx build + GitHub Pages deploy |
| `paper.yml` | `paper/**` | JOSS draft PDF build via openjournals action |
| `e2e.yml` | any | Full stack up via `scripts/dev.sh` + Playwright |

Locally the equivalent of CI is:

```bash
make test       # invokes scripts/test-all.sh — kernel + Java + Python + e2e
```

## Common gotchas

- **`mvn spring-boot:run` from `services/`** fails with "no main class". The
  `services/` parent module is `<packaging>pom</packaging>`; you must
  `cd` into a concrete service (`simulation-service` / `export-service`).
- **JDK version drift** — the `--enable-preview` flag is gated on JDK 21
  by the `jdk21-ffm-preview` profile in the parent `pom.xml`. Building
  with JDK 22+ or 25 produces class files the live JVM rejects with
  `UnsupportedClassVersionError`. Always pin Java 21 before `mvn`.
- **Maven snapshot resolution** — the modules depend on
  `shared-contracts-0.1.0-SNAPSHOT`. Run `mvn install` once from
  `services/` to publish the snapshot to your local `~/.m2` before
  building any one module.
- **HDF5 + FFM lock** — the Fortran kernel's libhdf5 is grabbed exclusively
  for the JVM's lifetime; no other libhdf5 inside or spawned-from this
  JVM can open the same file. The plot generator and the Gaia importer
  both work around this by reading **JSON** instead of HDF5 — preserve
  that pattern when adding subprocesses.
- **Postgres port** — host `5433` (not `5432`) mirrors the docker-compose
  mapping to avoid collisions with any other Postgres you have running.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). PRs should keep CI green on every
layer they touch.
