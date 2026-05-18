"""
Generate the JOSS paper's validation overview figure.

Reads the JobReport JSON committed alongside this script (a real run of the
pleiades scenario with N=3000, dt=0.005, 5000 steps) and renders three panels:
energy timeline, virial ratio Q(t), and Lagrangian radii r_{10}/r_{50}/r_{90}.

Run from this directory:

    python3 generate_figure.py

Output: ``validation_overview.pdf`` (vector, embeddable in LaTeX).
"""

from __future__ import annotations

import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless — figure goes to disk

import matplotlib.pyplot as plt

HERE = Path(__file__).parent
DATA = HERE / "pleiades_report.json"
OUTPUT = HERE / "validation_overview.pdf"


def main() -> None:
    report = json.loads(DATA.read_text())
    timeline = report["timeline"]

    t = [p["simTime"] for p in timeline]
    K = [p["K"] for p in timeline]
    U = [p["U"] for p in timeline]
    E = [p["E"] for p in timeline]
    Q = [p["Q"] for p in timeline]
    r10 = [p["r10"] for p in timeline]
    r50 = [p["r50"] for p in timeline]
    r90 = [p["r90"] for p in timeline]

    fig, axes = plt.subplots(3, 1, figsize=(6.0, 7.0), sharex=True)

    ax_e, ax_q, ax_r = axes

    ax_e.plot(t, K, label="$K$", color="#0b6db8")
    ax_e.plot(t, U, label="$U$", color="#b32d00")
    ax_e.plot(t, E, label="$E = K + U$", color="black", linewidth=1.3)
    ax_e.set_ylabel("Energy (Hénon units)")
    ax_e.legend(loc="center right", frameon=False, fontsize=9)
    ax_e.set_title(
        f"pleiades run · $N={report['nBodies']}$, "
        f"$\\Delta t={report['dt']}$, {report['nSteps']} steps",
        fontsize=10,
    )

    ax_q.axhline(1.0, color="grey", linestyle="--", linewidth=0.8)
    ax_q.plot(t, Q, color="#5b21b6")
    ax_q.set_ylabel("Virial $Q = -2K/U$")
    ax_q.set_ylim(0.9, 1.1)

    ax_r.plot(t, r90, label="$r_{90}$", color="#0b6db8")
    ax_r.plot(t, r50, label="$r_{50}$ (half-mass)", color="#b45309")
    ax_r.plot(t, r10, label="$r_{10}$", color="#047857")
    ax_r.set_ylabel("Lagrangian radius")
    ax_r.set_xlabel("Time (Hénon units)")
    ax_r.legend(loc="center right", frameon=False, fontsize=9)

    # Tight, vector output.
    for ax in axes:
        ax.grid(alpha=0.15)
        ax.spines["top"].set_visible(False)
        ax.spines["right"].set_visible(False)

    fig.tight_layout()
    fig.savefig(OUTPUT, format="pdf", bbox_inches="tight")
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    main()
