package mx.astro.simulation.domain;

/**
 * State pattern: lifecycle of a single simulation run.
 *
 * Transitions:
 *   IDLE → RUNNING (on start)
 *   RUNNING → PAUSED (on pause)
 *   PAUSED → RUNNING (on resume)
 *   RUNNING → COMPLETED (on natural finish)
 *   RUNNING|PAUSED → STOPPED (on user stop)
 *   any → FAILED (on error)
 */
public enum SimulationLifecycle {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    STOPPED,
    FAILED;

    public boolean canTransitionTo(SimulationLifecycle target) {
        return switch (this) {
            case IDLE      -> target == RUNNING || target == FAILED;
            case RUNNING   -> target == PAUSED || target == COMPLETED || target == STOPPED || target == FAILED;
            case PAUSED    -> target == RUNNING || target == STOPPED || target == FAILED;
            case COMPLETED, STOPPED, FAILED -> false;
        };
    }
}
