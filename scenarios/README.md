# scenarios/ — Pre-loaded problem catalog

Six standard test problems, ready to run from the UI, CLI, or Python:

| File                          | What it is                                       | Use case                  |
|-------------------------------|--------------------------------------------------|---------------------------|
| `pleiades.yaml`               | ~3000-star Plummer cluster, Pleiades-like        | Default demo, N-body      |
| `three_body_figure8.yaml`     | Chenciner-Montgomery choreographic orbit         | Integrator stability      |
| `three_body_pythagorean.yaml` | Burrau's chaotic 3-4-5 problem                   | Chaos demonstration       |
| `henon_heiles.yaml`           | Hénon-Heiles 2D potential, single particle       | Symplectic integrator test|
| `aarseth_standard.yaml`       | NBODY6 reference Plummer N=100                   | Cross-validation          |
| `solar_system.yaml`           | Sun + 8 planets at J2000                         | Educational, orbital mech |

Add your own: any `*.yaml` here is auto-discovered by the simulation-service and Python wrapper.

## Schema

See `pleiades.yaml` for the full schema. Key sections:

- `initial_condition`: type (`plummer`, `king`, `hernquist`, `explicit`, `gaia`), parameters
- `simulation`: integrator, force calculator, `dt`, `softening`, `n_steps`
- `validation`: per-scenario thresholds for the validation tests
- `display`: hints for the UI (units to show, optional reference cluster name, etc.)
