"""End-to-end post-processing for a finished N-body batch job.

Reads the multi-snapshot HDF5 produced by the Java backend and writes:

- ``report.json`` — full analysis output (timeline, binaries, escapers,
  conservation metrics) in the schema consumed by Java's :class:`JobReport`.
- ``energy.png``, ``virial.png``, ``lagrangian.png``, ``binaries.png``,
  ``escapers.png`` — publication-quality figures embedded in the report.

This module owns the science. Java only orchestrates: it tells us where the
HDF5 lives + which run parameters apply, then it picks up our JSON and
renders the LaTeX/PDF wrapper.

Why this lives in Python rather than Java:
- numpy / vectorised binding-energy and Lagrangian-radius computations are
  significantly faster than the equivalent Java O(N²) loops.
- h5py is the canonical HDF5 reader for scientific Python and avoids a class
  of issues we hit when jHDF's reader was used from the same JVM that had
  already loaded the kernel's native libhdf5.

Usage::

    python -m astro_nbody.report_plots <hdf5> <out_dir> \\
        --job-id ID --scenario NAME --dt 0.005 --softening 0.01 \\
        --n-steps 5000 --seed 42
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import matplotlib

# h5py is imported lazily inside _read_snapshots() so the --from-json path
# (which is what the Java orchestrator now uses) works on environments where
# h5py / libhdf5 aren't installed. Keeping h5py at module scope would break
# subprocess invocation in those minimal setups.

# Headless: no display, no event loop. Backend Agg is bundled with matplotlib
# and produces clean PNGs suitable for PDF embedding.
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

# Publication style: serif fonts, modest line weights, no chart-junk.
plt.rcParams.update({
    "font.family": "serif",
    "font.size": 10,
    "axes.titlesize": 11,
    "axes.labelsize": 10,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "legend.frameon": False,
    "figure.dpi": 150,
    "savefig.dpi": 150,
    "savefig.bbox": "tight",
})

# Hénon units: G = M = -4E = 1.
G = 1.0
# Pair-distance ceiling for binary candidates (Hénon units). Anything farther
# is too loosely associated to count.
BINARY_DISTANCE_CUTOFF = 0.10
# Radius (in half-mass units) past which an unbound body is officially an escaper.
ESCAPER_RADIUS_FACTOR = 5.0


@dataclass
class JobMeta:
    """Run parameters passed in by the Java orchestrator."""
    job_id: str
    scenario: str
    n_bodies: int
    n_steps: int
    dt: float
    softening: float
    seed: int


# ============================================================
# HDF5 reading
# ============================================================

def _read_snapshots(hdf5_path: Path) -> list[dict]:
    """Return a list of snapshot dicts ordered by step. No in-process retry:
    libhdf5 caches a failed open's state and subsequent retries in the same
    process keep failing — the report daemon handles retries by re-spawning
    fresh subprocesses (which get a clean libhdf5 each time)."""
    import h5py  # lazy: only the HDF5 entrypoint needs it
    snaps: list[dict] = []
    with h5py.File(hdf5_path, "r") as f:
        if "Snapshots" not in f:
            raise ValueError(f"{hdf5_path}: no /Snapshots group")
        snap_group = f["Snapshots"]
        # Group names are zero-padded indices, so lex sort matches step order.
        for name in sorted(snap_group.keys()):
            g = snap_group[name]
            coords = np.asarray(g["Coordinates"])
            vels = np.asarray(g["Velocities"])
            mass = np.asarray(g["Masses"])
            # Coordinates may be (3, N) Fortran-style or (N, 3) C-style.
            if coords.shape[0] == 3 and coords.shape[1] == mass.size:
                x, y, z = coords[0], coords[1], coords[2]
                vx, vy, vz = vels[0], vels[1], vels[2]
            elif coords.shape[1] == 3 and coords.shape[0] == mass.size:
                x, y, z = coords[:, 0], coords[:, 1], coords[:, 2]
                vx, vy, vz = vels[:, 0], vels[:, 1], vels[:, 2]
            else:
                raise ValueError(
                    f"unexpected Coordinates shape {coords.shape} for N={mass.size}")
            # Time/Step may be 1-D arrays in the on-disk attribute (Fortran HDF5
            # writes scalars as length-1 arrays); .flat[0] handles both shapes.
            time = float(np.asarray(g.attrs.get("Time", 0.0)).flat[0])
            step = int(np.asarray(g.attrs.get("Step", 0)).flat[0])
            snaps.append({
                "time": time, "step": step,
                "x": x, "y": y, "z": z,
                "vx": vx, "vy": vy, "vz": vz,
                "mass": mass,
            })
    return snaps


# ============================================================
# Physics
# ============================================================

def _energetics(s: dict, eps2: float) -> dict:
    """Return per-snapshot energetics + per-body energy array."""
    x, y, z = s["x"], s["y"], s["z"]
    vx, vy, vz = s["vx"], s["vy"], s["vz"]
    m = s["mass"]
    n = m.size

    v2 = vx * vx + vy * vy + vz * vz
    k_per = 0.5 * m * v2
    K = float(k_per.sum())
    P = np.array([float((m * vx).sum()),
                  float((m * vy).sum()),
                  float((m * vz).sum())])
    L = np.array([float((m * (y * vz - z * vy)).sum()),
                  float((m * (z * vx - x * vz)).sum()),
                  float((m * (x * vy - y * vx)).sum())])

    # CoM
    M = m.sum()
    cx = float((m * x).sum() / M) if M > 0 else 0.0
    cy = float((m * y).sum() / M) if M > 0 else 0.0
    cz = float((m * z).sum() / M) if M > 0 else 0.0

    # Potential energy: vectorised pairwise via broadcasting. Memory ~O(N²)
    # in floats; for N=3000 that's 72 MB — acceptable for a post-processing pass.
    dx = x[:, None] - x[None, :]
    dy = y[:, None] - y[None, :]
    dz = z[:, None] - z[None, :]
    r = np.sqrt(dx * dx + dy * dy + dz * dz + eps2)
    np.fill_diagonal(r, np.inf)         # avoid self-term singularity
    inv_r = 1.0 / r
    # Per-body U_i = -G m_i Σ_j m_j / r_ij  (each pair counted once for total)
    u_per = -G * m * (m[None, :] * inv_r).sum(axis=1)
    U = float(0.5 * u_per.sum())        # halve to count each pair once
    E = K + U
    Q = -2.0 * K / U if U != 0 else float("nan")
    e_per = k_per + u_per

    return {
        "K": K, "U": U, "E": E,
        "P": P.tolist(), "L": L.tolist(),
        "Q": Q,
        "cx": cx, "cy": cy, "cz": cz,
        "energy_per_body": e_per,
        "k_per_body": k_per,
    }


def _lagrangian_radii(s: dict, com: tuple[float, float, float]) -> tuple[float, float, float]:
    cx, cy, cz = com
    dx, dy, dz = s["x"] - cx, s["y"] - cy, s["z"] - cz
    r = np.sqrt(dx * dx + dy * dy + dz * dz)
    order = np.argsort(r)
    cum_m = np.cumsum(s["mass"][order])
    M = cum_m[-1] if cum_m.size > 0 else 0.0
    if M <= 0:
        return 0.0, 0.0, 0.0
    out: list[float] = []
    for frac in (0.10, 0.50, 0.90):
        idx = np.searchsorted(cum_m, frac * M)
        idx = min(idx, len(order) - 1)
        out.append(float(r[order[idx]]))
    return out[0], out[1], out[2]


def _detect_binaries(s: dict, eps2: float, kT: float) -> list[dict]:
    """Vectorised pair scan. Returns one event per bound pair in this snapshot."""
    n = s["mass"].size
    if n < 2:
        return []
    x, y, z = s["x"], s["y"], s["z"]
    vx, vy, vz = s["vx"], s["vy"], s["vz"]
    m = s["mass"]

    # Distance matrix; mask above cutoff, mask self.
    dx = x[:, None] - x[None, :]
    dy = y[:, None] - y[None, :]
    dz = z[:, None] - z[None, :]
    r2 = dx * dx + dy * dy + dz * dz
    iu, ju = np.triu_indices(n, k=1)              # upper triangle, no self
    sep_sq = r2[iu, ju]
    keep = sep_sq <= BINARY_DISTANCE_CUTOFF ** 2
    if not np.any(keep):
        return []
    iu, ju = iu[keep], ju[keep]
    sep = np.sqrt(sep_sq[keep])

    mi, mj = m[iu], m[ju]
    mu = mi * mj / (mi + mj)
    dvx = vx[iu] - vx[ju]
    dvy = vy[iu] - vy[ju]
    dvz = vz[iu] - vz[ju]
    v2 = dvx * dvx + dvy * dvy + dvz * dvz
    r_soft = np.sqrt(sep * sep + eps2)
    Epair = 0.5 * mu * v2 - G * mi * mj / r_soft

    bound = Epair < 0
    if not np.any(bound):
        return []
    iu, ju = iu[bound], ju[bound]
    sep = sep[bound]
    Epair = Epair[bound]
    mu = mu[bound]
    mi, mj = mi[bound], mj[bound]
    dvx, dvy, dvz = dvx[bound], dvy[bound], dvz[bound]
    dxk, dyk, dzk = dx[iu, ju], dy[iu, ju], dz[iu, ju]

    a = -G * mi * mj / (2.0 * Epair)
    Lx = dyk * dvz - dzk * dvy
    Ly = dzk * dvx - dxk * dvz
    Lz = dxk * dvy - dyk * dvx
    L2 = Lx * Lx + Ly * Ly + Lz * Lz
    mTot = mi + mj
    e = np.sqrt(np.maximum(0.0,
        1.0 + (2.0 * (Epair / mu) * L2) / (G * G * mTot * mTot)))
    period = 2.0 * np.pi * np.sqrt(np.power(np.abs(a), 3) / (G * mTot))
    hard = np.abs(Epair) > kT

    return [
        {
            "simTime": s["time"], "stepIndex": s["step"],
            "bodyA": int(iu[k]), "bodyB": int(ju[k]),
            "separation": float(sep[k]),
            "semiMajorAxis": float(a[k]),
            "eccentricity": float(e[k]),
            "periodEstimate": float(period[k]),
            "bindingEnergy": float(Epair[k]),
            "hard": bool(hard[k]),
        }
        for k in range(len(iu))
    ]


# ============================================================
# Top-level analysis
# ============================================================

def analyse(meta: JobMeta, hdf5_path: Path) -> dict:
    snaps = _read_snapshots(hdf5_path)
    if not snaps:
        raise ValueError(f"{hdf5_path}: zero snapshots")

    eps2 = meta.softening * meta.softening
    timeline: list[dict] = []
    binaries: list[dict] = []
    escapers: list[dict] = []
    already_escaped = np.zeros(meta.n_bodies, dtype=bool)
    E0 = None
    L0 = None
    worst_dE = 0.0

    for s in snaps:
        en = _energetics(s, eps2)
        com = (en["cx"], en["cy"], en["cz"])
        r10, r50, r90 = _lagrangian_radii(s, com)

        snap_bins = _detect_binaries(s, eps2, kT=en["K"] / max(s["mass"].size, 1))
        binaries.extend(snap_bins)

        # First-time-unbound + far-from-cluster bodies become escapers.
        if r50 > 0:
            dx = s["x"] - en["cx"]
            dy = s["y"] - en["cy"]
            dz = s["z"] - en["cz"]
            radii = np.sqrt(dx * dx + dy * dy + dz * dz)
            speed = np.sqrt(s["vx"] ** 2 + s["vy"] ** 2 + s["vz"] ** 2)
            unbound = en["energy_per_body"] > 0
            far = radii > ESCAPER_RADIUS_FACTOR * r50
            new_escapers_idx = np.where(unbound & far & ~already_escaped[:s["mass"].size])[0]
            for i in new_escapers_idx:
                escapers.append({
                    "bodyIndex": int(i),
                    "escapeTime": float(s["time"]),
                    "escapeStepIndex": int(s["step"]),
                    "escapeRadius": float(radii[i]),
                    "escapeSpeed": float(speed[i]),
                    "escapeEnergy": float(en["energy_per_body"][i]),
                })
                already_escaped[i] = True
            n_new_escapers = int(new_escapers_idx.size)
        else:
            n_new_escapers = 0

        timeline.append({
            "simTime": s["time"], "stepIndex": s["step"],
            "K": en["K"], "U": en["U"], "E": en["E"],
            "P": en["P"], "L": en["L"], "Q": en["Q"],
            "r10": r10, "r50": r50, "r90": r90,
            "nBinaries": len(snap_bins),
            "nEscapers": n_new_escapers,
        })

        if E0 is None:
            E0 = en["E"]
            L0 = float(np.linalg.norm(en["L"]))
        else:
            if abs(E0) > 1e-12:
                rel = abs((en["E"] - E0) / E0)
                if rel > worst_dE:
                    worst_dE = rel

    last = timeline[-1]
    Lf = float(np.linalg.norm(last["L"]))
    cons = {
        "dE_over_E_initial": abs((last["E"] - E0) / E0) if E0 and abs(E0) > 0 else 0.0,
        "dL_over_L_initial": abs((Lf - L0) / L0) if L0 and L0 > 0 else 0.0,
        "worst_dE_over_E": worst_dE,
    }
    return {
        "jobId": meta.job_id,
        "scenario": meta.scenario,
        "nBodies": meta.n_bodies,
        "nSteps": meta.n_steps,
        "dt": meta.dt,
        "softening": meta.softening,
        "seed": meta.seed,
        "timeline": timeline,
        "binaries": binaries,
        "escapers": escapers,
        "conservation": cons,
    }


# ============================================================
# Plots — same schema as before, now reading the in-memory dict
# ============================================================

def _t(timeline: list[dict]) -> np.ndarray:
    return np.asarray([row["simTime"] for row in timeline])


def plot_energy(report: dict, out: Path) -> None:
    timeline = report["timeline"]
    if not timeline:
        return
    t = _t(timeline)
    K = np.asarray([row["K"] for row in timeline])
    U = np.asarray([row["U"] for row in timeline])
    E = np.asarray([row["E"] for row in timeline])
    fig, ax = plt.subplots(figsize=(6.4, 3.0))
    ax.plot(t, K, label=r"$K$ (kinetic)", color="#34d399", linewidth=1.2)
    ax.plot(t, U, label=r"$U$ (potential)", color="#fbbf24", linewidth=1.2)
    ax.plot(t, E, label=r"$E$ (total)", color="#a78bfa", linewidth=1.6)
    ax.set_xlabel(r"Time $t$  [Hénon units]")
    ax.set_ylabel("Energy")
    ax.set_title("Energy timeline")
    ax.axhline(0, color="#94a3b8", linestyle=":", linewidth=0.6)
    if len(E) > 1 and abs(E[0]) > 1e-12:
        drift = abs((E[-1] - E[0]) / E[0])
        ax.text(0.98, 0.05, fr"$|\Delta E / E_0| = {drift:.2e}$",
                transform=ax.transAxes, ha="right", va="bottom",
                fontsize=9, color="#475569")
    ax.legend(loc="best", fontsize=9)
    fig.savefig(out)
    plt.close(fig)


def plot_virial(report: dict, out: Path) -> None:
    timeline = report["timeline"]
    if not timeline:
        return
    t = _t(timeline)
    Q = np.asarray([row["Q"] for row in timeline])
    fig, ax = plt.subplots(figsize=(6.4, 2.4))
    ax.plot(t, Q, color="#0ea5e9", linewidth=1.2)
    ax.axhline(1.0, color="#94a3b8", linestyle="--", linewidth=0.8,
               label="virial equilibrium ($Q = 1$)")
    ax.set_xlabel(r"Time $t$  [Hénon units]")
    ax.set_ylabel(r"$Q = -2K / U$")
    ax.set_title("Virial ratio (relaxation diagnostic)")
    ax.legend(loc="best", fontsize=9)
    fig.savefig(out)
    plt.close(fig)


def plot_lagrangian(report: dict, out: Path) -> None:
    timeline = report["timeline"]
    if not timeline:
        return
    t = _t(timeline)
    r10 = np.asarray([row["r10"] for row in timeline])
    r50 = np.asarray([row["r50"] for row in timeline])
    r90 = np.asarray([row["r90"] for row in timeline])
    fig, ax = plt.subplots(figsize=(6.4, 3.0))
    ax.plot(t, r10, label=r"$r_{10}$ (core)", color="#dc2626", linewidth=1.2)
    ax.plot(t, r50, label=r"$r_{50}$ (half-mass)", color="#16a34a", linewidth=1.6)
    ax.plot(t, r90, label=r"$r_{90}$ (halo)", color="#2563eb", linewidth=1.2)
    ax.set_xlabel(r"Time $t$  [Hénon units]")
    ax.set_ylabel(r"Radius  [Hénon units]")
    ax.set_title("Lagrangian radii (cluster structure evolution)")
    ax.set_yscale("log")
    ax.legend(loc="best", fontsize=9)
    fig.savefig(out)
    plt.close(fig)


def plot_binaries(report: dict, out: Path) -> None:
    timeline = report["timeline"]
    binaries = report.get("binaries", [])
    if not timeline:
        return
    t = _t(timeline)
    n_per_snap = np.asarray([row.get("nBinaries", 0) for row in timeline])

    fig, axL = plt.subplots(figsize=(6.4, 3.0))
    axL.plot(t, n_per_snap, color="#7c3aed", linewidth=1.4,
             label="bound pairs detected")
    axL.set_xlabel(r"Time $t$  [Hénon units]")
    axL.set_ylabel("Binary pairs (per snapshot)", color="#7c3aed")
    axL.tick_params(axis="y", colors="#7c3aed")
    axL.set_title("Binary catalog timeline")

    if binaries:
        per_t: dict[float, float] = {}
        for b in binaries:
            if not b.get("hard"):
                continue
            ts = float(b["simTime"])
            sep = float(b["separation"])
            if ts not in per_t or sep < per_t[ts]:
                per_t[ts] = sep
        if per_t:
            xs = np.asarray(sorted(per_t.keys()))
            ys = np.asarray([per_t[x] for x in xs])
            axR = axL.twinx()
            axR.plot(xs, ys, color="#dc2626", linewidth=1.0, marker=".",
                     markersize=3, label="tightest hard-binary $r$")
            axR.set_ylabel("Min separation (hard)", color="#dc2626")
            axR.tick_params(axis="y", colors="#dc2626")
            axR.set_yscale("log")
            axR.spines["top"].set_visible(False)

    fig.savefig(out)
    plt.close(fig)


def plot_escapers(report: dict, out: Path) -> None:
    timeline = report["timeline"]
    escapers = report.get("escapers", [])
    if not timeline:
        return
    t = _t(timeline)
    if escapers:
        et = np.asarray(sorted(float(e["escapeTime"]) for e in escapers))
        cum_t = np.concatenate([[t[0]], et, [t[-1]]])
        cum_n = np.concatenate([[0], np.arange(1, len(et) + 1), [len(et)]])
    else:
        cum_t = t
        cum_n = np.zeros_like(t)
    fig, ax = plt.subplots(figsize=(6.4, 2.4))
    ax.step(cum_t, cum_n, where="post", color="#be185d", linewidth=1.4)
    ax.set_xlabel(r"Time $t$  [Hénon units]")
    ax.set_ylabel("Cumulative escapers")
    ax.set_title(f"Escaper timeline (final count = {len(escapers)})")
    fig.savefig(out)
    plt.close(fig)


def render_all_plots(report: dict, out_dir: Path) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    targets: list[Path] = []
    for name, fn in [
        ("energy.png",     plot_energy),
        ("virial.png",     plot_virial),
        ("lagrangian.png", plot_lagrangian),
        ("binaries.png",   plot_binaries),
        ("escapers.png",   plot_escapers),
    ]:
        target = out_dir / name
        try:
            fn(report, target)
            if target.exists():
                targets.append(target)
        except Exception as e:
            print(f"[report_plots] {name} failed: {e}", file=sys.stderr)
    return targets


# ============================================================
# CLI
# ============================================================

def _main(argv: list[str]) -> int:
    """Two invocation modes:

    1. ``--from-json <report.json> <out_dir>`` (preferred, used by the Java
       orchestrator): skip HDF5 entirely, just render the five PNG plots from
       a previously-written report.json. No libhdf5 dependency.

    2. Legacy HDF5 mode (kept for stand-alone use):
       ``<hdf5> <out_dir> --job-id ... --scenario ... ...``. Reads the
       multi-snapshot HDF5, computes the analysis, writes both
       ``report.json`` and the plots.
    """
    if argv and argv[0] == "--from-json":
        if len(argv) < 3:
            print("usage: --from-json <report.json> <out_dir>", file=sys.stderr)
            return 2
        report_path = Path(argv[1])
        out_dir = Path(argv[2])
        if not report_path.is_file():
            print(f"report.json not found: {report_path}", file=sys.stderr)
            return 1
        with report_path.open() as f:
            report = json.load(f)
        plots = render_all_plots(report, out_dir)
        print(f"[report_plots] wrote {len(plots)} plots from {report_path.name}")
        for p in plots:
            print(f"  - {p.name}")
        return 0

    # Legacy HDF5 mode.
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("hdf5", type=Path, help="Path to multi-snapshot HDF5 file.")
    p.add_argument("out_dir", type=Path, help="Directory to write report.json + plots.")
    p.add_argument("--job-id", required=True)
    p.add_argument("--scenario", required=True)
    p.add_argument("--n-bodies", type=int, required=True)
    p.add_argument("--n-steps", type=int, required=True)
    p.add_argument("--dt", type=float, required=True)
    p.add_argument("--softening", type=float, required=True)
    p.add_argument("--seed", type=int, required=True)
    args = p.parse_args(argv)
    if not args.hdf5.exists():
        print(f"hdf5 not found: {args.hdf5}", file=sys.stderr)
        return 1
    meta = JobMeta(
        job_id=args.job_id, scenario=args.scenario, n_bodies=args.n_bodies,
        n_steps=args.n_steps, dt=args.dt, softening=args.softening, seed=args.seed,
    )
    report = analyse(meta, args.hdf5)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.out_dir / "report.json"
    with json_path.open("w") as f:
        json.dump(report, f, indent=2)
    print(f"[report_plots] wrote {json_path}")
    plots = render_all_plots(report, args.out_dir)
    print(f"[report_plots] wrote {len(plots)} plots")
    for p in plots:
        print(f"  - {p.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
