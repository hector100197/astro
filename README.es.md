# astro — Simulador N-body de cúmulos estelares

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.PENDING.svg)](https://doi.org/10.5281/zenodo.PENDING)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Banco de trabajo open-source para simulaciones N-body por suma directa de
cúmulos estelares. Combina un kernel numérico **Fortran 2018 + OpenMP** con
una capa de servicios **Java 21 / Spring Boot** y un micro-frontend
**Angular 21**. Cada corrida termina con una calificación automática contra
tolerancias de la literatura (NBODY6-grade / Marginal / Failed) y un paquete
listo para publicación: PDF con plots embebidos, fuente LaTeX, JSON crudo y
snapshots HDF5.

> 📖 **English version:** [README.md](README.md). Documentación completa:
> [docs-site/source/](docs-site/source/).

---

## Elige tu camino

Tres formas distintas de usar `astro`. Cada una con sus prerequisitos
mínimos — **no necesitas instalar todo**.

| Tú eres… | Quieres… | Salta a |
|---|---|---|
| 🧑‍🔬 **Astrofísico que vive en Python/Jupyter** | Llamar el kernel desde un notebook, obtener HDF5 + numpy | [Camino A — Wrapper Python](#camino-a--wrapper-python-más-rápido) |
| 🌐 **Astrofísico que quiere la GUI** | Lanzar y visualizar corridas interactivas, bajar PDF | [Camino B — UI Web](#camino-b--ui-web) |
| 🛠️ **Desarrollador/contributor** | Modificar kernel, servicios o frontends | [Camino C — Setup dev completo](#camino-c--setup-dev-completo) |

---

## Camino A — Wrapper Python (más rápido)

**Prerequisitos** (5 min si no los tienes):
- Python 3.10 o más nuevo
- Compilador C/Fortran (`gfortran` en Linux/macOS)
- GNU `make`

Comandos exactos por SO en [INSTALL.md](INSTALL.md#path-a-prerequisites).

### Compilar el kernel e instalar el wrapper

```bash
git clone https://github.com/hector100197/astro.git
cd astro

# Compila el kernel Fortran (una vez, ~10 s)
make -C kernel

# Crea un venv y instala el wrapper editable
cd python
python3 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev,notebooks]'
```

### Tu primera simulación (4 líneas)

```python
import astro_nbody as nb

sim = nb.Simulation(n=3000, scenario="plummer", seed=42)
sim.run(steps=10_000, dt=0.005, progress=True)
sim.save("mi_run.h5")
print(sim.diagnostics())
```

O desde shell:

```bash
nbody-sim --scenario pleiades --output pleiades.h5 --diagnostics
```

📓 Ejemplo completo en
[`python/notebooks/01_quickstart.ipynb`](python/notebooks/01_quickstart.ipynb).

---

## Camino B — UI Web

**Prerequisitos:**
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (para
  el contenedor de PostgreSQL — todo lo demás corre local)
- Java 21, Maven 3.9+, Node.js 22+, gfortran
- Comandos por SO en [INSTALL.md](INSTALL.md#path-b-prerequisites).

### Levantar el stack completo

```bash
git clone https://github.com/hector100197/astro.git
cd astro

./scripts/dev-setup.sh    # verifica toolchain
make dev                  # levanta postgres + servicios + frontends
```

En ~30 segundos verás logs de cada componente. Después abre:

| URL | Qué es |
|---|---|
| **http://localhost:4200** | UI principal (empieza aquí) |
| http://localhost:8081/actuator/health | health simulation-service |
| http://localhost:8082/actuator/health | health export-service |

### Qué puedes hacer en la UI

- Visualizar un cúmulo Plummer en tiempo real con Δt, N, softening ajustables
- Time scrubber, overlay de radios Lagrangianos, comparación lado a lado
- **Click en cualquier estrella para seguirla** con un trail desvanecente
- **Drawer de Batch jobs** (botón "Batch jobs" arriba):
  - Lanzar corridas headless (5000+ pasos)
  - **Importar cúmulos reales de Gaia DR3 por nombre** (Pléyades, Hyades, M67)
  - Cada corrida obtiene un badge `✓ NBODY6-grade` / `⚠ Marginal` / `✗ Failed`
  - Click en el badge → panel con los seis chequeos físicos
  - Descarga: PDF (con plots), LaTeX, JSON, snapshots HDF5

📷 Capturas y tour paso a paso en
[docs-site/source/tutorials/first_simulation.rst](docs-site/source/tutorials/first_simulation.rst).

### Detener todo

`Ctrl-C` en la terminal donde corriste `make dev`.

---

## Camino C — Setup dev completo

Para modificar kernel, servicios o UI:

- Setup por capa en [DEVELOPMENT.md](DEVELOPMENT.md)
- Tests por capa: `make test`
- CI en [`.github/workflows/`](.github/workflows/)
- Lee [CONTRIBUTING.md](CONTRIBUTING.md) antes de un PR

---

## Citar

Si usas `astro` en una publicación, cita la release archivada en Zenodo
(ver [README.md](README.md#citation) para el BibTeX). El paper JOSS está
en preparación en [`paper/paper.md`](paper/paper.md).

## Licencia

MIT — ver [LICENSE](LICENSE).
