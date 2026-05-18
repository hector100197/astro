"""
Low-level ctypes bindings to ``libnbody.dylib`` / ``libnbody.so``.

The Fortran kernel exposes three C-bound entry points (see
``kernel/src/nbody_api.f90``):

  * ``nbody_init_plummer(x, y, z, vx, vy, vz, m, n, seed)``
  * ``nbody_step(x, y, z, vx, vy, vz, m, n, dt, eps)``
  * ``nbody_write_snapshot_h5(path, x, y, z, vx, vy, vz, m, n, sim_time) -> int``

ctypes is used (rather than f2py) because:
  1. We can reuse the **same** binary that the Java service consumes via FFM —
     no separate Python build of the kernel.
  2. ctypes is in the Python stdlib; no extra build dependency.
  3. The ABI is plain C (Fortran's ``bind(C)``), trivial to map.

The :func:`load_kernel` function caches the loaded library, so multiple
``Simulation`` instances share one DLL handle.
"""

from __future__ import annotations

import ctypes
import os
import platform
import sys
from pathlib import Path
from typing import Optional

import numpy as np

_kernel_cache: Optional["Kernel"] = None


def _default_kernel_path() -> Path:
    """Walk up from this file looking for ``kernel/build/libnbody.{dylib,so}``."""
    here = Path(__file__).resolve()
    ext = "dylib" if platform.system() == "Darwin" else "so"
    # python/astro_nbody/_kernel.py → repo root is two parents up.
    for parent in (here.parent.parent.parent, *here.parents):
        candidate = parent / "kernel" / "build" / f"libnbody.{ext}"
        if candidate.exists():
            return candidate
    raise FileNotFoundError(
        f"libnbody.{ext} not found near {here}. Build the kernel first: make -C kernel"
    )


class Kernel:
    """Thin wrapper around the loaded shared library and its callable handles."""

    def __init__(self, library_path: Optional[os.PathLike] = None):
        self.library_path = Path(library_path) if library_path else _default_kernel_path()
        self.lib = ctypes.CDLL(str(self.library_path))

        # Argument typing for each function. NumPy arrays are passed via
        # ndarray.ctypes.data_as(POINTER(c_double)) so the call has near-zero
        # marshalling cost (no copy).
        DOUBLE_P = ctypes.POINTER(ctypes.c_double)

        self.lib.nbody_init_plummer.argtypes = [
            DOUBLE_P, DOUBLE_P, DOUBLE_P,
            DOUBLE_P, DOUBLE_P, DOUBLE_P,
            DOUBLE_P, ctypes.c_int, ctypes.c_int,
        ]
        self.lib.nbody_init_plummer.restype = None

        self.lib.nbody_step.argtypes = [
            DOUBLE_P, DOUBLE_P, DOUBLE_P,
            DOUBLE_P, DOUBLE_P, DOUBLE_P,
            DOUBLE_P, ctypes.c_int,
            ctypes.c_double, ctypes.c_double,
        ]
        self.lib.nbody_step.restype = None

        self.lib.nbody_write_snapshot_h5.argtypes = [
            ctypes.c_char_p,
            DOUBLE_P, DOUBLE_P, DOUBLE_P,
            DOUBLE_P, DOUBLE_P, DOUBLE_P,
            DOUBLE_P, ctypes.c_int,
            ctypes.c_double,
        ]
        self.lib.nbody_write_snapshot_h5.restype = ctypes.c_int

    @staticmethod
    def _ptr(arr: np.ndarray):
        if arr.dtype != np.float64:
            raise TypeError(f"Expected float64, got {arr.dtype}")
        if not arr.flags["C_CONTIGUOUS"]:
            raise ValueError("Array must be C-contiguous (use np.ascontiguousarray)")
        return arr.ctypes.data_as(ctypes.POINTER(ctypes.c_double))

    def init_plummer(
        self,
        x: np.ndarray, y: np.ndarray, z: np.ndarray,
        vx: np.ndarray, vy: np.ndarray, vz: np.ndarray,
        m: np.ndarray,
        seed: int,
    ) -> None:
        """Generate Plummer initial conditions in place. All arrays must be float64, length N."""
        n = len(x)
        self.lib.nbody_init_plummer(
            self._ptr(x), self._ptr(y), self._ptr(z),
            self._ptr(vx), self._ptr(vy), self._ptr(vz),
            self._ptr(m), ctypes.c_int(n), ctypes.c_int(seed),
        )

    def step(
        self,
        x: np.ndarray, y: np.ndarray, z: np.ndarray,
        vx: np.ndarray, vy: np.ndarray, vz: np.ndarray,
        m: np.ndarray,
        dt: float, eps: float,
    ) -> None:
        """One leapfrog kick-drift-kick step, in place."""
        n = len(x)
        self.lib.nbody_step(
            self._ptr(x), self._ptr(y), self._ptr(z),
            self._ptr(vx), self._ptr(vy), self._ptr(vz),
            self._ptr(m), ctypes.c_int(n),
            ctypes.c_double(dt), ctypes.c_double(eps),
        )

    def write_snapshot_h5(
        self,
        path: os.PathLike,
        x: np.ndarray, y: np.ndarray, z: np.ndarray,
        vx: np.ndarray, vy: np.ndarray, vz: np.ndarray,
        m: np.ndarray,
        sim_time: float,
    ) -> int:
        """Write a single-snapshot HDF5 file. Returns 0 on success."""
        n = len(x)
        path_bytes = str(path).encode("utf-8")
        return int(self.lib.nbody_write_snapshot_h5(
            path_bytes,
            self._ptr(x), self._ptr(y), self._ptr(z),
            self._ptr(vx), self._ptr(vy), self._ptr(vz),
            self._ptr(m), ctypes.c_int(n),
            ctypes.c_double(sim_time),
        ))


def load_kernel(library_path: Optional[os.PathLike] = None) -> Kernel:
    """Load (or return cached) Kernel handle to libnbody."""
    global _kernel_cache
    if _kernel_cache is None or (library_path and Path(library_path) != _kernel_cache.library_path):
        _kernel_cache = Kernel(library_path)
    return _kernel_cache
