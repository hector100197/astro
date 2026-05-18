# Tutorial 1 — Your first simulation

> Goal: run a Pleiades simulation in under 5 minutes and understand what you're looking at.

## Prerequisites

- Python ≥ 3.10
- macOS or Linux
- 4 GB free RAM

## Install

```bash
pip install astro-nbody
```

## Run the simulation

```python
import astro_nbody as nb

sim = nb.Simulation(n=3000, scenario="pleiades", seed=42)
sim.run(steps=10_000, dt=0.001)
sim.save("pleiades.h5")
```

This produces an HDF5 file with 10 000 snapshots of the evolving cluster, plus a reproducibility manifest.

## Visualize

(TODO Sem 7) — open the HDF5 file with `yt-project` or with the project's web UI.

## What's actually happening?

[ Physics explanation: Plummer profile, leapfrog, energy conservation, etc. — TODO. ]

## Next

→ [Tutorial 2: Energy conservation and why it matters](02_energy_conservation.md)
