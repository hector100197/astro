package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.InitialCondition;
import mx.astro.simulation.domain.Snapshot;

import java.util.SplittableRandom;

/**
 * V1 mock initial condition: uniformly samples points in a thin disk of radius 1
 * in the xy-plane, with a small thickness in z. Velocities and masses are
 * placeholder — used only to verify the pipeline before the real Plummer/Fortran
 * implementation lands in Sem 3.
 *
 * <p>NOT a physically meaningful initial condition. Use {@code PlummerInitialCondition}
 * for science.
 */
public class UniformDiskInitialCondition implements InitialCondition {

    private static final double RADIUS = 1.0;
    private static final double THICKNESS = 0.05;

    @Override
    public Snapshot generate(int n, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);

        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        double[] vx = new double[n];
        double[] vy = new double[n];
        double[] vz = new double[n];
        double[] mass = new double[n];

        double bodyMass = 1.0 / n;

        for (int i = 0; i < n; i++) {
            // Uniform in disk: r = R · sqrt(u), θ = 2π · v
            double u = rng.nextDouble();
            double v = rng.nextDouble();
            double r = RADIUS * Math.sqrt(u);
            double theta = 2.0 * Math.PI * v;

            x[i] = r * Math.cos(theta);
            y[i] = r * Math.sin(theta);
            z[i] = (rng.nextDouble() - 0.5) * THICKNESS;
            mass[i] = bodyMass;
            // velocities left at zero — the mock integrator imposes circular motion
        }

        return new Snapshot(0L, 0.0, x, y, z, vx, vy, vz, mass);
    }

    @Override
    public String name() {
        return "uniform_disk_mock";
    }
}
