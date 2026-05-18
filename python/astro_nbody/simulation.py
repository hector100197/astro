"""High-level :class:`Simulation` API on top of the ctypes kernel binding."""

from __future__ import annotations

import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np

from ._kernel import load_kernel, Kernel


@dataclass(frozen=True)
class Diagnostics:
    """Per-snapshot physics diagnostics, mirroring the Java domain ``Diagnostics``."""
    sim_time: float
    step: int
    K: float                          # kinetic energy
    U: float                          # potential energy
    E: float                          # total energy
    P: np.ndarray                     # linear momentum, shape (3,)
    L: np.ndarray                     # angular momentum, shape (3,)
    Q: float                          # virial ratio 2K/|U|


class Simulation:
    """An N-body simulation backed by the Fortran kernel.

    Positions, velocities and masses are stored as ``np.float64`` arrays so
    they can be passed directly to the kernel without copies. The kernel
    mutates them in place during :meth:`step`.
    """

    def __init__(
        self,
        n: int = 3000,
        scenario: str = "plummer",
        seed: int = 42,
        softening: float = 0.01,
        library_path: Optional[os.PathLike] = None,
    ):
        if n < 2:
            raise ValueError("N must be at least 2")
        self.n = n
        self.scenario = scenario
        self.seed = seed
        self.softening = softening
        self._kernel: Kernel = load_kernel(library_path)

        # Allocate SoA buffers
        self.x  = np.zeros(n, dtype=np.float64)
        self.y  = np.zeros(n, dtype=np.float64)
        self.z  = np.zeros(n, dtype=np.float64)
        self.vx = np.zeros(n, dtype=np.float64)
        self.vy = np.zeros(n, dtype=np.float64)
        self.vz = np.zeros(n, dtype=np.float64)
        self.m  = np.zeros(n, dtype=np.float64)

        self.sim_time = 0.0
        self.step_index = 0

        self._init_state()

    def _init_state(self) -> None:
        if self.scenario == "plummer":
            self._kernel.init_plummer(
                self.x, self.y, self.z,
                self.vx, self.vy, self.vz,
                self.m, self.seed,
            )
        else:
            raise ValueError(
                f"Unknown scenario {self.scenario!r}. "
                f"Built-in: 'plummer'. "
                f"Use load_scenario('<name>') for YAML scenarios in scenarios/."
            )

    # ------------------------------------------------------------------ run

    def step(self, dt: float) -> None:
        """Advance one leapfrog step of size ``dt``."""
        self._kernel.step(
            self.x, self.y, self.z,
            self.vx, self.vy, self.vz,
            self.m, dt, self.softening,
        )
        self.sim_time += dt
        self.step_index += 1

    def run(self, steps: int, dt: float = 0.005, progress: bool = False) -> None:
        """Advance the simulation by ``steps`` integration steps."""
        if progress:
            t0 = time.perf_counter()
            for s in range(steps):
                self.step(dt)
                if (s + 1) % max(1, steps // 20) == 0:
                    elapsed = time.perf_counter() - t0
                    pct = 100 * (s + 1) / steps
                    print(f"  [{pct:5.1f}%] step {s + 1}/{steps}  sim_t={self.sim_time:.3f}  elapsed={elapsed:.1f}s")
        else:
            for _ in range(steps):
                self.step(dt)

    # ------------------------------------------------------------------ diagnostics

    def diagnostics(self) -> Diagnostics:
        """Compute K, U, E, P, L and virial ratio Q from the current state.

        This mirrors the Java :code:`RealDiagnosticsCalculator` to machine
        precision, since both implementations use the same softened ``r²``
        formula and the kernel's float64 state.
        """
        K = 0.5 * np.sum(self.m * (self.vx**2 + self.vy**2 + self.vz**2))
        Px = np.sum(self.m * self.vx)
        Py = np.sum(self.m * self.vy)
        Pz = np.sum(self.m * self.vz)
        Lx = np.sum(self.m * (self.y * self.vz - self.z * self.vy))
        Ly = np.sum(self.m * (self.z * self.vx - self.x * self.vz))
        Lz = np.sum(self.m * (self.x * self.vy - self.y * self.vx))

        # O(N²) potential, vectorised: build pairwise distances and sum upper-triangular.
        dx = self.x[:, None] - self.x[None, :]
        dy = self.y[:, None] - self.y[None, :]
        dz = self.z[:, None] - self.z[None, :]
        r = np.sqrt(dx * dx + dy * dy + dz * dz + self.softening ** 2)
        np.fill_diagonal(r, np.inf)        # avoid self-interaction term
        mm = self.m[:, None] * self.m[None, :]
        U = -0.5 * np.sum(mm / r)          # ½ to undo the double counting

        E = K + U
        Q = (-2.0 * K / U) if U != 0.0 else float("nan")

        return Diagnostics(
            sim_time=self.sim_time,
            step=self.step_index,
            K=float(K), U=float(U), E=float(E),
            P=np.array([Px, Py, Pz]),
            L=np.array([Lx, Ly, Lz]),
            Q=float(Q),
        )

    # ------------------------------------------------------------------ I/O

    def save(self, path: os.PathLike) -> Path:
        """Write the current snapshot to an HDF5 file via the Fortran kernel.

        The format matches what the Java :code:`FortranHdf5Writer` produces —
        files are interchangeable between Python and the web service.
        """
        path = Path(path).expanduser().resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        status = self._kernel.write_snapshot_h5(
            path,
            self.x, self.y, self.z,
            self.vx, self.vy, self.vz,
            self.m,
            self.sim_time,
        )
        if status != 0:
            raise IOError(f"HDF5 write failed (kernel status={status})")
        return path

    @classmethod
    def from_hdf5(cls, path: os.PathLike) -> "Simulation":
        """Load a saved snapshot back into a :class:`Simulation` instance.

        Uses :mod:`h5py` to read the GADGET-like layout written by
        :meth:`save` (or the Java service's :code:`FortranHdf5Writer`).
        """
        import h5py
        with h5py.File(path, "r") as f:
            # HDF5 attributes from Fortran are 1-element arrays, not scalars.
            n = int(np.asarray(f["Header"].attrs["NumPart"]).flat[0])
            t = float(np.asarray(f["Header"].attrs["Time"]).flat[0])
            coords = np.array(f["PartType1/Coordinates"])
            vels   = np.array(f["PartType1/Velocities"])
            masses = np.array(f["PartType1/Masses"])

        # Build a sim and inject state (skip _init_state's Plummer call by using object.__new__).
        sim = object.__new__(cls)
        sim.n = n
        sim.scenario = "from_hdf5"
        sim.seed = -1
        sim.softening = 0.01
        sim._kernel = load_kernel()
        # HDF5 stores 3×N (Fortran order); coords in our file are [3, N], so transpose.
        if coords.shape[0] == 3:
            sim.x  = np.ascontiguousarray(coords[0])
            sim.y  = np.ascontiguousarray(coords[1])
            sim.z  = np.ascontiguousarray(coords[2])
            sim.vx = np.ascontiguousarray(vels[0])
            sim.vy = np.ascontiguousarray(vels[1])
            sim.vz = np.ascontiguousarray(vels[2])
        else:
            sim.x, sim.y, sim.z = (np.ascontiguousarray(c) for c in coords.T)
            sim.vx, sim.vy, sim.vz = (np.ascontiguousarray(c) for c in vels.T)
        sim.m = np.ascontiguousarray(masses)
        sim.sim_time = t
        sim.step_index = 0
        return sim
