package mx.astro.simulation.domain;

/**
 * A point-in-time state of the simulation. SoA layout for efficient
 * transfer to/from the Fortran kernel and over the wire.
 */
public record Snapshot(
        long stepIndex,
        double time,
        double[] x, double[] y, double[] z,
        double[] vx, double[] vy, double[] vz,
        double[] mass
) {
    public int n() {
        return x.length;
    }
}
