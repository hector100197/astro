# simulation-service

Live mode service. Owns the Fortran kernel, exposes WebSocket binary streaming for snapshots, REST for control commands, and runs the in-process physics validation tests.

- Port: `:8081`
- Hex layers: `domain/`, `application/`, `infrastructure/{in,out}/`
- Persistence: PostgreSQL via Spring Data JPA + Flyway migrations

## Run

```bash
cd services/simulation-service
mvn spring-boot:run
```

Requires `libnbody.dylib` (or `.so`) at `../../kernel/build/`. Build the kernel first:

```bash
make -C ../../kernel
```

For the Sem 1 mock (no Postgres, no FFM):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mock
```
