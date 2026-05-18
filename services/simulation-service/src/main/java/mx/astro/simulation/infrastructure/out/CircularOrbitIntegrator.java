package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Integrator;
import mx.astro.simulation.domain.Snapshot;

/**
 * V1 mock integrator: rotates every body around the z-axis at constant angular
 * velocity ω = 1 rad / Hénon-time-unit. Ignores gravity entirely. Produces a
 * visually steady spinning disk pattern useful for verifying the streaming
 * pipeline end-to-end before the real leapfrog (Sem 3) lands.
 *
 * <p>Step formula:
 * <pre>
 *   x' =  x cos(ω·dt) - y sin(ω·dt)
 *   y' =  x sin(ω·dt) + y cos(ω·dt)
 *   z' =  z
 * </pre>
 *
 * <p>Energy and angular-momentum conservation tests will fail by design on this
 * integrator — that is expected. The point of Sem 1 is to verify the pipeline,
 * not the physics.
 */
public class CircularOrbitIntegrator implements Integrator {

    private static final double OMEGA = 1.0;

    @Override
    public Snapshot step(Snapshot current, double dt, double softening) {
        final int n = current.n();
        final double angle = OMEGA * dt;
        final double cos = Math.cos(angle);
        final double sin = Math.sin(angle);

        double[] xOld = current.x(), yOld = current.y(), zOld = current.z();
        double[] xNew = new double[n];
        double[] yNew = new double[n];
        double[] zNew = new double[n];

        for (int i = 0; i < n; i++) {
            xNew[i] = xOld[i] * cos - yOld[i] * sin;
            yNew[i] = xOld[i] * sin + yOld[i] * cos;
            zNew[i] = zOld[i];
        }

        return new Snapshot(
                current.stepIndex() + 1,
                current.time() + dt,
                xNew, yNew, zNew,
                current.vx(), current.vy(), current.vz(),
                current.mass()
        );
    }

    @Override
    public String name() {
        return "circular_orbit_mock";
    }
}
