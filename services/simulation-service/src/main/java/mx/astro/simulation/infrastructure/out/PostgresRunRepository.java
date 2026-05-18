package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Diagnostics;
import mx.astro.simulation.domain.Manifest;
import mx.astro.simulation.domain.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Postgres-backed implementation of {@link RunRepository}, active in the
 * default profile (when DB autoconfig is not excluded).
 *
 * <p>Manifest is serialized to JSONB via Hibernate's {@code SqlTypes.JSON}
 * mapping on the entity. Validation metrics are inserted one at a time;
 * Spring Data batches under the hood when {@code spring.jpa.properties
 * .hibernate.jdbc.batch_size} is set.
 */
@Component
@Profile({"!mock & !dev"})
public class PostgresRunRepository implements RunRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresRunRepository.class);

    private final SimulationRunJpaRepository runs;
    private final ValidationMetricJpaRepository metrics;

    public PostgresRunRepository(SimulationRunJpaRepository runs,
                                 ValidationMetricJpaRepository metrics) {
        this.runs = runs;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public UUID startRun(Manifest manifest, RunMode mode) {
        SimulationRunEntity entity = new SimulationRunEntity(
                manifest.runId(),
                manifest.createdAt(),
                "running",
                mode.name().toLowerCase(),
                flatManifest(manifest)
        );
        runs.save(entity);
        log.info("Persisted run start: {} (mode={})", manifest.runId(), mode);
        return manifest.runId();
    }

    @Override
    @Transactional
    public void markCompleted(UUID runId, Instant finishedAt) {
        runs.findById(runId).ifPresent(e -> {
            e.setStatus("completed");
            e.setFinishedAt(finishedAt);
            runs.save(e);
        });
    }

    @Override
    @Transactional
    public void markStopped(UUID runId, Instant finishedAt) {
        runs.findById(runId).ifPresent(e -> {
            e.setStatus("stopped");
            e.setFinishedAt(finishedAt);
            runs.save(e);
        });
    }

    @Override
    @Transactional
    public void markFailed(UUID runId, Instant finishedAt, String errorMessage) {
        runs.findById(runId).ifPresent(e -> {
            e.setStatus("failed");
            e.setFinishedAt(finishedAt);
            e.setErrorMessage(errorMessage);
            runs.save(e);
        });
    }

    @Override
    @Transactional
    public void recordHdf5Path(UUID runId, String hdf5Path) {
        runs.findById(runId).ifPresent(e -> {
            e.setHdf5Path(hdf5Path);
            runs.save(e);
        });
    }

    @Override
    @Transactional
    public void recordMetrics(UUID runId, Diagnostics d) {
        metrics.save(new ValidationMetricEntity(
                runId,
                d.stepIndex(),
                d.simTime(),
                d.kineticEnergy(), d.potentialEnergy(), d.totalEnergy(),
                d.momentum()[0], d.momentum()[1], d.momentum()[2],
                d.angularL()[0], d.angularL()[1], d.angularL()[2],
                Double.isNaN(d.virialRatio()) ? null : d.virialRatio()
        ));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<RunSummary> findRecent(int limit) {
        var page = org.springframework.data.domain.PageRequest.of(
                0, Math.max(1, limit),
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        return runs.findAll(page).getContent().stream().map(this::toSummary).toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.Optional<RunSummary> findById(UUID runId) {
        return runs.findById(runId).map(this::toSummary);
    }

    private RunSummary toSummary(SimulationRunEntity e) {
        return new RunSummary(
                e.getId(), e.getCreatedAt(), e.getFinishedAt(),
                e.getStatus(), e.getMode(), e.getScenarioName(),
                e.getHdf5Path(), e.getManifest()
        );
    }

    /**
     * Flatten the structured manifest into a single map for JSONB storage.
     * Keys are namespaced ({@code kernel}, {@code parameters}, …) so the
     * shape mirrors the design in {@code docs/reproducibility.md}.
     */
    private Map<String, Object> flatManifest(Manifest m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", m.runId().toString());
        out.put("created_at", m.createdAt().toString());
        out.put("kernel", m.kernel());
        out.put("scenario", m.scenario());
        out.put("parameters", m.parameters());
        out.put("hardware", m.hardware());
        out.put("software", m.software());
        return out;
    }
}
