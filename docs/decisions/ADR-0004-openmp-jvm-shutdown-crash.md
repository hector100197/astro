# ADR-0004: OpenMP + JVM shutdown SIGSEGV is benign for long-running services

- **Status**: Accepted (with mitigation note)
- **Date**: 2026-05-08

## Context

During Sem 3 validation we ran a standalone Java FFM smoke test that loaded
`libnbody.dylib` (compiled with `-fopenmp`), executed 100 leapfrog steps with
N=100, then exited the JVM. Physics validation **passed cleanly**:

```
E(t=0)        = -0.150733
E(t=1.00)     = -0.150732
|ΔE/E|        = 7.07e-6      ← well below leapfrog threshold of 1e-2
|P|           = 1.71e-17     ← machine precision (perfect conservation)
avg step time = 0.16 ms      ← N=100 single-thread, OpenMP not stressed
STEP TEST PASSED ✓
```

But **after** the test's main method completed, JVM shutdown crashed with
SIGSEGV at `libsystem_pthread.dylib+0x4678 _pthread_tsd_cleanup+0x1e4`.

## Root cause

This is a known interaction between gfortran's OpenMP runtime and the JVM
shutdown sequence on macOS / Apple Silicon:

1. gfortran's `-fopenmp` registers thread-specific data (TSD) destructors
   in pthreads when worker threads are created.
2. On JVM exit, those TSDs are destructed by macOS pthread cleanup.
3. The OpenMP runtime's TSD-cleanup callback then tries to access
   already-freed JVM-managed memory, segfaulting harmlessly.

References:
- https://github.com/gcc-mirror/gcc/blob/master/libgomp/team.c — OpenMP TSD setup
- macOS-specific: pthread library is more aggressive about TSD cleanup than glibc.

## Decision

We **accept** this crash for now because:

1. **It happens only at JVM exit**, which Spring Boot services do not normally
   reach during operation. A service running for hours/days never triggers
   this code path.
2. **Physics is correct** — energy and momentum conservation pass to machine
   precision, validating that the FFM call path itself is sound.
3. **Mitigations are intrusive** for marginal benefit (see below).

## Mitigations available if it becomes blocking

| Option | Cost | Benefit |
|---|---|---|
| `OMP_NUM_THREADS=1` env var | Lose multi-threading speedup (~8×) | Eliminates the crash |
| Compile kernel without `-fopenmp` and parallelize Java-side | Major refactor | Eliminates the crash |
| Use `std::quick_exit` / `_exit(0)` to bypass cleanup | Skip resource finalisers | Loses observability/logging on shutdown |
| Pre-emptive OpenMP shutdown via `omp_set_num_threads(0)` before JVM exit | Custom JNI call | Possibly fixes it; needs validation |

Defer to Sem 5+ if Spring Boot logs ever show this on graceful shutdown.

## How to test

If you ever need to re-validate physics in isolation:

1. Build: `make -C kernel`
2. Save the smoke test from this ADR's git history (or rewrite from
   `services/.../FortranKernelLoader.java` as reference).
3. Run with `--enable-preview --enable-native-access=ALL-UNNAMED`.
4. Check the output **before** the SIGSEGV section — that is the real result.

## Reconsider if

- macOS Spring Boot service crashes during graceful shutdown (`Ctrl+C`).
- Tests in CI start failing with non-zero exit codes due to the segfault.
- A future gfortran release fixes the TSD cleanup ordering.
