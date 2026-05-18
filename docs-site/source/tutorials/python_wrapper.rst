Python wrapper — quickstart
============================

The Python wrapper (``astro_nbody``) lets you drive the same Fortran kernel
that the live UI uses, from inside a Jupyter notebook or a shell script. Both
paths produce bit-identical results for matching parameters and OpenMP
thread count.

Install
-------

See :doc:`../getting_started` (Path A). In summary:

.. code-block:: bash

   make -C kernel
   cd python
   python3 -m venv .venv
   source .venv/bin/activate
   pip install -e '.[dev,notebooks]'

A four-line simulation
----------------------

.. code-block:: python

   import astro_nbody as nb

   sim = nb.Simulation(n=3000, scenario="plummer", seed=42)
   sim.run(steps=10_000, dt=0.005, progress=True)
   sim.save("my_run.h5")

   print(sim.diagnostics())
   # Diagnostics(K=0.247, U=-0.502, E=-0.254, Q=0.984, ...)

The HDF5 layout matches what the live service writes, so you can drop
``my_run.h5`` into the **Mis runs** drawer in the UI and replay it visually.

Scenario catalog
----------------

.. code-block:: python

   nb.list_scenarios()
   # ['aarseth_standard', 'gaia_hyades', 'gaia_m67', 'gaia_pleiades',
   #  'henon_heiles', 'pleiades', 'solar_system', 'three_body_figure8',
   #  'three_body_pythagorean']

   pleiades = nb.load_scenario("pleiades")
   print(pleiades["n_bodies"], pleiades["simulation"]["dt"])

CLI
---

The ``nbody-sim`` entry point gives you the same machinery from a shell:

.. code-block:: bash

   nbody-sim --scenario pleiades --output pleiades.h5 --diagnostics
   nbody-sim --n 1500 --steps 5000 --output run.h5 --diagnostics
   nbody-sim --config my_custom_scenario.yaml --output run.h5

Convenient for HPC batch submission: write a thin SLURM script that calls
``nbody-sim`` with the parameters you want; the resulting HDF5 is the same
artefact the GUI consumes.

Loading a previous run
----------------------

.. code-block:: python

   sim = nb.Simulation.from_hdf5("pleiades.h5")
   print(sim.n, sim.t)        # bodies + current time after the last snapshot

   # Access positions / velocities / masses as numpy arrays:
   import numpy as np
   r = np.sqrt(sim.x**2 + sim.y**2 + sim.z**2)
   print("Half-mass radius:", np.median(r))

Importing a real Gaia cluster
-----------------------------

The same Gaia importer that the Web UI uses is exposed as a CLI:

.. code-block:: bash

   python -m astro_nbody.gaia_import m67 -o scenarios/gaia_m67.yaml

After the script finishes (5–30 s), the YAML lives in ``scenarios/`` and
the next time you call ``nb.list_scenarios()`` (or restart the service)
``gaia_m67`` will be available. See :doc:`gaia_import` for caveats.

Going further
-------------

* The fully-worked notebook is in
  `python/notebooks/01_quickstart.ipynb
  <https://github.com/hector100197/astro/blob/main/python/notebooks/01_quickstart.ipynb>`_.
* The Python wrapper is intentionally *thin* — it's just a NumPy facade
  over the Fortran kernel. Heavy analysis (binary detection, escaper
  classification, plotting) lives in
  ``python/astro_nbody/report_plots.py`` and is shared with the JVM
  service, so what you see in the PDF and in a notebook is computed by
  the same code.
* For programmatic access to the run's automatic validation verdict,
  inspect the ``Diagnostics`` object returned by ``sim.diagnostics()``
  or fetch the structured report over REST when running via the GUI
  (:doc:`validation_badge`).
