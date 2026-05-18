# ADR-0002: Skip Spring Cloud Gateway in V1

- **Status**: Accepted
- **Date**: 2026-05-08

## Context

The original microservices proposal included a Spring Cloud Gateway service in front of `simulation-service` and `export-service`.

## Decision

We do **not** include an API Gateway in V1. The frontend (`shell-app` and the MFEs) calls the backend services directly on their respective ports (8081, 8082).

## Rationale

Spring Cloud Gateway provides value for: single entry point, centralized CORS, rate limiting, auth, load balancing, observability across many services. **None of these apply** in our context:

- Single user, localhost dev.
- Two services, not many.
- CORS is configured per service in two lines of YAML.
- No auth in V1.
- No load balancing (one instance each).

The gateway would add: another service to run, another Spring Boot startup, ~1–5 ms latency per request, another configuration to maintain. **No benefit, real cost.**

## Consequences

- Frontend knows the URLs of both services. We accept this; documenting them in environment configs.
- If we later decide to deploy publicly, we add the gateway then.

## Reconsider if

- We add a third or fourth service (auth, billing, …).
- We deploy to a public URL and need a single entry.
- We need centralized rate limiting or observability.
