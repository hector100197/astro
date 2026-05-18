# astro — N-body stellar cluster simulator

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20264301.svg)](https://doi.org/10.5281/zenodo.20264301)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![kernel](https://github.com/hector100197/astro/actions/workflows/kernel.yml/badge.svg)](https://github.com/hector100197/astro/actions/workflows/kernel.yml)
[![services](https://github.com/hector100197/astro/actions/workflows/services.yml/badge.svg)](https://github.com/hector100197/astro/actions/workflows/services.yml)
[![e2e](https://github.com/hector100197/astro/actions/workflows/e2e.yml/badge.svg)](https://github.com/hector100197/astro/actions/workflows/e2e.yml)

An open-source workbench for direct-summation N-body simulations of stellar
clusters. Combines a **Fortran 2018 + OpenMP** numerical kernel with a
**Java 21 / Spring Boot** service layer and an **Angular 21** micro-frontend.
Every finished run is automatically graded against literature tolerances
(NBODY6-grade / Marginal / Failed) and exported as a publication-ready
bundle: PDF report with embedded plots, LaTeX source, raw JSON, and HDF5
snapshots.

> 📖 **Full documentation:** see [docs-site/source/](docs-site/source/) or the
> rendered site at https://hector100197.github.io/astro/ *(after first release)*.
> Spanish version of this README: [README.es.md](README.es.md).

---

## Pick your path

Three different ways to use `astro`. Each has its own minimal prerequisites —
**you don't need to install everything**.

| You are… | You want… | Jump to |
|---|---|---|
| 🧑‍🔬 **An astrophysicist who lives in Python/Jupyter** | Call the kernel from a notebook, get HDF5 + numpy arrays back | [Path A — Python wrapper](#path-a--python-wrapper-fastest-path) |
| 🌐 **An astrophysicist who wants the GUI** | Launch & visualise runs interactively, download PDF reports | [Path B — Web UI](#path-b--web-ui) |
| 🛠️ **A developer / contributor** | Hack on the kernel, services, or frontends | [Path C — Full dev setup](#path-c--full-dev-setup) |

---

## Path A — Python wrapper (fastest path)

**Prerequisites** (5 min to install if you don't already have them):
- Python 3.10 or newer
- A C/Fortran compiler (`gfortran` on Linux/macOS)
- GNU `make`
- HDF5 with Fortran bindings (the kernel writes HDF5 snapshots)

**Install commands** for your OS in [INSTALL.md](INSTALL.md#path-a-prerequisites).

### Build the kernel and install the wrapper

```bash
git clone https://github.com/hector100197/astro.git
cd astro

# Compile the Fortran kernel (one-time, ~10 s)
make -C kernel

# Create a Python venv and install the wrapper in editable mode
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev,notebooks]'
```

### Your first simulation (4 lines of Python)

```python
import astro_nbody as nb

sim = nb.Simulation(n=3000, scenario="plummer", seed=42)
sim.run(steps=10_000, dt=0.005, progress=True)
sim.save("my_run.h5")
print(sim.diagnostics())
# Diagnostics(K=0.247, U=-0.502, E=-0.254, Q=0.984, ...)
```

Or from the shell:

```bash
nbody-sim --scenario pleiades --output pleiades.h5 --diagnostics
```

📓 See [`python/notebooks/01_quickstart.ipynb`](python/notebooks/01_quickstart.ipynb)
for a fully-worked Jupyter example.

---

## Path B — Web UI

**Prerequisites:**
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (for the
  PostgreSQL container — anything else runs locally)
- Java 21, Maven 3.9+, Node.js 22+, gfortran, HDF5 (with Fortran bindings)
- See [INSTALL.md](INSTALL.md#path-b-prerequisites) for one-line installers per OS

### Start the whole stack

```bash
git clone https://github.com/hector100197/astro.git
cd astro

# Verify your toolchain (warns if anything is missing)
./scripts/dev-setup.sh

# Bring up postgres + 2 backend services + 2 frontend MFEs
make dev
```

In ~30 seconds you'll see log lines from every component. Then open:

| URL | What it is |
|---|---|
| **http://localhost:4200** | Main UI (start here) |
| http://localhost:8081/actuator/health | simulation-service health probe |
| http://localhost:8082/actuator/health | export-service health probe |

### What you can do in the UI

- Watch a Plummer cluster evolve in real time with controllable Δt, N, softening
- Time scrubber, Lagrangian-radii overlay, side-by-side comparison
- **Click any star to follow its orbit** with a fading trail
- **Batch jobs drawer** (top-right "Batch jobs" button):
  - Launch headless N-body runs (5000+ steps)
  - **Import real clusters from Gaia DR3 by name** (Pleiades, Hyades, M67)
  - Every finished run gets a `✓ NBODY6-grade` / `⚠ Marginal` / `✗ Failed` badge
  - Click the badge to see the six physical checks behind the verdict
  - Download the full report: PDF (with plots), LaTeX, JSON, HDF5 snapshots

📷 Screenshots and a step-by-step tour in
[docs-site/source/tutorials/first_simulation.rst](docs-site/source/tutorials/first_simulation.rst).

### Stop the stack

`Ctrl-C` in the terminal where you ran `make dev`. The script kills every
process and stops the postgres container.

---

## Path C — Full dev setup

If you want to modify the kernel, services, or UI:

- Set up everything per-layer following [DEVELOPMENT.md](DEVELOPMENT.md)
- Per-layer test commands: `make test`
- CI is configured in [`.github/workflows/`](.github/workflows/)
- See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR

---

## Why use `astro`?

Existing N-body codes (NBODY6, GADGET-2, PETAR) are command-line Fortran tools.
You produce log files and post-process them yourself before you can trust a
run. In parallel, browser-based N-body demos use JavaScript Euler integrators
that fail conservation tests within seconds.

`astro` fills the gap:

- **Scientifically rigorous kernel**: symplectic leapfrog, Plummer softening,
  Hénon units. Conservation reaches \|ΔE/E\| ≈ 3×10⁻⁶ on the Aarseth N=3000
  benchmark.
- **Automated quality grading**: six checks against literature tolerances
  produce a one-glance verdict on every finished run.
- **Reproducible artefacts out of the box**: PDF + LaTeX + JSON + HDF5 with
  embedded plots, bit-exact rerunnable given matching kernel binary.
- **Gaia DR3 integration**: import real clusters by name from the GUI.
- **Coexists with the Python ecosystem**: `pip install astro-nbody`, Jupyter
  notebooks, HDF5 standard layout — drop into your existing workflow.

---

## Citation

If you use `astro` in a publication, please cite the Zenodo-archived release:

```bibtex
@software{medel_astro_2026,
  author       = {Medel, Héctor},
  title        = {astro: N-body stellar cluster simulator with
                  hybrid Fortran/Java/Angular stack},
  version      = {0.1.0},
  year         = {2026},
  publisher    = {Zenodo},
  doi          = {10.5281/zenodo.20264301},
  url          = {https://github.com/hector100197/astro}
}
```

A JOSS paper is in preparation in [`paper/paper.md`](paper/paper.md).

## License

MIT — see [LICENSE](LICENSE).
