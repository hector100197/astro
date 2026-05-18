# Tutorial 1 — Tu primera simulación

> Objetivo: correr una simulación de Pléyades en menos de 5 minutos y entender qué estás viendo.

## Pre-requisitos

- Python ≥ 3.10
- macOS o Linux
- 4 GB de RAM libre

## Instalar

```bash
pip install astro-nbody
```

## Correr la simulación

```python
import astro_nbody as nb

sim = nb.Simulation(n=3000, scenario="pleiades", seed=42)
sim.run(steps=10_000, dt=0.001)
sim.save("pleiades.h5")
```

Esto crea un archivo HDF5 con 10 000 snapshots del cluster en evolución, más un manifest de reproducibilidad.

## Visualizar

(TODO Sem 7) — abre el archivo HDF5 con `yt-project` o con la UI web del proyecto.

## ¿Qué está pasando?

[ Explicación física: Plummer profile, leapfrog, conservación de energía, etc. — TODO. ]

## Siguiente

→ [Tutorial 2: Conservación de energía y por qué importa](02_conservacion_energia.md)
