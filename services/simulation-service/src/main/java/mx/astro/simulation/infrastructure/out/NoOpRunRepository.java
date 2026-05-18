package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Diagnostics;
import mx.astro.simulation.domain.Manifest;
import mx.astro.simulation.domain.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Null-object implementation of {@link RunRepository} for profiles where
 * Postgres is not available ({@code mock}, {@code dev}). The application
 * service can call lifecycle / metrics methods unconditionally; this
 * adapter logs at TRACE and returns the manifest's run id.
 *
 * <p>Why a null object instead of {@code Optional<RunRepository>}? It keeps
 * the call sites readable and tracks the same domain port — swapping
 * profiles changes the wiring, not the application code.
 */
@Component
@Profile({"mock | dev"})
public class NoOpRunRepository implements RunRepository {

    private static final Logger log = LoggerFactory.getLogger(NoOpRunRepository.class);

    @Override
    public UUID startRun(Manifest manifest, RunMode mode) {
        log.info("[NoOp] would persist run start: {} (mode={})", manifest.runId(), mode);
        return manifest.runId();
    }

    @Override
    public void markCompleted(UUID runId, Instant finishedAt) {
        log.trace("[NoOp] markCompleted {}", runId);
    }

    @Override
    public void markStopped(UUID runId, Instant finishedAt) {
        log.trace("[NoOp] markStopped {}", runId);
    }

    @Override
    public void markFailed(UUID runId, Instant finishedAt, String errorMessage) {
        log.warn("[NoOp] markFailed {} — {}", runId, errorMessage);
    }

    @Override
    public void recordHdf5Path(UUID runId, String hdf5Path) {
        log.info("[NoOp] would set hdf5_path={} for {}", hdf5Path, runId);
    }

    @Override
    public void recordMetrics(UUID runId, Diagnostics diagnostics) {
        log.trace("[NoOp] recordMetrics {} step={}", runId, diagnostics.stepIndex());
    }

    @Override
    public java.util.List<RunSummary> findRecent(int limit) {
        return java.util.List.of();
    }

    @Override
    public java.util.Optional<RunSummary> findById(UUID runId) {
        return java.util.Optional.empty();
    }
}
