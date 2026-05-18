# astro-nbody — Python wrapper

NumPy-friendly Python interface to the astro N-body Fortran kernel. Wraps
``libnbody.{dylib,so}`` via **ctypes** — the same binary the Java live-streaming
service consumes via FFM, so Python and the live web UI produce bit-identical
results given the same parameters and OpenMP thread count.

## Install (always in a venv)

```bash
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e .[dev,notebooks]
```

The kernel must be built first:

```bash
make -C ../kernel
```

## Usage

```python
import astro_nbody as nb

sim = nb.Simulation(n=3000, scenario="plummer", seed=42)
sim.run(steps=10_000, dt=0.001, progress=True)

d = sim.diagnostics()
print(f"E = {d.E:.6f}, virial Q = {d.Q:.3f}")

sim.save("pleiades.h5")
```

Reload from disk:

```python
loaded = nb.Simulation.from_hdf5("pleiades.h5")
```

Browse the scenario catalog:

```python
nb.list_scenarios()        # ['aarseth_standard', 'henon_heiles', 'pleiades', ...]
ple = nb.load_scenario("pleiades")
```

## CLI

`pip install` registers the ``nbody-sim`` entry point:

```bash
nbody-sim --scenario pleiades --output pleiades.h5 --diagnostics
nbody-sim --n 1500 --steps 5000 --output run.h5 --diagnostics
```

## Notebook

```bash
source .venv/bin/activate
jupyter notebook notebooks/01_quickstart.ipynb
```

## Why this exists

> 90% of computational astrophysics is in Python. Without this wrapper, no
> astrophysicist will use the kernel, no matter how fast it is. This *is*
> the adoption layer.
