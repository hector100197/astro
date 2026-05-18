Interpret the validation badge
==============================

Every finished simulation gets a one-glance verdict:

* **✓ NBODY6-grade** (green) — every check is within the strict tolerance
  band. The run is publication-ready.
* **⚠ Marginal** (yellow) — at least one check is in the warning band but
  no critical failure. Useful but worth a second look.
* **✗ Failed** (red) — at least one check exceeds the failure threshold.
  Either the integrator parameters are wrong (Δt too large, softening too
  small) or the initial conditions are pathological.

Click any badge in the **Batch jobs** drawer to expand a panel showing the
six individual checks behind the verdict, with the observed value, both
thresholds, and a human-readable hint.

The six checks
--------------

1. ``|ΔE/E₀|`` (final) — energy conservation at the last step
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

   * pass ≤ 1×10⁻³, warn ≤ 1×10⁻², else fail.
   * Reference: NBODY6, GADGET-4, PETAR consistently achieve
     ``< 1e-3`` on the Aarseth N=3000 benchmark.

2. ``|ΔE/E₀|`` (worst step) — worst-case energy deviation across all snapshots
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

   * Same thresholds as the final check.
   * Catches transient blow-ups that recover by the end of the run
     (close encounters that exchange energy briefly).

3. ``|ΔL/L₀|`` — angular momentum conservation
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

   * pass ≤ 1×10⁻¹⁰, warn ≤ 1×10⁻⁶, else fail.
   * The leapfrog integrator is symplectic and preserves angular
     momentum to floating-point round-off (~1×10⁻¹⁵). A run that fails
     this almost certainly has a bug in the integrator wiring.

4. ``|⟨Q⟩−1|`` (second half) — virial equilibrium
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

   * pass ≤ 0.10, warn ≤ 0.20, else fail.
   * Averaged over the second half of the timeline (the first half
     is excluded so the cluster has time to relax).
   * A relaxed bound cluster oscillates around ``Q = -2K/U = 1``
     (virial theorem).

5. ``r₅₀(t_f) / r₅₀(0)`` — half-mass radius stability
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

   * pass ≤ 3.0, warn ≤ 5.0, else fail.
   * Bound clusters expand modestly over a few crossing times. Ratios
     > 5× indicate disruption or runaway expansion.

6. ``N_esc / N`` — escaper fraction
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

   * pass ≤ 0.05, warn ≤ 0.15, else fail.
   * A body counts as an escaper when it has positive total energy
     **and** sits at ``r > 5 × r_h`` from the centre of mass for the
     first time. Stable runs lose only a handful of bodies; > 15 %
     suggests Δt is too large or the IC is pathological.

Aggregate verdict logic
-----------------------

* If any check is ``fail`` → verdict is ``fail`` (badge red).
* Otherwise if any check is ``warn`` → verdict is ``warn`` (badge yellow).
* Otherwise → verdict is ``pass`` (badge green, "NBODY6-grade").

How to make a red badge turn green
----------------------------------

Most common causes and fixes:

* **dE/E in warn or fail** → reduce Δt by 2–4× and re-run. A
  ``Plummer N=3000`` cluster with Δt=0.05 fails. With Δt=0.005 it passes.

* **Virial fail with a Gaia-imported cluster** → the imported cluster
  arrives with K ≫ |U| because Gaia velocities include large RV
  uncertainties. See :doc:`gaia_import` for context. Either shrink the
  integration window or sigma-clip the velocity distribution.

* **r₅₀ blowing up** → either the same problem (cluster not bound) or
  Δt large enough that close encounters dump energy into outliers.

* **Energy fine but angular momentum drifts** → check that the
  integrator and force loop are wired correctly. Leapfrog preserves
  L exactly; anything above 1e-10 is suspicious.

Reading the JSON directly
-------------------------

If you prefer to ingest the verdict programmatically, fetch the
structured report:

.. code-block:: bash

   curl -s http://localhost:8081/api/jobs/<jobId>/validation | jq .

The schema:

.. code-block:: json

   {
     "verdict": "pass",
     "summary": "NBODY6-grade — all 6 checks within strict tolerance.",
     "checks": [
       {
         "id": "energy_final",
         "label": "Energy conservation |ΔE/E₀| (final)",
         "severity": "pass",
         "observed": 3.3e-06,
         "passThreshold": 1e-3,
         "warnThreshold": 1e-2,
         "unit": "ratio",
         "message": "Reference codes achieve |ΔE/E₀| < 1e-3 on the Aarseth benchmark."
       },
       ...
     ]
   }
