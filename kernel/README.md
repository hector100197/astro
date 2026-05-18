# kernel — Fortran 2018 + OpenMP numerical core

## Build

```bash
make           # builds libnbody.dylib (macOS) / libnbody.so (Linux)
make test      # runs pFUnit-based unit tests
make clean     # removes build artifacts
```

## Layout

```
src/
  nbody_core.f90    Integrators (leapfrog), force calculators (brute force O(N²)), initial conditions (Plummer)
  nbody_io.f90      HDF5 writer following GADGET-like layout
  nbody_api.f90     C-bindings exposed via ISO_C_BINDING for Java FFM
tests/
  test_kepler.f90   Two-body Kepler problem — exact analytical comparison
  test_energy.f90   Energy conservation drift over 10⁴ steps
  test_virial.f90   Virial theorem on relaxed Plummer sphere
```

## Compile flags

Production:
```
-O3 -march=native -fopenmp -fimplicit-none -std=f2018 -Wall
```

Development:
```
-O0 -g -fopenmp -fimplicit-none -fbounds-check -std=f2018 -Wall -Wextra
```

## Determinism

For bit-exact reproducibility, OpenMP thread count must be pinned via `OMP_NUM_THREADS`. Floating-point reductions in OpenMP are not associative — different thread counts produce different bit-level results, even if numerically equivalent. The reproducibility manifest records the thread count used.

## Calling from Java (FFM)

```java
import java.lang.foreign.*;

SymbolLookup lookup = SymbolLookup.libraryLookup("libnbody.dylib", Arena.global());
MethodHandle nbodyStep = Linker.nativeLinker().downcallHandle(
    lookup.find("nbody_step").orElseThrow(),
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE)
);
```

The C-bindings exposed in `nbody_api.f90` use `bind(C)` with explicit interfaces.
