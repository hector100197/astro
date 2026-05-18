Import a real cluster from Gaia DR3
====================================

``astro`` can pull a real open cluster from the ESA Gaia DR3 archive,
filter members by parallax and proper motion, and turn the result into
a scenario YAML that the simulator runs natively.

Currently supported clusters: ``pleiades`` (Pleiades / M45), ``hyades``,
and ``m67``. Adding more is a matter of adding an entry in
``python/astro_nbody/gaia_import.py`` → ``KNOWN_CLUSTERS`` with the
cluster's centre, search radius, and parallax / proper-motion membership
window.

Two ways to use it
------------------

From the Web UI
~~~~~~~~~~~~~~~

Open the Batch jobs drawer (top header). At the top of the drawer you'll
see a new section **"Importar cluster real (Gaia DR3)"** with a
drop-down (Pleiades / Hyades / M67) and a ``↓ Gaia DR3`` button.

1. Pick a cluster.
2. Click **↓ Gaia DR3**.
3. Wait 5–30 seconds (the wall time is dominated by the ESA Gaia archive
   round-trip).
4. A confirmation appears: ``OK — escenario gaia_<name> (N=…) disponible
   en el dropdown.`` The new scenario is auto-selected in the
   **Escenario** dropdown of the **Lanzar nuevo job** form.
5. Click ▶ **Lanzar job** to run the imported cluster.

From the command line
~~~~~~~~~~~~~~~~~~~~~

If you have the Python wrapper installed (:doc:`python_wrapper`):

.. code-block:: bash

   source python/.venv/bin/activate
   python -m astro_nbody.gaia_import m67 -o scenarios/gaia_m67.yaml

The simulator picks up the new scenario at the next restart of
``simulation-service``.

What you get
------------

The resulting YAML uses ``initial_condition.type: explicit`` — the actual
positions and velocities of the brightest ~200 cluster members from
Gaia DR3, recentred on the cluster's centre of mass and normalised to
Hénon units (G = M = R\ :sub:`h` = 1). The header section in the YAML
records the provenance (catalog, half-mass radius in parsecs, total mass
assumed) so the run is auditable.

Caveats — important
-------------------

A direct simulation of the raw Gaia members will almost always trip the
validation badge red. This is **physically correct** and is precisely
what the badge is designed to surface. The reasons:

* **Radial-velocity uncertainties** in Gaia DR3 are several km/s for
  faint stars, often much larger than the cluster's true velocity
  dispersion (~0.8 km/s for M67). The brightest-200 subset still
  inherits some of that noise.
* **Membership filtering** by parallax + proper motion is conservative
  but not perfect — a few contaminating field stars (faster than the
  cluster) may pass through.
* **Uniform mass assumption** — every body is given mass ``1/N``. The
  cluster's real stellar mass function (Salpeter / Kroupa) is not yet
  reconstructed from Gaia photometry.

The combined effect: when normalised to Hénon units, the imported cluster
has more kinetic energy than its potential can bind, so it starts
*unbound* (:math:`Q = -2K/U \gg 1`) and disperses violently within a
few half-mass crossing times.

For meaningful physics with imported data you currently have to either:

* shrink the integration window so you study the *initial* dynamics
  before the dispersion is large, or
* sigma-clip the velocity distribution before feeding it to the
  simulator (V2 roadmap).

Either way, the badge correctly tells you which regime you're in.

Architecture note
-----------------

The Gaia importer lives entirely in the Python layer; the JVM service
spawns ``python -m astro_nbody.gaia_import`` as a subprocess and copies
the resulting YAML into the catalog directory. This is the same pattern
used for the matplotlib plot generation in the PDF report — pure-Python
helpers that read JSON / write files, never linked into the JVM.
