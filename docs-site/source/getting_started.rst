Getting started
================

This page mirrors the README's three-path layout. Pick the one that matches
how you want to use ``astro``.

.. note::

   The exact ``apt`` / ``brew`` / ``dnf`` commands for each prerequisite live
   in `INSTALL.md <https://github.com/hector100197/astro/blob/main/INSTALL.md>`_
   so they stay in one place. This page links to the right section.

Path A — Python wrapper
-----------------------

For users who live in notebooks and want HDF5/NumPy access to the kernel.

**Prerequisites:** Python 3.10+, ``gfortran``, GNU ``make``.

.. code-block:: bash

   git clone https://github.com/hector100197/astro.git
   cd astro
   make -C kernel                  # ~10 s

   cd python
   python3 -m venv .venv
   source .venv/bin/activate
   pip install -e '.[dev,notebooks]'

Continue with :doc:`tutorials/python_wrapper`.

Path B — Web UI
---------------

For users who want the interactive viewer + automatic validation.

**Prerequisites:** Docker Desktop, Java 21, Maven 3.9+, Node.js 22+, gfortran.

.. code-block:: bash

   git clone https://github.com/hector100197/astro.git
   cd astro
   ./scripts/dev-setup.sh          # warns about anything missing
   make dev                        # ~30 s to ready

Open http://localhost:4200 and follow :doc:`tutorials/first_simulation`.

Path C — Developer setup
------------------------

If you want to modify the kernel, services, or frontends, follow
`DEVELOPMENT.md
<https://github.com/hector100197/astro/blob/main/DEVELOPMENT.md>`_.

Verifying your install
----------------------

After installing the prerequisites for your chosen path, run from the repo
root:

.. code-block:: bash

   ./scripts/dev-setup.sh

This validates every required binary is on your ``PATH`` and installs the
per-layer dependencies (npm packages, Python ``pip install -e .``, Sphinx
requirements). If it prints ``MISSING`` for anything, see the troubleshooting
section in
`INSTALL.md <https://github.com/hector100197/astro/blob/main/INSTALL.md#troubleshooting>`_.
