package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Diagnostics;
import mx.astro.simulation.domain.DiagnosticsCalculator;
import mx.astro.simulation.domain.Snapshot;

/**
 * V1 mock diagnostics: fabricated values with tiny sinusoidal oscillation
 * so the validation plot is not flat. NOT physically meaningful — the mock
 * {@code CircularOrbitIntegrator} has zero velocities and rigid rotation,
 * so a real K = ½ Σ mᵢ vᵢ² would be zero. We synthesize plausible numbers
 * to verify the diagnostics → plot pipeline end-to-end before the real
 * computation lands in Sem 3 (using actual velocities from the leapfrog
 * integrator and the Fortran-computed potential).
 *
 * <p>Baseline: K = 1.0, U = -2.0, E = -1.0, virial Q = 1.0
 * (mimics a relaxed bound system in Hénon units).
 */
public class MockDiagnosticsCalculator implements DiagnosticsCalculator {

    @Override
    public Diagnostics compute(Snapshot snapshot) {
        double t = snapshot.time();
        // Tiny oscillation so the plot has visible variation.
        double K = 1.0 + 0.001 * Math.sin(t * 0.5);
        double U = -2.0 + 0.001 * Math.cos(t * 0.7);
        double E = K + U;
        double virial = -2.0 * K / U;

        return new Diagnostics(
                t,
                snapshot.stepIndex(),
                K, U, E,
                new double[]{0.0, 0.0, 0.0},      // mock has no velocities
                new double[]{0.0, 0.0, 1.0},      // pretend angular L is unit z
                virial,
                0.3, 0.7, 1.4                     // mock Lagrangian radii (Plummer-like)
        );
    }

    @Override
    public String name() {
        return "mock_constants";
    }
}
