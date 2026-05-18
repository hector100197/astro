package mx.astro.simulation.domain;

/**
 * Strategy port for generating initial conditions.
 *
 * Implementations:
 *   - PlummerInitialCondition (V1)
 *   - KingInitialCondition (V2)
 *   - HernquistInitialCondition (V2)
 *   - GaiaImportedInitialCondition (V2 — from gaia/ module)
 *   - CustomCsvInitialCondition (V1)
 */
public interface InitialCondition {

    /**
     * Generates the initial state.
     *
     * @param n number of bodies
     * @param seed RNG seed for reproducibility
     */
    Snapshot generate(int n, long seed);

    String name();
}
