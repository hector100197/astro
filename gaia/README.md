# gaia/ — Real cluster import from Gaia DR3

Imports a stellar cluster's current 6D phase-space state (positions + velocities + masses) from the Gaia DR3 catalog and converts it into an `astro` initial condition. Lets the user say:

```yaml
initial_condition:
  type: gaia
  cluster_name: "Pleiades"     # or "Hyades", "M67", "NGC 752", ...
```

and have the simulator load the actual observed stars.

## Why this matters

Most N-body sims start from theoretical models (Plummer, King). This module lets researchers and students **simulate the future of real clusters**, comparing simulation against further Gaia data releases. **No other web N-body tool offers this.**

## Implementation

V1 (Sem 7):
- Query Gaia DR3 by cluster name through `astroquery` (Python).
- Filter by membership probability from a published catalog (e.g., Cantat-Gaudin et al. 2020).
- Convert (RA, Dec, parallax, pmra, pmdec, radial_velocity) → cartesian (x, y, z, vx, vy, vz) in Hénon-equivalent units.
- Estimate masses from photometry + isochrone fitting.
- Export as a snapshot HDF5 file readable by the kernel.

V2:
- Live cone search by user-drawn region.
- Direct ingestion of new Gaia data releases.

## Files

- `importer/` — Python module with the query + conversion logic
- `known_clusters.yaml` — curated list of clusters with membership references
