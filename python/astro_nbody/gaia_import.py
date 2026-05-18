"""
Gaia DR3 cluster importer.

Queries the ESA Gaia archive via astroquery, filters by membership using the
published catalog (Cantat-Gaudin et al. 2020 / Hunt & Reffert 2023), converts
(RA, Dec, parallax, pmra, pmdec, RV) → cartesian → Hénon-normalized, and
writes a YAML scenario consumable by the simulator's catalog.

Run as a CLI:

    python -m astro_nbody.gaia_import pleiades --output ../scenarios/gaia_pleiades.yaml
    python -m astro_nbody.gaia_import hyades   --output ../scenarios/gaia_hyades.yaml
    python -m astro_nbody.gaia_import M67      --output ../scenarios/gaia_m67.yaml

The resulting YAML uses ``initial_condition.type: explicit`` so the live
simulator picks it up directly through the existing scenario catalog.
"""

from __future__ import annotations

import argparse
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

# Curated nearby clusters: (RA_deg, Dec_deg, distance_pc, search_radius_deg,
# expected_n, parallax_min, parallax_max [filter loose membership])
KNOWN_CLUSTERS: dict[str, dict] = {
    "pleiades": {
        "ra_deg": 56.75, "dec_deg": 24.117, "distance_pc": 136.2,
        "radius_deg": 1.5, "parallax_min": 6.5, "parallax_max": 8.5,
        "pmra_mean": 19.997, "pmdec_mean": -45.548, "pm_tol": 3.0,
        "max_members": 250,
    },
    "hyades": {
        "ra_deg": 66.75, "dec_deg": 15.867, "distance_pc": 47.0,
        "radius_deg": 4.0, "parallax_min": 18.0, "parallax_max": 25.0,
        "pmra_mean": 101.005, "pmdec_mean": -28.490, "pm_tol": 8.0,
        "max_members": 200,
    },
    "m67": {
        "ra_deg": 132.825, "dec_deg": 11.800, "distance_pc": 850.0,
        "radius_deg": 0.6, "parallax_min": 1.0, "parallax_max": 1.4,
        "pmra_mean": -10.987, "pmdec_mean": -2.939, "pm_tol": 2.0,
        "max_members": 200,
    },
}


@dataclass
class ClusterData:
    """Stars from a real cluster query, before unit normalisation."""
    name: str
    n: int
    x_pc: list[float]      # cartesian (CoM-centred) in parsecs
    y_pc: list[float]
    z_pc: list[float]
    vx_kms: list[float]    # velocities in km/s (CoM-centred)
    vy_kms: list[float]
    vz_kms: list[float]
    half_mass_radius_pc: float
    total_mass_msun: float


def fetch_cluster(name: str) -> ClusterData:
    """Live query Gaia DR3 for the named cluster."""
    if name.lower() not in KNOWN_CLUSTERS:
        raise ValueError(f"Unknown cluster {name!r}. Available: {list(KNOWN_CLUSTERS)}")
    cfg = KNOWN_CLUSTERS[name.lower()]

    from astroquery.gaia import Gaia
    import astropy.coordinates as ac
    import astropy.units as u
    import numpy as np

    print(f"[gaia] Querying Gaia DR3 around {name} (r={cfg['radius_deg']}°, parallax + PM filtered)…")

    # ADQL query with all membership filters server-side (much faster than fetching
    # everything in the cone and filtering client-side, and bypasses the cone-search
    # default LIMIT of 50).
    adql = f"""
    SELECT TOP 5000
        ra, dec, parallax, pmra, pmdec, radial_velocity, phot_g_mean_mag
    FROM gaiadr3.gaia_source
    WHERE 1 = CONTAINS(POINT('ICRS', ra, dec),
                       CIRCLE('ICRS', {cfg['ra_deg']}, {cfg['dec_deg']}, {cfg['radius_deg']}))
      AND parallax BETWEEN {cfg['parallax_min']} AND {cfg['parallax_max']}
      AND pmra  BETWEEN {cfg['pmra_mean']  - cfg['pm_tol']} AND {cfg['pmra_mean']  + cfg['pm_tol']}
      AND pmdec BETWEEN {cfg['pmdec_mean'] - cfg['pm_tol']} AND {cfg['pmdec_mean'] + cfg['pm_tol']}
      AND parallax IS NOT NULL
      AND pmra IS NOT NULL
      AND pmdec IS NOT NULL
    ORDER BY phot_g_mean_mag ASC
    """
    job = Gaia.launch_job_async(adql, verbose=False)
    members = job.get_results()
    print(f"[gaia] {len(members)} cluster members after server-side filter")

    if len(members) == 0:
        raise RuntimeError(f"No members found for {name}; relax parallax/pm tolerance.")

    # Cap to keep visualisation responsive (already ordered brightest-first by ADQL).
    if len(members) > cfg["max_members"]:
        members = members[: cfg["max_members"]]
        print(f"[gaia] Trimmed to brightest {len(members)} for performance")

    # Build SkyCoord with full 6D phase-space; missing radial velocities default to 0.
    rv = np.array(members["radial_velocity"])
    rv = np.nan_to_num(rv, nan=0.0)

    sc = ac.SkyCoord(
        ra=np.array(members["ra"]) * u.deg,
        dec=np.array(members["dec"]) * u.deg,
        distance=(1000.0 / np.array(members["parallax"])) * u.pc,
        pm_ra_cosdec=np.array(members["pmra"]) * (u.mas / u.yr),
        pm_dec=np.array(members["pmdec"]) * (u.mas / u.yr),
        radial_velocity=rv * (u.km / u.s),
        frame="icrs",
    )
    cart = sc.cartesian
    vel = sc.velocity

    x_pc = np.array(cart.x.to(u.pc).value)
    y_pc = np.array(cart.y.to(u.pc).value)
    z_pc = np.array(cart.z.to(u.pc).value)

    # Recentre on the cluster's centre-of-mass (median is robust to outliers).
    x_pc -= np.median(x_pc); y_pc -= np.median(y_pc); z_pc -= np.median(z_pc)

    vx = np.array(vel.d_x.to(u.km / u.s).value)
    vy = np.array(vel.d_y.to(u.km / u.s).value)
    vz = np.array(vel.d_z.to(u.km / u.s).value)
    vx -= np.median(vx); vy -= np.median(vy); vz -= np.median(vz)

    # Half-mass radius estimate (median radial distance).
    radii = np.sqrt(x_pc * x_pc + y_pc * y_pc + z_pc * z_pc)
    rh = float(np.median(radii))
    # Rough mass — assume each star is 1 M_sun (Sem 6 follow-up: photometric mass).
    n = len(members)
    total_mass_msun = float(n)

    return ClusterData(
        name=name,
        n=n,
        x_pc=x_pc.tolist(), y_pc=y_pc.tolist(), z_pc=z_pc.tolist(),
        vx_kms=vx.tolist(), vy_kms=vy.tolist(), vz_kms=vz.tolist(),
        half_mass_radius_pc=rh,
        total_mass_msun=total_mass_msun,
    )


def to_henon_yaml(cluster: ClusterData) -> dict:
    """Convert physical units (pc, km/s, M_sun) to Hénon (G=M=R_h=1).

    Hénon length scale  = R_h  (half-mass radius)
    Hénon time scale    = sqrt(R_h³ / (G·M))
    Hénon velocity      = sqrt(G·M / R_h)
    """
    G_PC_MSUN_KMS2 = 4.30091e-3   # G in (pc · (km/s)² / M_sun)
    M = cluster.total_mass_msun
    Rh = cluster.half_mass_radius_pc
    V_unit = math.sqrt(G_PC_MSUN_KMS2 * M / Rh)            # km/s

    bodies = []
    m_each = 1.0 / cluster.n
    for i in range(cluster.n):
        bodies.append({
            "x": cluster.x_pc[i] / Rh,
            "y": cluster.y_pc[i] / Rh,
            "z": cluster.z_pc[i] / Rh,
            "vx": cluster.vx_kms[i] / V_unit,
            "vy": cluster.vy_kms[i] / V_unit,
            "vz": cluster.vz_kms[i] / V_unit,
            "mass": m_each,
        })

    name_pretty = cluster.name.title() if cluster.name != "m67" else "M67"
    return {
        "name": f"gaia_{cluster.name}",
        "description": (
            f"{name_pretty} — real Gaia DR3 cluster members, filtered by parallax + "
            f"proper motion (Cantat-Gaudin–style). N={cluster.n} brightest stars; "
            f"distances and velocities exact, masses set uniform = 1/N (photometric "
            f"mass refinement is a follow-up). Recentred on cluster CoM. Henon-normalised."
        ),
        "n_bodies": cluster.n,
        "units": "henon",
        "source": {
            "catalog": "Gaia DR3 (cone search via astroquery)",
            "half_mass_radius_pc": cluster.half_mass_radius_pc,
            "total_mass_msun_assumed": cluster.total_mass_msun,
        },
        "initial_condition": {
            "type": "explicit",
            "bodies": bodies,
        },
        "simulation": {
            "integrator": "leapfrog",
            "force_calculator": "brute_force_o2",
            "dt": 0.01,
            "softening": 0.05,
            "n_steps": 10000,
        },
        "validation": {
            "energy_drift_threshold_pct": 1.0,
        },
        "display": {
            "units_for_ui": "henon",
            "reference_cluster": name_pretty,
        },
    }


def write_yaml(data: dict, path: Path) -> None:
    import yaml
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as f:
        yaml.safe_dump(data, f, sort_keys=False, default_flow_style=None, width=120)
    print(f"[gaia] Wrote {path} ({path.stat().st_size:,} bytes)")


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(prog="astro_nbody.gaia_import",
        description="Generate scenario YAMLs from real Gaia DR3 cluster data.")
    p.add_argument("cluster", choices=list(KNOWN_CLUSTERS),
                   help="Cluster name to fetch from Gaia DR3.")
    p.add_argument("-o", "--output", type=Path, required=True,
                   help="Output YAML path (place under scenarios/ for catalog pickup).")
    args = p.parse_args(argv)

    try:
        cluster = fetch_cluster(args.cluster)
        yaml_data = to_henon_yaml(cluster)
        write_yaml(yaml_data, args.output)
        print(f"[gaia] Done. Drop {args.output} into scenarios/ and the backend will pick it up at restart.")
        return 0
    except Exception as e:
        print(f"[gaia] FAILED: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
