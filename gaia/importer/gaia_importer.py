"""
Gaia DR3 cluster importer.

TODO Sem 7:
    1. Resolve cluster_name → coordinates from known_clusters.yaml
    2. Cone search via astroquery.gaia
    3. Cross-match with published membership catalog
    4. Convert (RA, Dec, parallax, pmra, pmdec, RV) → (x, y, z, vx, vy, vz) Hénon
    5. Estimate masses from BP-RP color via isochrone fitting
    6. Write snapshot HDF5 readable by the kernel
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class GaiaImportResult:
    cluster_name: str
    n_stars: int
    hdf5_path: str
    membership_reference: str


def import_cluster(cluster_name: str, output_path: str) -> GaiaImportResult:
    """Import a named cluster from Gaia DR3 and write a snapshot HDF5."""
    raise NotImplementedError("TODO Sem 7 — astroquery + isochrone fitting")
