# export-service

Headless mode service. Accepts batch simulation jobs, runs the kernel without streaming, persists snapshots to HDF5, and exposes job status / download endpoints.

- Port: `:8082`
- Hex layers: `domain/`, `application/`, `infrastructure/{in,out}/`
- Output: HDF5 files in `data/` plus row in `simulation_runs` table

## Run

```bash
cd services/export-service
mvn spring-boot:run
```
