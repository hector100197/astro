"""
astro_nbody — Python wrapper around the Fortran N-body kernel.

Wraps ``libnbody`` (compiled in ``kernel/build/``) via ctypes and exposes a
NumPy-friendly :class:`Simulation` API plus HDF5 I/O compatible with the
broader astrophysics ecosystem (``yt-project``, ``h5py``, ``astropy``).

Quick start::

    import astro_nbody as nb

    sim = nb.Simulation(n=3000, scenario="plummer", seed=42)
    sim.run(steps=10_000, dt=0.001)
    sim.save("out.h5")

    # Diagnostics every step:
    times, energies = sim.energy_history()

The kernel is the same shared library that the Java ``simulation-service``
loads via FFM, so Python and the live web service produce bit-identical
results given the same parameters and OpenMP thread count.
"""

from __future__ import annotations

__version__ = "0.1.0"

from .simulation import Simulation, Diagnostics
from ._kernel import load_kernel
from .scenarios import load_scenario, list_scenarios

__all__ = [
    "Simulation",
    "Diagnostics",
    "load_kernel",
    "load_scenario",
    "list_scenarios",
]
