package mx.astro.simulation.domain;

/**
 * Strategy port for time integration.
 *
 * Implementations:
 *   - LeapfrogIntegrator (V1)
 *   - Hermite4Integrator (V2)
 *
 * Pure domain interface — no infrastructure imports.
 */
public interface Integrator {

    /**
     * Advances {@code current} by one timestep, returning the new state.
     *
     * @param current snapshot at time t
     * @param dt timestep size in Hénon units
     * @param softening Plummer softening length
     * @return snapshot at time t + dt
     */
    Snapshot step(Snapshot current, double dt, double softening);

    /** Identifier persisted in the reproducibility manifest. */
    String name();
}
