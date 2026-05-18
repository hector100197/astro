package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.InitialCondition;
import mx.astro.simulation.domain.Snapshot;

import java.util.List;
import java.util.Map;

/**
 * Initial condition that takes positions, velocities, and masses from a
 * pre-defined list (e.g. from a scenario YAML's {@code initial_condition.bodies}
 * block — the three-body figure-8, the solar system, Pythagorean problem, …).
 *
 * <p>Stateful per-instance: the body list is captured at construction time.
 * The {@link InitialCondition#generate} method ignores its {@code n} and
 * {@code seed} arguments and simply returns the captured state.
 */
public class ExplicitInitialCondition implements InitialCondition {

    private final String name;
    private final double[] x, y, z, vx, vy, vz, mass;

    public ExplicitInitialCondition(String name, List<Map<String, Object>> bodies) {
        this.name = name;
        int n = bodies.size();
        x  = new double[n]; y  = new double[n]; z  = new double[n];
        vx = new double[n]; vy = new double[n]; vz = new double[n];
        mass = new double[n];

        for (int i = 0; i < n; i++) {
            Map<String, Object> b = bodies.get(i);
            x[i]  = num(b, "x");
            y[i]  = num(b, "y");
            z[i]  = num(b, "z");
            vx[i] = num(b, "vx");
            vy[i] = num(b, "vy");
            vz[i] = num(b, "vz");
            mass[i] = num(b, "mass");
        }
    }

    @Override
    public Snapshot generate(int n, long seed) {
        // n and seed are intentionally ignored — explicit ICs use the captured state.
        return new Snapshot(0L, 0.0,
                x.clone(), y.clone(), z.clone(),
                vx.clone(), vy.clone(), vz.clone(),
                mass.clone());
    }

    @Override
    public String name() { return "explicit:" + name; }

    /** Number of bodies as encoded in the YAML. */
    public int bodyCount() { return x.length; }

    private static double num(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) return 0.0;
        if (v instanceof Number num) return num.doubleValue();
        return Double.parseDouble(v.toString());
    }
}
