# Architecture

> Hexagonal architecture across two Spring Boot microservices, three Angular micro-frontends, and a Fortran 2018 numerical kernel — all coordinated locally during development, with PostgreSQL as the metadata store and HDF5 as the snapshot format.

## High-level diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              Browser                                      │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ shell-app :4200  (Native Federation host)                          │  │
│  │   ├─ simulation-mfe :4201  (Live + Capture)                        │  │
│  │   ├─ export-mfe     :4202  (Headless jobs)                         │  │
│  │   └─ shared-ui             (lib of components, signals, i18n)      │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└────────────────────────┬──────────────────────┬──────────────────────────┘
                         │ REST + WebSocket     │ REST
                         ▼                      ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    Backend (Java 21 + Spring Boot 3.4)                    │
│  ┌────────────────────────────┐    ┌────────────────────────────────┐   │
│  │ simulation-service :8081   │    │ export-service :8082           │   │
│  │  ┌──────────────────────┐  │    │  ┌──────────────────────────┐  │   │
│  │  │ domain/              │  │    │  │ domain/                  │  │   │
│  │  │   Body, Snapshot     │  │    │  │   ExportJob, JobStatus   │  │   │
│  │  │   Integrator (port)  │  │    │  │   ExportPort             │  │   │
│  │  │   ForceCalc (port)   │  │    │  └──────────────────────────┘  │   │
│  │  └──────────────────────┘  │    │  ┌──────────────────────────┐  │   │
│  │  ┌──────────────────────┐  │    │  │ application/             │  │   │
│  │  │ application/         │  │    │  │   SubmitExportJob        │  │   │
│  │  │   RunSimulation      │  │    │  │   QueryJobStatus         │  │   │
│  │  │   PauseSimulation    │  │    │  └──────────────────────────┘  │   │
│  │  │   ValidatePhysics    │  │    │  ┌──────────────────────────┐  │   │
│  │  └──────────────────────┘  │    │  │ infrastructure/          │  │   │
│  │  ┌──────────────────────┐  │    │  │  in/  REST controllers   │  │   │
│  │  │ infrastructure/      │  │    │  │  out/ FFM, HDF5, Postgres│  │   │
│  │  │  in/                 │  │    │  └──────────────────────────┘  │   │
│  │  │   REST + WS handlers │  │    └────────────────────────────────┘   │
│  │  │  out/                │  │                                          │
│  │  │   FortranKernelAdpt  │  │    ┌────────────────────────────────┐   │
│  │  │   HDF5Adapter        │  │    │ shared-contracts (lib)         │   │
│  │  │   PostgresAdapter    │  │    │   DTOs, events, error types    │   │
│  │  │   EventBus           │  │    └────────────────────────────────┘   │
│  │  └──────────────────────┘  │                                          │
│  └────────────────────────────┘                                          │
└──────────────┬─────────────────────────────────┬────────────────────────┘
               │ FFM (Foreign Function & Memory) │ JDBC
               ▼                                 ▼
┌──────────────────────────┐         ┌──────────────────────────────────┐
│  kernel/libnbody.dylib   │         │   PostgreSQL 16                  │
│  Fortran 2018 + OpenMP   │         │   simulation_runs                │
│  ┌────────────────────┐  │         │   parameters                     │
│  │ nbody_core.f90     │  │         │   validation_metrics             │
│  │   leapfrog,        │  │         │   validation_tests               │
│  │   brute force,     │  │         └──────────────────────────────────┘
│  │   Plummer softening│  │
│  └────────────────────┘  │         ┌──────────────────────────────────┐
│  ┌────────────────────┐  │         │   data/*.hdf5                    │
│  │ nbody_io.f90       │  │         │   GADGET-like layout:            │
│  │   HDF5 writer      │──┼────────▶│     /Header (manifest)           │
│  └────────────────────┘  │         │     /Snapshots/NNNN/             │
│  ┌────────────────────┐  │         │     /Validation/                 │
│  │ nbody_api.f90      │  │         └──────────────────────────────────┘
│  │   C-bindings (FFM) │  │
│  └────────────────────┘  │
└──────────────────────────┘
```

## Hexagonal layers (per service)

### `domain/`
Pure business logic. Zero infrastructure imports. Entities, value objects, ports (interfaces).

- `Body`, `Snapshot`, `SimulationConfig`, `RunId`, `JobId`
- Ports: `Integrator`, `ForceCalculator`, `InitialCondition`, `SnapshotPublisher`, `RunRepository`

### `application/`
Use cases / services. Orchestrates domain + ports. Transactional boundaries here.

- `RunSimulation`, `PauseSimulation`, `ResumeSimulation`, `StopSimulation`
- `ValidatePhysics`, `SubmitExportJob`, `QueryJobStatus`

### `infrastructure/in/`
Driving adapters: REST controllers, WebSocket handlers, CLI entry points.

### `infrastructure/out/`
Driven adapters: implementations of ports.

- `FortranKernelAdapter` (FFM bridge)
- `HDF5Adapter`
- `PostgresRunRepository`
- `EventBusAdapter`

## Design patterns applied

| Pattern    | Where                                               |
|------------|-----------------------------------------------------|
| Strategy   | `Integrator` (Leapfrog, Hermite-4), `ForceCalculator` (BruteForce, BarnesHut), `InitialCondition` (Plummer, King, Hernquist, GaiaImported) |
| Factory    | `SimulationFactory` builds config + integrator + force calc from request DTO |
| Builder    | `SimulationConfigBuilder` (many parameters)         |
| State      | `SimulationLifecycle` (IDLE → RUNNING → PAUSED → COMPLETED → FAILED) |
| Observer   | Snapshot events fan out to: WebSocket publisher, HDF5 writer, validation runner |
| Singleton  | `KernelPool` (Spring bean, default scope) — manages reusable Fortran contexts |
| Adapter    | Implicit in hexagonal — every `*Adapter` class      |

## The 3 modes

| Mode      | Backend                                          | Frontend                                             |
|-----------|--------------------------------------------------|------------------------------------------------------|
| Live      | simulation-service runs kernel + WebSocket       | simulation-mfe receives binary stream, renders 3D    |
| Capture   | (none extra) — reuses Live                       | simulation-mfe activates CCapture.js on the canvas   |
| Headless  | export-service runs kernel without streaming     | export-mfe submits job, polls status, downloads HDF5 |

## Decoupled timestep + interpolation (Live mode)

Following Glenn Fiedler's *Fix Your Timestep!* pattern: the kernel produces snapshots at a fixed physical Δt, the frontend interpolates between buffered snapshots at the display refresh rate (60–240 Hz depending on monitor). The kernel is never coupled to `requestAnimationFrame`.

## Communication

- `simulation-mfe` ↔ `simulation-service`: REST for control (start/pause/setDt), WebSocket binary for snapshot stream.
- `export-mfe` ↔ `export-service`: REST only (job submission, polling, download).
- `simulation-service` ↔ `export-service`: REST sync for cross-service queries; events via Postgres `LISTEN/NOTIFY` or RabbitMQ in V2.

## See also

- [physics.md](physics.md) — equations, integrators, validation
- [reproducibility.md](reproducibility.md) — manifest spec
- [decisions/](decisions/) — ADRs
