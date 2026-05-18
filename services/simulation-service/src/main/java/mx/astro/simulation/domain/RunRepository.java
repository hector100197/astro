package mx.astro.simulation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain port for persisting simulation run lifecycle and per-snapshot diagnostics.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code PostgresRunRepository} — JPA-backed, active in default profile.</li>
 *   <li>{@code NoOpRunRepository} — silently discards everything; active in
 *       {@code mock} and {@code dev} profiles where no DB is available.</li>
 * </ul>
 *
 * <p>The application service calls these methods unconditionally; profile
 * selection determines whether bytes hit disk or get dropped on the floor.
 */
public interface RunRepository {

    /**
     * Records the start of a run with its full reproducibility manifest.
     * Returns the run id (which the application echoes from the manifest's
     * {@link Manifest#runId()} so the caller stays canonical).
     */
    UUID startRun(Manifest manifest, RunMode mode);

    /** Records a successful completion. */
    void markCompleted(UUID runId, Instant finishedAt);

    /** Records that the user stopped the run early. */
    void markStopped(UUID runId, Instant finishedAt);

    /** Records a failure with an error message for postmortem. */
    void markFailed(UUID runId, Instant finishedAt, String errorMessage);

    /** Records the HDF5 output path produced for the run, if any. */
    void recordHdf5Path(UUID runId, String hdf5Path);

    /**
     * Persists one snapshot of physics diagnostics. May be invoked at a
     * decimated rate vs the streaming rate (e.g. 5 Hz of persistence on a
     * 60 Hz stream) — the application controls the cadence.
     */
    void recordMetrics(UUID runId, Diagnostics diagnostics);

    /**
     * Browse recent runs, newest first. Returns an empty list if no DB is
     * configured (mock / dev profiles).
     */
    java.util.List<RunSummary> findRecent(int limit);

    /** Look up a single run by id. */
    java.util.Optional<RunSummary> findById(UUID runId);

    enum RunMode { LIVE, HEADLESS }

    /**
     * Lightweight summary of a persisted run. Used by the {@code /api/runs}
     * REST endpoint that powers the UI history panel.
     */
    record RunSummary(
            UUID id,
            Instant createdAt,
            Instant finishedAt,
            String status,
            String mode,
            String scenarioName,
            String hdf5Path,
            java.util.Map<String, Object> manifest
    ) {}
}
