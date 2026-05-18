# nbody-sim — Standalone CLI

For HPC clusters, batch pipelines, or anyone who doesn't want a web UI.

```bash
nbody-sim --config scenarios/pleiades.yaml --output run.h5 --steps 100000
```

## Implementation

V1: thin Python script using the `astro-nbody` package + Click for argument parsing. No additional dependencies beyond what `python/` already pulls in.

V2: optional standalone Rust binary that calls the kernel directly via FFI, for environments where Python is not available (rare in academia, common in some HPC bare-metal clusters).
