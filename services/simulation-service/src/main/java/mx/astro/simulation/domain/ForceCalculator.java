package mx.astro.simulation.domain;

/**
 * Strategy port for gravitational acceleration calculation.
 *
 * Implementations:
 *   - BruteForceCalculator (O(N²)), V1
 *   - BarnesHutCalculator (O(N log N)), V2
 */
public interface ForceCalculator {

    /**
     * Computes the gravitational acceleration on each body.
     *
     * @param state current snapshot
     * @param softening Plummer softening length
     * @return arrays {@code [ax, ay, az]} of length N
     */
    double[][] computeAcceleration(Snapshot state, double softening);

    String name();
}
