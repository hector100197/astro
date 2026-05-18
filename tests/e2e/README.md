# tests/e2e — Playwright end-to-end tests

Validates the full vertical slice: launch the dev stack, drive the shell-app, observe simulation-service producing snapshots, export-service producing HDF5.

## Setup

```bash
cd tests/e2e
npm install
npx playwright install --with-deps
```

## Run

Start the dev stack first (`./scripts/dev.sh` from repo root), then:

```bash
npx playwright test
```

## TODO Sem 4

- `live_mode.spec.ts` — open shell, navigate to Simulate, click Play, observe canvas updates
- `capture_mode.spec.ts` — click Record, wait for stop, verify download
- `headless_mode.spec.ts` — submit job, poll status, verify HDF5 download
- `validation_panel.spec.ts` — verify energy plot updates and tests pass
