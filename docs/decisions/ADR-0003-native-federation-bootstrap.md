# ADR-0003: Native Federation requires `ng add` step

- **Status**: Accepted
- **Date**: 2026-05-08

## Context

The Angular projects in `apps/` were bootstrapped manually with the standard
Angular `@angular-devkit/build-angular:application` builder. The
`federation.config.js` files exist and reference `@angular-architects/native-federation`,
but the native-federation **builder** is not yet installed.

## Decision

Document the additional one-time setup step required to activate Native
Federation. We do not run it eagerly in skeletons because it modifies
`angular.json` and the user will run `npm install` before that anyway.

## How to activate (per workspace)

After `npm install` in each app, run:

```bash
# Host
cd apps/shell-app
npx ng add @angular-architects/native-federation --type dynamic-host --project shell-app

# Remote
cd apps/simulation-mfe
npx ng add @angular-architects/native-federation --type remote --project simulation-mfe
```

This swaps the builder in `angular.json` from `@angular-devkit/build-angular:application`
to `@angular-architects/native-federation:build`, generates the runtime
manifest, and wires the dev-server to expose the `remoteEntry.json` endpoint
that the host loads.

## Sem 1 fallback

Without the `ng add` step, the apps still build and serve in standalone mode:

- `simulation-mfe` at :4201 renders the `SimulationComponent` via its own
  router (`app.routes.ts`). The WebSocket stream and Three.js scene work
  identically — federation is orthogonal to the render pipeline.
- `shell-app` at :4200 will render the chrome (header + nav) but the
  `/simulate` and `/export` routes will fail at runtime because
  `loadRemoteModule(...)` requires the federation runtime.

So for Sem 1 verification, **point the browser at http://localhost:4201**
directly to see particles streaming. The federated route through
`shell-app` becomes functional once `ng add` is run.

## Reconsider if

- Native Federation tooling stabilizes a config-only path that doesn't
  modify `angular.json`.
- We adopt Nx with its first-class federation support.
