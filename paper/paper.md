---
title: 'astro: a hybrid Fortran/Java/Angular N-body workbench with built-in physics validation for stellar cluster simulations'
tags:
  - Fortran
  - Java
  - Angular
  - astrophysics
  - N-body
  - stellar dynamics
  - high-performance computing
authors:
  - name: Héctor Medel
    orcid: 0009-0009-8954-4234
    affiliation: 1
affiliations:
  - name: Independent researcher
    index: 1
date: 2026-05-17
bibliography: paper.bib
---

# Summary

`astro` is an open-source workbench for direct-summation N-body simulations of
stellar clusters. It combines a Fortran 2018 + OpenMP numerical kernel, a
Java 21 / Spring Boot service layer, and an Angular 21 micro-frontend, exposed
through a single REST/WebSocket surface. The kernel integrates Newtonian
gravity with the symplectic leapfrog scheme [@saha1992] and Plummer softening
[@plummer1911] in Hénon units [@henon1971], achieving a relative energy drift on the order of $10^{-6}$
on the standard Aarseth benchmark [@aarseth2003] with $N \approx 3000$ over
$5000$ steps.

What distinguishes `astro` from existing N-body codes is its **automated
quality assessment of every finished run**. The post-processing pipeline emits
a downloadable report (PDF + LaTeX + JSON) and a six-point validation badge
(`NBODY6-grade` / `Marginal` / `Failed`) that checks energy and angular
momentum conservation, virial equilibrium, half-mass radius stability, and the
escaper fraction against literature-calibrated tolerances. The same artefacts
are persisted in PostgreSQL and HDF5 alongside a deterministic reproducibility
manifest, so users can publish, share, or peer-review a run with a single
artefact bundle.

# Statement of need

Production-grade N-body codes such as NBODY6 [@aarseth2003], GADGET-2
[@springel2005], and PETAR [@wang2020] are command-line Fortran/C++ tools. They produce
text or binary log files that experienced users interpret manually. Newcomers
have to write their own post-processing scripts (energy budgets, virial ratios,
Lagrangian radii, binary detection, escaper accounting) before they can trust
a run. In parallel, browser-based N-body demos almost universally trade
numerical rigour for visual immediacy and run on JavaScript Euler integrators
that fail basic conservation tests within seconds.

`astro` targets the gap between these two worlds. It provides:

1. A **scientifically rigorous kernel** (symplectic integrator, soft-binary
   detection following the standard $|E_{\rm pair}| > kT$ criterion
   [@heggie1975]) accessible from Python (`pip install astro-nbody`),
   from a CLI (`nbody-sim`), or from a web UI.
2. **Built-in physics validation** that classifies each run against
   tolerances calibrated to reference codes — implemented as a deterministic
   service that the REST API exposes and the UI surfaces as a coloured badge.
   This shifts the burden of "did my run converge?" from manual inspection
   to an automated check that is part of the artefact bundle.
3. **Reproducible artefacts**: multi-snapshot HDF5 output with a
   GADGET-like layout, a self-contained PDF report, the LaTeX source for that
   report (for users targeting Overleaf or journal submission), and a raw
   JSON of the analysis for downstream Python work.
4. **Gaia DR3 integration** [@gaiacollaboration2023] to initialise a
   simulation from an observed open cluster by name (e.g. `pleiades`, `m67`),
   bridging the gap between observation and N-body experiment.

The project also serves as a reference architecture for combining HPC
numerics with modern enterprise patterns (hexagonal architecture, micro-frontends
via Native Federation, Java's Foreign Function & Memory API for zero-copy
Fortran/JVM interop).

# Architecture

The kernel exposes three C-ABI entry points (`nbody_init_plummer`,
`nbody_step`, multi-snapshot HDF5 I/O) that the JVM binds via the Foreign
Function & Memory API. Snapshots are persisted into a single
`{jobId}-multi.h5` per run, with `/Snapshots/NNNNN/{Coordinates,Velocities,Masses}`
datasets and a `/Header` group carrying the reproducibility manifest. The
service layer follows hexagonal architecture (`domain`, `application`,
`infrastructure`) with three ports: a REST controller for batch jobs, a
WebSocket for the interactive viewer, and a JPA adapter for the PostgreSQL
manifest. The Angular front end is a `shell-app` host that loads the
`simulation-mfe` remote at runtime through `@angular-architects/native-federation`.

# Validation

Quality is enforced at three levels.

**Kernel level.** Two integrator-physics tests run in CI on every commit:
a two-body Kepler closure (the circular-orbit period of $2\pi$ in Hénon units
must be reproduced to within $10^{-3}$ of the orbital radius after one full
period) and a Plummer $N{=}100$ energy-conservation test ($|\Delta E/E| <
5 \times 10^{-3}$ over $1000$ leapfrog steps).

**Run level.** Every finished simulation passes through six checks that
classify it against literature tolerances: $|\Delta E/E_0|$ at the final step
and at the worst step; $|\Delta L/L_0|$; the offset of the time-averaged
virial ratio $\langle Q \rangle = \langle -2K/U \rangle$ from unity over the
second half of the run; the half-mass radius ratio $r_{50}(t_f)/r_{50}(0)$;
and the escaper fraction $N_{\rm esc}/N$. The aggregate verdict
(`NBODY6-grade` / `Marginal` / `Failed`) is rendered as a coloured badge in
the UI and as a structured JSON document at
`GET /api/jobs/{id}/validation`.

**End-to-end.** A Playwright suite drives the actual browser through the
job-submission, badge-rendering, and report-download flows. Together these
yield 50+ automated checks across the kernel, the JVM service, and the UI.

Figure \ref{fig:validation} shows a representative `pleiades` run with
$N=3000$ and $5000$ leapfrog steps. The total energy line (black) is visually
flat — the actual relative drift is $|\Delta E/E_0| \approx 3 \times 10^{-6}$,
two orders of magnitude below the strict `NBODY6-grade` threshold. The virial
ratio oscillates within a $\pm 1.5\%$ band around unity, and the Lagrangian
radii ($r_{10}$, $r_{50}$, $r_{90}$) are stable throughout, confirming that
the cluster does not expand or collapse. All six validation checks therefore
mark this run as `pass`, and the badge in the UI renders green.

![Validation overview of a `pleiades` run with $N=3000$, $\Delta t = 0.005$,
$5000$ steps. Top: kinetic, potential, and total energy in Hénon units —
the total energy line is visually flat. Middle: virial ratio
$Q = -2K/U$, oscillating within $\pm 1.5\%$ of unity. Bottom: Lagrangian
radii enclosing 10, 50, and 90 % of the cluster mass.
\label{fig:validation}](figures/validation_overview.pdf){width=85%}

# Reproducibility

Every run produces a JSON manifest stored in both PostgreSQL and the HDF5
file's `/Header/Manifest` group, recording the kernel git SHA, binary
SHA-256, compiler version and flags, OpenMP thread count, hardware
description, scenario YAML hash, and all simulation parameters including
the RNG seed. With matching kernel binary and thread count, runs are
bit-exact reproducible. The validation report and the LaTeX/PDF artefacts
are similarly deterministic, so two researchers re-running the same
scenario on the same hardware obtain identical bundles.

# Acknowledgements

`astro` builds on the long tradition of direct-summation stellar dynamics
codes pioneered by Aarseth and on the cluster-evolution framework developed
by Hénon and Heggie. We are grateful to the open-source ecosystem around
HDF5, Spring Boot, and Angular for making a stack of this breadth tractable
for a single developer.

# References
