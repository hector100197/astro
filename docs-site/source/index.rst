astro — N-body stellar cluster simulator
==========================================

A high-performance N-body simulator for stellar clusters, combining a
Fortran 2018 + OpenMP numerical kernel with a Java 21 / Spring Boot
microservices backend and an Angular 21 micro-frontend visualisation layer.

Designed for **reproducible research** and **computational physics
education**. Every finished simulation is automatically graded against
literature tolerances (six physical checks → ``NBODY6-grade`` /
``Marginal`` / ``Failed``) and bundled as a downloadable report
(PDF + LaTeX + JSON + HDF5).

Where to start
--------------

* **Just want to use the GUI?** → :doc:`tutorials/first_simulation`
* **Coming from Python/Jupyter?** → :doc:`tutorials/python_wrapper`
* **Want to plug in a real cluster from Gaia?** → :doc:`tutorials/gaia_import`
* **What does the validation badge mean?** → :doc:`tutorials/validation_badge`

Quick install reminder
----------------------

Three different paths, each with its own minimal dependencies — pick one in
:doc:`getting_started` or in the
`top-level README <https://github.com/hector100197/astro#pick-your-path>`_.

.. toctree::
   :maxdepth: 2
   :caption: Getting started

   getting_started

.. toctree::
   :maxdepth: 2
   :caption: Tutorials

   tutorials/first_simulation
   tutorials/gaia_import
   tutorials/validation_badge
   tutorials/python_wrapper

Citing
------

If you use ``astro`` in a publication, cite the Zenodo-archived release
(BibTeX in the top-level
`README <https://github.com/hector100197/astro#citation>`_).

License
-------

MIT — see the `LICENSE
<https://github.com/hector100197/astro/blob/main/LICENSE>`_ file.
