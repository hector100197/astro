# ADR-0005: Astro Postgres listens on host port 5433

- **Status**: Accepted
- **Date**: 2026-05-08

## Context

The standard Postgres port is 5432. When validating Sem 3 we discovered the
developer's machine already had a Postgres container on `:5432` belonging to
another project (`exofinder-postgres`). Spring Boot tried to connect with
the astro credentials and got `password authentication failed for user "astro"`
— because it was authenticating against the *other* project's Postgres.

## Decision

Astro's docker-compose maps the container's 5432 to **host port 5433**:

```yaml
ports:
  - "5433:5432"
```

All connection strings (Spring `application.yml`, scripts, docs) reference
`localhost:5433`. Inside the container nothing changes (still 5432, the
Postgres default), so any tooling that connects via `docker exec` is
unaffected.

## Consequences

### Positive
- No conflict with other projects running Postgres on 5432.
- Multiple projects can run side-by-side.
- A simple `docker compose up -d` brings astro up cleanly.

### Negative
- Slightly non-standard port; new contributors may try 5432 by reflex.
- All connection strings hardcoded to 5433 — env override still works
  via `SPRING_DATASOURCE_URL` if needed.

## How to use

```bash
# Start the astro Postgres
docker compose up -d

# Verify it's there (look for the line with 0.0.0.0:5433->5432/tcp)
docker compose ps

# Connect with psql
psql -h localhost -p 5433 -U astro -d astro
# password: astro_dev (from docker-compose env)
```

## Reconsider if

- We move to a managed cloud Postgres (port irrelevant).
- The exofinder-style conflict goes away.
- We adopt a port-conflict detection helper that picks dynamically.
