package mx.astro.simulation.domain;

/**
 * Strategy port: compute physical diagnostics from a snapshot.
 *
 * Implementations:
 *   - MockDiagnosticsCalculator (V1) — fabricated stable values, used while
 *     the mock kernel does not produce physical motion.
 *   - RealDiagnosticsCalculator (V3) — actual O(N²) energy + momentum sums
 *     over real positions and velocities from the Fortran kernel.
 */
public interface DiagnosticsCalculator {

    Diagnostics compute(Snapshot snapshot);

    String name();
}
