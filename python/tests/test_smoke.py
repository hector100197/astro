"""Smoke tests for the astro_nbody Python wrapper.

These exercise the real ctypes/FFM binding to the Fortran kernel: a broken
kernel build, a wrong ABI, or a regressed leapfrog integrator all surface
here. They are intentionally tiny (N=64, a few hundred steps) so they run
in a second or two while still being physically meaningful.
"""
from __future__ import annotations

import math

import astro_nbody as nb


def test_public_api_surface():
    """The documented public symbols are importable."""
    assert hasattr(nb, "Simulation")
    assert hasattr(nb, "Diagnostics")
    assert callable(nb.list_scenarios)
    assert callable(nb.load_scenario)
    assert callable(nb.load_kernel)


def test_list_scenarios_includes_known_clusters():
    scenarios = nb.list_scenarios()
    assert isinstance(scenarios, list)
    assert len(scenarios) > 0
    # pleiades ships in the catalog and is used across the docs/tutorials.
    assert "pleiades" in scenarios


def test_tiny_plummer_run_conserves_energy():
    """A short Plummer run must produce finite, physical, energy-conserving
    diagnostics — this is the end-to-end check that the native kernel loads
    and the symplectic integrator is wired correctly."""
    sim = nb.Simulation(n=64, scenario="plummer", seed=1)

    d0 = sim.diagnostics()
    assert math.isfinite(d0.E)
    assert d0.K > 0.0          # kinetic energy is positive
    assert d0.U < 0.0          # bound system: potential energy is negative

    sim.run(steps=200, dt=0.005)

    d1 = sim.diagnostics()
    assert math.isfinite(d1.E)
    assert d1.step > d0.step
    assert d1.sim_time > d0.sim_time

    # Leapfrog is symplectic: relative energy drift over a short run stays
    # tiny. 1e-2 is a deliberately generous bound that still fails loudly
    # if the integrator or the kernel binding is broken.
    rel_drift = abs(d1.E - d0.E) / abs(d0.E)
    assert rel_drift < 1e-2, f"energy drift too large: {rel_drift:.3e}"

    # Virial ratio of a Plummer model stays of order unity.
    assert 0.0 < d1.Q < 10.0
