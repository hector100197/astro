package mx.astro.simulation.application;

import mx.astro.simulation.domain.InitialCondition;
import mx.astro.simulation.domain.Integrator;
import mx.astro.simulation.domain.Snapshot;
import mx.astro.simulation.infrastructure.out.ExplicitInitialCondition;
import mx.astro.simulation.infrastructure.out.ExportJobEntity;
import mx.astro.simulation.infrastructure.out.ExportJobJpaRepository;
import mx.astro.simulation.infrastructure.out.FortranHdf5RunWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application service for headless / batch jobs.
 *
 * <p>POST /api/jobs creates a row with status=queued, then we kick off a
 * background task on a single-threaded executor (one job at a time per JVM
 * because the multi-snapshot HDF5 writer holds module-level state in the
 * Fortran kernel — see {@link FortranHdf5RunWriter}).
 *
 * <p>Each job:
 * <ol>
 *   <li>Marks itself running, records started_at.</li>
 *   <li>Builds the initial condition for the requested scenario.</li>
 *   <li>Opens a multi-snapshot HDF5 file, named {@code {jobId}-multi.h5}.</li>
 *   <li>Loops {@code nSteps} integration steps; appends a snapshot every
 *       {@code snapshotEvery} steps.</li>
 *   <li>Closes the HDF5 file, marks completed, records hdf5_path + finished_at.</li>
 * </ol>
 *
 * <p>If a step or write throws, the job is marked failed with the message
 * (no half-written HDF5 — best-effort close on the way out).
 *
 * <p>Active outside the {@code mock} profile (mock has no Fortran kernel).
 */
@Service
@Profile("!mock")
public class ExportJobService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);

    private final ExportJobJpaRepository repo;
    private final InitialCondition defaultInitialCondition;
    private final Integrator integrator;
    private final ScenarioCatalogService scenarioCatalog;
    private final ObjectProvider<FortranHdf5RunWriter> writerProvider;
    private final ObjectProvider<ReportGenerationService> reportProvider;

    private final TransactionTemplate tx;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "export-job-worker");
        t.setDaemon(true);
        return t;
    });

    public ExportJobService(
            ExportJobJpaRepository repo,
            InitialCondition defaultInitialCondition,
            Integrator integrator,
            ScenarioCatalogService scenarioCatalog,
            ObjectProvider<FortranHdf5RunWriter> writerProvider,
            ObjectProvider<ReportGenerationService> reportProvider,
            PlatformTransactionManager txManager
    ) {
        this.repo = repo;
        this.defaultInitialCondition = defaultInitialCondition;
        this.integrator = integrator;
        this.scenarioCatalog = scenarioCatalog;
        this.writerProvider = writerProvider;
        this.reportProvider = reportProvider;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Submit a new headless job. Returns the persisted job entity (status=queued). */
    @Transactional
    public ExportJobEntity submit(String scenarioName, int nSteps, double dt,
                                  double softening, long seed, int snapshotEvery) {
        Map<String, Object> scenario = resolveScenario(scenarioName);
        InitialCondition ic = buildIC(scenarioName, scenario);
        int n = inferN(scenario, ic);

        UUID id = UUID.randomUUID();
        ExportJobEntity job = new ExportJobEntity(
                id, Instant.now(), "queued",
                scenarioName, n, nSteps, dt, softening, seed, snapshotEvery
        );
        repo.save(job);

        // Schedule the worker AFTER this transaction commits — otherwise the
        // worker thread races the commit and findById() returns empty.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { worker.submit(() -> runJob(id)); }
            });
        } else {
            worker.submit(() -> runJob(id));
        }

        log.info("Submitted job {} (scenario={}, N={}, steps={}, every={})",
                id, scenarioName, n, nSteps, snapshotEvery);
        return job;
    }

    public Optional<ExportJobEntity> get(UUID id) {
        return repo.findById(id);
    }

    public List<ExportJobEntity> list(int limit) {
        return repo.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100)))
        ).getContent();
    }

    // ---------- background execution ----------

    private void runJob(UUID id) {
        ExportJobEntity job = tx.execute(status -> repo.findById(id).orElse(null));
        if (job == null) { log.warn("Job {} disappeared before execution", id); return; }

        log.info("Running job {} (scenario={}, N={}, steps={})",
                id, job.getScenarioName(), job.getNBodies(), job.getNSteps());
        markStarted(id);

        FortranHdf5RunWriter writer = writerProvider.getIfAvailable();
        if (writer == null) {
            markFailed(id, "FortranHdf5RunWriter not available (wrong Spring profile?)");
            return;
        }

        Map<String, Object> scenario = resolveScenario(job.getScenarioName());
        InitialCondition ic = buildIC(job.getScenarioName(), scenario);

        Snapshot state = ic.generate(job.getNBodies(), job.getSeed());

        // Keep every persisted snapshot in memory so the post-processing
        // analyser can run without round-tripping through the HDF5 file
        // (which the kernel's libhdf5 holds in an unreadable state for the
        // entire JVM lifetime). For N=3000 with 100 snapshots this is ~7 MB —
        // negligible compared to the existing per-snapshot allocation.
        java.util.List<Snapshot> collected = new java.util.ArrayList<>();
        try (FortranHdf5RunWriter.RunHandle h = writer.openRun(id, job.getNBodies())) {
            // Step 0 = initial state.
            h.appendSnapshot(0, state);
            collected.add(state);
            int snapshotsWritten = 1;

            for (int s = 1; s <= job.getNSteps(); s++) {
                state = integrator.step(state, job.getDt(), job.getSoftening());

                if (s % job.getSnapshotEvery() == 0 || s == job.getNSteps()) {
                    h.appendSnapshot(snapshotsWritten, state);
                    collected.add(state);
                    snapshotsWritten++;
                    updateProgress(id, s);
                }
            }

            String hdf5Path = h.path().toString();
            markCompleted(id, hdf5Path);
            log.info("Job {} completed: wrote {} snapshots over {} steps",
                    id, snapshotsWritten, job.getNSteps());

            // Post-processing: build the analysis report from the in-memory
            // snapshot list. Best-effort — failure here doesn't roll back the
            // completion; the user gets an hdf5 download but no report.
            ReportGenerationService rs = reportProvider.getIfAvailable();
            if (rs != null) {
                try {
                    ExportJobEntity reloaded = tx.execute(st -> repo.findById(id).orElse(null));
                    if (reloaded != null) {
                        ReportGenerationService.ReportBundle bundle = rs.generate(reloaded, collected);
                        markReportReady(id, bundle.reportDir().toString(),
                                bundle.validationJson(), bundle.validationVerdict());
                    }
                } catch (Throwable rt) {
                    log.warn("Job {} completed but report generation failed: {}",
                            id, rt.getMessage(), rt);
                }
            }
        } catch (Throwable t) {
            log.error("Job {} failed", id, t);
            markFailed(id, t.getMessage());
        }
    }

    private void markReportReady(UUID id, String reportDir, String validationJson, String verdict) {
        tx.executeWithoutResult(status -> repo.findById(id).ifPresent(j -> {
            j.setReportDir(reportDir);
            j.setValidationJson(validationJson);
            j.setValidationVerdict(verdict);
            repo.save(j);
        }));
    }

    // ---------- transactional state updates ----------
    // These run via TransactionTemplate (NOT @Transactional) because they're
    // invoked from a worker thread via this.method() — Spring's annotation-based
    // proxies don't intercept self-invocation, so @Transactional would silently
    // skip the wrap. TransactionTemplate is explicit and always works.

    private void markStarted(UUID id) {
        tx.executeWithoutResult(status -> repo.findById(id).ifPresent(j -> {
            j.setStatus("running");
            j.setStartedAt(Instant.now());
            repo.save(j);
        }));
    }

    private void markCompleted(UUID id, String hdf5Path) {
        tx.executeWithoutResult(status -> repo.findById(id).ifPresent(j -> {
            j.setStatus("completed");
            j.setFinishedAt(Instant.now());
            j.setHdf5Path(hdf5Path);
            repo.save(j);
        }));
    }

    private void markFailed(UUID id, String message) {
        tx.executeWithoutResult(status -> repo.findById(id).ifPresent(j -> {
            j.setStatus("failed");
            j.setFinishedAt(Instant.now());
            j.setErrorMessage(message);
            repo.save(j);
        }));
    }

    private void updateProgress(UUID id, int step) {
        tx.executeWithoutResult(status -> repo.findById(id).ifPresent(j -> {
            j.setProgressSteps(step);
            repo.save(j);
        }));
    }

    // ---------- scenario resolution helpers ----------

    private Map<String, Object> resolveScenario(String name) {
        if (name == null || name.isBlank() || "default_plummer".equals(name)) {
            return Map.of("initial_condition", Map.of("type", "plummer"));
        }
        return scenarioCatalog.get(name).orElseThrow(
                () -> new IllegalArgumentException("Unknown scenario: " + name));
    }

    @SuppressWarnings("unchecked")
    private InitialCondition buildIC(String scenarioName, Map<String, Object> scenario) {
        Map<String, Object> ic = (Map<String, Object>) scenario.get("initial_condition");
        if (ic == null) return defaultInitialCondition;
        String type = String.valueOf(ic.get("type"));
        return switch (type) {
            case "plummer" -> defaultInitialCondition;
            case "explicit" -> new ExplicitInitialCondition(
                    scenarioName, (List<Map<String, Object>>) ic.get("bodies"));
            default -> throw new IllegalArgumentException(
                    "Unsupported initial_condition.type for headless: " + type);
        };
    }

    private int inferN(Map<String, Object> scenario, InitialCondition ic) {
        if (ic instanceof ExplicitInitialCondition expl) return expl.bodyCount();
        Object n = scenario.get("n_bodies");
        return n instanceof Number num ? num.intValue() : 1500;
    }
}
