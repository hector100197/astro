Your first simulation (Web UI)
==============================

This tutorial assumes you have the full stack running locally
(``make dev`` succeeded — if not, see :doc:`../getting_started`). It walks
through launching a real Pleiades-like run and reading the report.

Step 1 — Open the simulator
---------------------------

Navigate to http://localhost:4200.

You should see:

* The astro · N-body header (top-left), the **Controles** panel (right) with
  the live viewer running a Plummer ``aarseth_standard`` cluster.
* A 3D field of ~100 stars rotating slowly in the canvas — that's the live
  WebSocket stream from ``simulation-service`` rendering at 60 Hz.

If the viewer doesn't start, click the ▶ **Pausar/Reanudar** button or check
``http://localhost:8081/actuator/health`` returns ``{"status":"UP"}``.

Step 2 — Open the Batch jobs drawer
-----------------------------------

In the top header, click **Batch jobs**. A side drawer opens from the left.
This is where you launch and inspect headless (long) runs — the
``Controles`` panel on the right drives the *live* viewer, while batch jobs
go through the persisted pipeline that ends with a validation badge.

Step 3 — Launch a job with defaults
-----------------------------------

In the drawer:

1. Leave **Escenario** on ``pleiades (N=3000)`` — this is a synthetic Plummer
   model with N=3000 stars matched to the Pleiades half-mass radius.
2. Leave **Pasos totales** = ``5000``, **Snapshot cada N pasos** = ``50``,
   **Δt** = ``0.005``. These defaults produce 101 snapshots over ~25 Hénon
   time units (about one half-mass crossing time).
3. Click **▶ Lanzar job**.

The job appears at the top of the list with status ``queued`` →
``running`` (with a progress bar) → ``completed``. With the Fortran kernel
on an M-series CPU this finishes in ~30 s.

Step 4 — Read the validation badge
----------------------------------

When status hits ``completed``, two new badges appear next to it:

* **completed** (green) — pipeline finished without error.
* **✓ NBODY6-grade / ⚠ Marginal / ✗ Failed** — the six-check verdict.

For the default Pleiades parameters, the verdict is almost always
**``✓ NBODY6-grade``**. Click the badge to expand a panel with the six
individual checks:

* Energy conservation (final + worst) — should be ``≤ 1e-3``
* Angular momentum conservation — leapfrog gives ``~1e-15``
* Virial equilibrium — ``Q = -2K/U`` should oscillate around 1
* Half-mass radius stability — the cluster should not expand more than 3×
* Escaper fraction — fewer than 5 % of bodies become unbound

The page that explains the physics of each check is
:doc:`validation_badge`.

Step 5 — Download the report
----------------------------

Below the badges, every completed job exposes four downloads:

* **HDF5** — the raw multi-snapshot file (positions, velocities, masses
  per snapshot, plus a ``/Header/Manifest`` with the reproducibility
  metadata).
* **PDF** — a single-file scientific report with parameters, conservation
  diagnostics, the binary catalog, the escaper catalog, and five embedded
  matplotlib figures.
* **.tex** — the LaTeX source of that PDF, ready for Overleaf or journal
  submission.
* **JSON** — the raw analysis output (``timeline``, ``binaries``,
  ``escapers``, ``conservation``) — convenient if you want to plot or
  rebuild figures yourself.

Step 6 — Try the comparison mode
--------------------------------

Click **▦ Comparar** in the top header. A second viewer panel appears. You
can change ``Δt`` or ``N`` in one panel and watch how the cluster evolution
diverges between the two — a visual demonstration of how integrator
parameters matter.

Step 7 — Click to follow a star
-------------------------------

In any viewer, click directly on a bright star. A fading trail draws its
recent orbit, and a small overlay shows that body's index, position, and
velocity. Click on empty space to deselect.

What to try next
----------------

* Import a real cluster from Gaia DR3 → :doc:`gaia_import`
* Push the integrator until the badge turns red — try ``dt = 0.05`` →
  :doc:`validation_badge`
* Drive the same kernel from Python → :doc:`python_wrapper`
