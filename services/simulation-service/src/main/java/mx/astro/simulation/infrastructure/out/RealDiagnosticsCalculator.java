package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Diagnostics;
import mx.astro.simulation.domain.DiagnosticsCalculator;
import mx.astro.simulation.domain.Snapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Real-physics diagnostics computed from snapshot positions and velocities.
 *
 * <ul>
 *   <li><strong>K</strong> = ½ Σ mᵢ vᵢ² — direct from velocities, O(N).</li>
 *   <li><strong>U</strong> = -G Σᵢ Σⱼ&gt;ᵢ mᵢmⱼ / √(rᵢⱼ² + ε²) — softened to
 *       match the integrator's force law, O(N²). At N=3000 this is ~4.5 M
 *       operations per snapshot; at 60 Hz that's ~270 M ops/sec — single-threaded
 *       Java handles this comfortably. We can move to Fortran-side computation
 *       in V2 if profiling shows it as a bottleneck.</li>
 *   <li><strong>P</strong> = Σ mᵢ vᵢ — must remain ~zero (recentred system).</li>
 *   <li><strong>L</strong> = Σ mᵢ rᵢ × vᵢ — Newtonian conservation: dL/dt = 0.</li>
 *   <li><strong>Q</strong> = 2K / |U| — virial ratio (~1 for relaxed bound system).</li>
 * </ul>
 *
 * <p>Active outside the {@code mock} profile.
 */
@Component
@Profile("!mock")
public class RealDiagnosticsCalculator implements DiagnosticsCalculator {

    /** Same softening as the integrator — keeps diagnostics consistent with dynamics. */
    private final double softening;

    /** Gravitational constant in Hénon units. */
    private static final double G = 1.0;

    public RealDiagnosticsCalculator(
            @Value("${astro.simulation.default-softening:0.01}") double softening) {
        this.softening = softening;
    }

    @Override
    public Diagnostics compute(Snapshot s) {
        final int n = s.n();
        final double[] x = s.x();
        final double[] y = s.y();
        final double[] z = s.z();
        final double[] vx = s.vx();
        final double[] vy = s.vy();
        final double[] vz = s.vz();
        final double[] m = s.mass();
        final double eps2 = softening * softening;

        // ---- Kinetic energy + linear / angular momentum (O(N)) ----
        double K = 0.0;
        double Px = 0.0, Py = 0.0, Pz = 0.0;
        double Lx = 0.0, Ly = 0.0, Lz = 0.0;
        for (int i = 0; i < n; i++) {
            double vix = vx[i], viy = vy[i], viz = vz[i];
            double v2 = vix*vix + viy*viy + viz*viz;
            K  += 0.5 * m[i] * v2;
            Px += m[i] * vix;     Py += m[i] * viy;     Pz += m[i] * viz;
            Lx += m[i] * (y[i]*viz - z[i]*viy);
            Ly += m[i] * (z[i]*vix - x[i]*viz);
            Lz += m[i] * (x[i]*viy - y[i]*vix);
        }

        // ---- Potential energy (O(N²), upper-triangular sum) ----
        double U = 0.0;
        for (int i = 0; i < n; i++) {
            double xi = x[i], yi = y[i], zi = z[i], mi = m[i];
            for (int j = i + 1; j < n; j++) {
                double dx = xi - x[j];
                double dy = yi - y[j];
                double dz = zi - z[j];
                double r = Math.sqrt(dx*dx + dy*dy + dz*dz + eps2);
                U -= G * mi * m[j] / r;
            }
        }

        double E = K + U;
        double virial = (U != 0.0) ? -2.0 * K / U : Double.NaN;

        // ---- Lagrangian radii (10, 50, 90% of mass enclosed) ----
        double[] lag = lagrangianRadii(x, y, z, m, n);

        return new Diagnostics(
                s.time(),
                s.stepIndex(),
                K, U, E,
                new double[]{Px, Py, Pz},
                new double[]{Lx, Ly, Lz},
                virial,
                lag[0], lag[1], lag[2]
        );
    }

    /**
     * Compute r10, r50 (half-mass radius), r90 by sorting bodies by distance
     * from the centre of mass and walking the cumulative-mass curve.
     *
     * <p>Cost: O(N log N) for the sort. For N=3000 it's ~30 µs — negligible
     * vs the O(N²) potential sum we already do.
     */
    private static double[] lagrangianRadii(double[] x, double[] y, double[] z, double[] m, int n) {
        double cx = 0, cy = 0, cz = 0, totalMass = 0;
        for (int i = 0; i < n; i++) {
            cx += m[i] * x[i]; cy += m[i] * y[i]; cz += m[i] * z[i];
            totalMass += m[i];
        }
        if (totalMass <= 0) return new double[]{0, 0, 0};
        cx /= totalMass; cy /= totalMass; cz /= totalMass;

        double[][] pairs = new double[n][2];   // (radius, mass)
        for (int i = 0; i < n; i++) {
            double dx = x[i] - cx, dy = y[i] - cy, dz = z[i] - cz;
            pairs[i][0] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            pairs[i][1] = m[i];
        }
        java.util.Arrays.sort(pairs, (a, b) -> Double.compare(a[0], b[0]));

        double t10 = 0.10 * totalMass, t50 = 0.50 * totalMass, t90 = 0.90 * totalMass;
        double r10 = 0, r50 = 0, r90 = 0;
        double cum = 0;
        boolean got10 = false, got50 = false;
        for (int i = 0; i < n; i++) {
            cum += pairs[i][1];
            if (!got10 && cum >= t10) { r10 = pairs[i][0]; got10 = true; }
            if (!got50 && cum >= t50) { r50 = pairs[i][0]; got50 = true; }
            if (cum >= t90) { r90 = pairs[i][0]; break; }
        }
        return new double[]{r10, r50, r90};
    }

    @Override
    public String name() {
        return "real_brute_force_o2";
    }
}
