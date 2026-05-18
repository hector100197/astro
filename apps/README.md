# apps — Angular 21 micro-frontends

Three remotes plus a shell host, plus a shared component library, all using **Native Federation** (the Angular-native replacement for Webpack Module Federation, available since Angular 17 and stable in 21).

| App              | Port  | Role                                                  |
|------------------|-------|-------------------------------------------------------|
| `shell-app`      | 4200  | Host. Routes between modes, loads remotes dynamically |
| `simulation-mfe` | 4201  | Live + Capture (via CCapture.js on the canvas)        |
| `export-mfe`     | 4202  | Headless: submit jobs, poll status, download HDF5     |
| `shared-ui`      | (lib) | Component library: signals, i18n, theme tokens        |

## Dev

Each MFE has its own `package.json`. Use the workspace script to run all in parallel:

```bash
./scripts/dev.sh    # runs all apps + services + postgres
```

Or individually:

```bash
cd apps/shell-app && npm run start
cd apps/simulation-mfe && npm run start
cd apps/export-mfe && npm run start
```

## Stack notes

- Standalone components (default since Angular 19)
- Signals for reactive state
- New control flow (`@if`, `@for`)
- Three.js for 3D rendering
- Plotly (or uPlot) for 2D scientific plots
- RxJS for WebSocket streams
- `@angular/localize` for i18n (ES + EN from day 1)
