package mx.astro.simulation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import mx.astro.contracts.DiagnosticsDto;
import mx.astro.simulation.domain.Diagnostics;
import mx.astro.simulation.domain.DiagnosticsCalculator;
import mx.astro.simulation.domain.InitialCondition;
import mx.astro.simulation.domain.Integrator;
import mx.astro.simulation.domain.Manifest;
import mx.astro.simulation.domain.RunRepository;
import mx.astro.simulation.domain.Snapshot;
import mx.astro.simulation.infrastructure.out.ExplicitInitialCondition;
import mx.astro.simulation.infrastructure.out.ExportJobJpaRepository;
import mx.astro.simulation.infrastructure.out.FortranHdf5Writer;
import mx.astro.simulation.infrastructure.out.Hdf5Reader;
import mx.astro.simulation.infrastructure.out.SnapshotBinaryCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Application use case: drive a live simulation stream for a single client.
 *
 * <p>Each connection gets its own {@link StreamSession} carrying mutable
 * state (current snapshot, paused flag, current Δt, run id). On stream
 * start we build a {@link Manifest} and persist a row in
 * {@code simulation_runs}; on stream stop we mark the row completed.
 * Diagnostics are persisted at a configurable decimated rate (default 5 Hz)
 * to keep DB write rate bounded.
 */
@Service
public class LiveStreamingService {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamingService.class);

    private final InitialCondition initialCondition;
    private final Integrator integrator;
    private final DiagnosticsCalculator diagnosticsCalculator;
    private final RunRepository runRepository;
    private final ManifestBuilder manifestBuilder;
    private final ScenarioCatalogService scenarioCatalog;
    private final ObjectMapper objectMapper;
    /**
     * Optional HDF5 writer — present in default profile (real kernel),
     * absent in {@code mock}. Used to dump final snapshot on stream stop.
     */
    private final ObjectProvider<FortranHdf5Writer> hdf5WriterProvider;
    /** Optional reader for replay mode — present only in real-kernel profile. */
    private final ObjectProvider<Hdf5Reader> hdf5ReaderProvider;
    /** Lookup for batch jobs to find HDF5 paths for replay. */
    private final ObjectProvider<ExportJobJpaRepository> jobsProvider;

    private final int defaultN;
    private final long defaultSeed;
    private final double defaultDt;
    private final double defaultSoftening;
    private final long snapshotIntervalNanos;
    private final int metricsPersistEveryNthTick;

    private final Map<String, StreamSession> activeStreams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    public LiveStreamingService(
            InitialCondition initialCondition,
            Integrator integrator,
            DiagnosticsCalculator diagnosticsCalculator,
            RunRepository runRepository,
            ManifestBuilder manifestBuilder,
            ScenarioCatalogService scenarioCatalog,
            ObjectMapper objectMapper,
            ObjectProvider<FortranHdf5Writer> hdf5WriterProvider,
            ObjectProvider<Hdf5Reader> hdf5ReaderProvider,
            ObjectProvider<ExportJobJpaRepository> jobsProvider,
            @Value("${astro.simulation.default-n:1500}") int defaultN,
            @Value("${astro.simulation.default-seed:42}") long defaultSeed,
            @Value("${astro.simulation.default-dt:0.005}") double defaultDt,
            @Value("${astro.simulation.default-softening:0.01}") double defaultSoftening,
            @Value("${astro.simulation.snapshot-rate-hz:60}") int snapshotRateHz,
            @Value("${astro.persistence.metrics-rate-hz:5}") int metricsRateHz
    ) {
        this.initialCondition = initialCondition;
        this.integrator = integrator;
        this.diagnosticsCalculator = diagnosticsCalculator;
        this.runRepository = runRepository;
        this.manifestBuilder = manifestBuilder;
        this.scenarioCatalog = scenarioCatalog;
        this.objectMapper = objectMapper;
        this.hdf5WriterProvider = hdf5WriterProvider;
        this.hdf5ReaderProvider = hdf5ReaderProvider;
        this.jobsProvider = jobsProvider;
        this.defaultN = defaultN;
        this.defaultSeed = defaultSeed;
        this.defaultDt = defaultDt;
        this.defaultSoftening = defaultSoftening;
        this.snapshotIntervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, snapshotRateHz);
        this.metricsPersistEveryNthTick = Math.max(1, snapshotRateHz / Math.max(1, metricsRateHz));
    }

    /**
     * Starts a streaming session.
     *
     * @param binaryFrameSink receives each encoded snapshot frame.
     * @param textSink receives diagnostic JSON messages on the WS text channel.
     */
    public String startStream(Consumer<ByteBuffer> binaryFrameSink, Consumer<String> textSink) {
        String streamId = UUID.randomUUID().toString();
        UUID runId = UUID.randomUUID();

        Manifest manifest = manifestBuilder.build(
                runId,
                defaultN, defaultDt, defaultSoftening, defaultSeed,
                integrator, initialCondition, diagnosticsCalculator,
                "default_plummer"
        );
        runRepository.startRun(manifest, RunRepository.RunMode.LIVE);

        Snapshot initial = initialCondition.generate(defaultN, defaultSeed);
        StreamSession session = new StreamSession(
                runId,
                new AtomicReference<>(initial),
                new AtomicBoolean(false),
                new AtomicReference<>(defaultDt),
                defaultSoftening,
                new AtomicLong(0L),
                "default_plummer"
        );

        log.info("Starting stream {} (run={}, N={}, dt={}, integrator={}, IC={}, diagnostics={})",
                streamId, runId, defaultN, defaultDt,
                integrator.name(), initialCondition.name(), diagnosticsCalculator.name());

        ScheduledFuture<?> handle = scheduler.scheduleWithFixedDelay(
                () -> tick(session, binaryFrameSink, textSink),
                0L,
                snapshotIntervalNanos,
                TimeUnit.NANOSECONDS
        );
        session.setHandle(handle);
        session.setTextSink(textSink);

        activeStreams.put(streamId, session);
        return streamId;
    }

    public void stopStream(String streamId) {
        StreamSession session = activeStreams.remove(streamId);
        if (session == null) return;
        session.cancel();
        finalizeRun(session.runId(), session.state().get());
        log.info("Stopped stream {} (run={})", streamId, session.runId());
    }

    public void pauseStream(String streamId) {
        StreamSession session = activeStreams.get(streamId);
        if (session != null) {
            session.paused().set(true);
            log.info("Paused stream {}", streamId);
        }
    }

    public void resumeStream(String streamId) {
        StreamSession session = activeStreams.get(streamId);
        if (session != null) {
            session.paused().set(false);
            log.info("Resumed stream {}", streamId);
        }
    }

    public void setDt(String streamId, double dt) {
        if (dt <= 0 || !Double.isFinite(dt)) {
            log.warn("Rejected invalid dt {} on stream {}", dt, streamId);
            return;
        }
        StreamSession session = activeStreams.get(streamId);
        if (session != null) {
            session.currentDt().set(dt);
            log.info("Set dt={} on stream {}", dt, streamId);
        }
    }

    public void setSoftening(String streamId, double eps) {
        if (eps < 0 || !Double.isFinite(eps)) {
            log.warn("Rejected invalid softening {} on stream {}", eps, streamId);
            return;
        }
        StreamSession session = activeStreams.get(streamId);
        if (session != null) {
            session.currentSoftening().set(eps);
            log.info("Set softening={} on stream {}", eps, streamId);
        }
    }

    /**
     * Resize the stream to a new N. Closes the current run cleanly (HDF5 +
     * DB) and opens a fresh one with the same scenario but a different
     * particle count. Only meaningful for procedural ICs (Plummer); for
     * explicit scenarios N is fixed by the body list.
     */
    public void setN(String streamId, int newN) {
        if (newN < 2 || newN > 100_000) {
            log.warn("Rejected N={} on stream {} (must be 2..100000)", newN, streamId);
            return;
        }
        StreamSession session = activeStreams.get(streamId);
        if (session == null) return;

        // For explicit scenarios, refuse to change N (it's tied to the body list).
        String scenarioName = session.scenarioName();
        if (scenarioName != null && scenarioName.startsWith("explicit:")) {
            log.warn("Cannot change N on explicit-IC stream {} (scenario {})", streamId, scenarioName);
            return;
        }

        // Close the old run (HDF5 + DB), open a new one with the new N.
        finalizeRun(session.runId(), session.state().get());

        UUID newRunId = UUID.randomUUID();
        Manifest manifest = manifestBuilder.build(
                newRunId,
                newN, session.currentDt().get(), session.currentSoftening().get(), defaultSeed,
                integrator, initialCondition, diagnosticsCalculator,
                scenarioName != null ? scenarioName : "default_plummer"
        );
        runRepository.startRun(manifest, RunRepository.RunMode.LIVE);

        Snapshot fresh = initialCondition.generate(newN, defaultSeed);
        session.setRunId(newRunId);
        session.state().set(fresh);
        session.tickCounter().set(0L);
        session.paused().set(false);

        log.info("Resized stream {} to N={} (run={})", streamId, newN, newRunId);
    }

    public void resetStream(String streamId) {
        StreamSession session = activeStreams.get(streamId);
        if (session == null) return;
        Snapshot fresh = initialCondition.generate(defaultN, defaultSeed);
        session.state().set(fresh);
        session.tickCounter().set(0L);
        log.info("Reset stream {}", streamId);
    }

    /**
     * Load a different scenario from the YAML catalog into an existing stream.
     * Closes the current run (writes its HDF5 + marks it stopped) and opens a
     * fresh run with the new scenario's initial condition. The WebSocket
     * connection stays open — the client just sees a new cluster appear.
     */
    public void loadScenarioStream(String streamId, String scenarioName) {
        StreamSession session = activeStreams.get(streamId);
        if (session == null) return;

        Optional<Map<String, Object>> opt = scenarioCatalog.get(scenarioName);
        if (opt.isEmpty()) {
            log.warn("Scenario {} not found — ignoring loadScenario", scenarioName);
            return;
        }
        Map<String, Object> scenario = opt.get();

        InitialCondition newIC;
        int n;
        long seed;
        try {
            ScenarioParams params = parseScenario(scenarioName, scenario);
            newIC = params.ic();
            n = params.n();
            seed = params.seed();
        } catch (Exception e) {
            log.warn("Failed to instantiate scenario {}: {}", scenarioName, e.getMessage());
            return;
        }

        // 1. Close out the old run cleanly (HDF5 + DB)
        finalizeRun(session.runId(), session.state().get());

        // 2. Open a new run with the new manifest
        UUID newRunId = UUID.randomUUID();
        Manifest manifest = manifestBuilder.build(
                newRunId,
                n, session.currentDt().get(), defaultSoftening, seed,
                integrator, newIC, diagnosticsCalculator,
                scenarioName
        );
        runRepository.startRun(manifest, RunRepository.RunMode.LIVE);

        // 3. Swap state in the running session — scheduler keeps ticking with new IC
        Snapshot fresh = newIC.generate(n, seed);
        session.setRunId(newRunId);
        session.setScenarioName(scenarioName);
        session.state().set(fresh);
        session.tickCounter().set(0L);
        session.paused().set(false);
        session.replayBuffer().set(null);   // exit replay mode if we were in it
        session.replayCursor().set(0);

        log.info("Loaded scenario '{}' on stream {} (run={}, N={}, ic={})",
                scenarioName, streamId, newRunId, n, newIC.name());
    }

    /**
     * Switch a stream into REPLAY mode using the multi-snapshot HDF5 of a
     * completed batch job. Closes the current live run and starts emitting
     * the saved snapshots at the same scheduler rate. Loops at the end.
     *
     * <p>Only available in profiles where {@link Hdf5Reader} is wired
     * (default, not {@code mock}).
     */
    public void replayJobStream(String streamId, UUID jobId) {
        StreamSession session = activeStreams.get(streamId);
        if (session == null) return;

        Hdf5Reader reader = hdf5ReaderProvider.getIfAvailable();
        ExportJobJpaRepository jobs = jobsProvider.getIfAvailable();
        if (reader == null || jobs == null) {
            log.warn("Replay requested but HDF5 reader / jobs repository unavailable on stream {}", streamId);
            return;
        }

        var jobOpt = jobs.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Replay: job {} not found", jobId);
            return;
        }
        String hdf5Path = jobOpt.get().getHdf5Path();
        if (hdf5Path == null || hdf5Path.isBlank()) {
            log.warn("Replay: job {} has no hdf5_path (status={})", jobId, jobOpt.get().getStatus());
            return;
        }

        List<Snapshot> snapshots;
        try {
            snapshots = reader.readMultiSnapshots(java.nio.file.Path.of(hdf5Path));
        } catch (Exception e) {
            log.warn("Replay: failed to read HDF5 {}: {}", hdf5Path, e.getMessage());
            return;
        }
        if (snapshots.isEmpty()) {
            log.warn("Replay: HDF5 {} has zero snapshots", hdf5Path);
            return;
        }

        // Close out the current live run (write its final HDF5, mark stopped).
        finalizeRun(session.runId(), session.state().get());

        // Open a new run row tagged as a replay so it's distinguishable in history.
        UUID newRunId = UUID.randomUUID();
        String tag = "replay:" + jobId;
        Manifest manifest = manifestBuilder.build(
                newRunId,
                snapshots.get(0).n(), session.currentDt().get(), session.currentSoftening().get(), defaultSeed,
                integrator, initialCondition, diagnosticsCalculator,
                tag
        );
        runRepository.startRun(manifest, RunRepository.RunMode.LIVE);

        session.setRunId(newRunId);
        session.setScenarioName(tag);
        session.replayBuffer().set(snapshots);
        session.replayCursor().set(0);
        session.state().set(snapshots.get(0));
        session.tickCounter().set(0L);
        session.paused().set(false);

        // Notify the client of replay state so it can show a time scrubber.
        Consumer<String> sink = session.textSink();
        if (sink != null) {
            sink.accept(String.format(
                    "{\"type\":\"replayInfo\",\"jobId\":\"%s\",\"totalFrames\":%d}",
                    jobId, snapshots.size()));
        }

        log.info("Replay started on stream {}: {} snapshots from job {} (run={})",
                streamId, snapshots.size(), jobId, newRunId);
    }

    /**
     * Seek the replay cursor to a specific frame index. No-op if the stream
     * is not currently in replay mode.
     */
    public void seekReplay(String streamId, int frameIndex) {
        StreamSession session = activeStreams.get(streamId);
        if (session == null) return;
        List<Snapshot> buffer = session.replayBuffer().get();
        if (buffer == null) {
            log.warn("seekReplay on non-replay stream {}", streamId);
            return;
        }
        int idx = Math.max(0, Math.min(frameIndex, buffer.size() - 1));
        session.replayCursor().set(idx);
        session.state().set(buffer.get(idx));
        log.info("Seek stream {} to replay frame {}/{}", streamId, idx, buffer.size());
    }

    /** Persist HDF5 (best-effort) and mark the run as stopped in the DB. */
    private void finalizeRun(UUID runId, Snapshot finalState) {
        FortranHdf5Writer writer = hdf5WriterProvider.getIfAvailable();
        if (writer != null) {
            try {
                Path hdf5 = writer.writeFinal(runId, finalState);
                if (hdf5 != null) {
                    runRepository.recordHdf5Path(runId, hdf5.toString());
                }
            } catch (Exception e) {
                log.warn("HDF5 write on finalize failed for run {}: {}", runId, e.getMessage());
            }
        }
        runRepository.markStopped(runId, Instant.now());
    }

    /**
     * Parses a scenario YAML map into an {@link InitialCondition} + N + seed.
     * Supports {@code initial_condition.type} = "plummer" (uses the default
     * Fortran-backed bean) or "explicit" (positions/velocities hardcoded).
     */
    @SuppressWarnings("unchecked")
    private ScenarioParams parseScenario(String scenarioName, Map<String, Object> scenario) {
        Map<String, Object> ic = (Map<String, Object>) scenario.get("initial_condition");
        if (ic == null) throw new IllegalArgumentException("Missing initial_condition block");
        String type = String.valueOf(ic.get("type"));
        int n = ((Number) scenario.getOrDefault("n_bodies", defaultN)).intValue();
        long seed = ((Number) ic.getOrDefault("rng_seed", defaultSeed)).longValue();

        InitialCondition newIC = switch (type) {
            case "plummer" -> initialCondition;
            case "explicit" -> new ExplicitInitialCondition(
                    scenarioName,
                    (List<Map<String, Object>>) ic.get("bodies")
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported initial_condition.type: " + type +
                    " (live UI supports 'plummer' and 'explicit'; others via CLI/Python)");
        };

        // For explicit scenarios, n is determined by the body list.
        if (newIC instanceof ExplicitInitialCondition expl) n = expl.bodyCount();
        return new ScenarioParams(newIC, n, seed);
    }

    private record ScenarioParams(InitialCondition ic, int n, long seed) {}

    private void tick(StreamSession session,
                      Consumer<ByteBuffer> binarySink,
                      Consumer<String> textSink) {
        try {
            if (session.paused().get()) return;

            // Replay branch: cursor through pre-loaded HDF5 snapshots, no integration.
            List<Snapshot> replay = session.replayBuffer().get();
            Snapshot next;
            if (replay != null) {
                long pos = session.replayCursor().getAndIncrement();
                if (pos >= replay.size()) {
                    // End of buffer — loop so the user sees the cycle repeat.
                    session.replayCursor().set(1);
                    pos = 0;
                }
                Snapshot raw = replay.get((int) pos);
                // Rewrite stepIndex to the cursor position so the client scrubber
                // tracks 0..totalFrames-1 (instead of the raw saved step number).
                next = new Snapshot(pos, raw.time(),
                        raw.x(), raw.y(), raw.z(),
                        raw.vx(), raw.vy(), raw.vz(),
                        raw.mass());
                session.state().set(next);
            } else {
                double dt = session.currentDt().get();
                double eps = session.currentSoftening().get();
                next = integrator.step(session.state().get(), dt, eps);
                session.state().set(next);
            }

            binarySink.accept(SnapshotBinaryCodec.encode(next));

            // Diagnostics: real for live runs, computed for replay too (always informative).
            Diagnostics diag = diagnosticsCalculator.compute(next);
            String json = serializeDiagnostics(diag);
            if (json != null) textSink.accept(json);

            // Persist diagnostics only for live (not replay — they're already in DB).
            if (replay == null) {
                long n = session.tickCounter().incrementAndGet();
                if (n % metricsPersistEveryNthTick == 0) {
                    runRepository.recordMetrics(session.runId(), diag);
                }
            }
        } catch (Exception e) {
            log.error("Tick failed for run {}", session.runId(), e);
        }
    }

    private String serializeDiagnostics(Diagnostics d) {
        DiagnosticsDto dto = new DiagnosticsDto(
                DiagnosticsDto.TYPE,
                d.simTime(), d.stepIndex(),
                d.kineticEnergy(), d.potentialEnergy(), d.totalEnergy(),
                d.momentum(), d.angularL(), d.virialRatio(),
                d.r10(), d.r50(), d.r90()
        );
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize diagnostics", e);
            return null;
        }
    }

    /**
     * Mutable per-stream state. Atomics keep tick() lock-free.
     * {@code runId} is held in an AtomicReference because {@code loadScenario}
     * closes the current run and starts a fresh one in the same session.
     */
    private static final class StreamSession {
        private final AtomicReference<UUID> runId;
        private final AtomicReference<Snapshot> state;
        private final AtomicBoolean paused;
        private final AtomicReference<Double> currentDt;
        private final AtomicReference<Double> currentSoftening;
        private final AtomicLong tickCounter;
        private final AtomicReference<String> scenarioName;
        /** Non-null when this session is in replay mode (cursor over saved snapshots). */
        private final AtomicReference<List<Snapshot>> replayBuffer = new AtomicReference<>(null);
        private final AtomicLong replayCursor = new AtomicLong(0);
        /** Out-of-band text sender, captured at startStream time. */
        private Consumer<String> textSink;
        private ScheduledFuture<?> handle;

        StreamSession(UUID runId,
                      AtomicReference<Snapshot> state,
                      AtomicBoolean paused,
                      AtomicReference<Double> currentDt,
                      double initialSoftening,
                      AtomicLong tickCounter,
                      String scenarioName) {
            this.runId = new AtomicReference<>(runId);
            this.state = state;
            this.paused = paused;
            this.currentDt = currentDt;
            this.currentSoftening = new AtomicReference<>(initialSoftening);
            this.tickCounter = tickCounter;
            this.scenarioName = new AtomicReference<>(scenarioName);
        }

        UUID                      runId()             { return runId.get(); }
        void                      setRunId(UUID id)   { runId.set(id); }
        AtomicReference<Snapshot> state()             { return state; }
        AtomicBoolean             paused()            { return paused; }
        AtomicReference<Double>   currentDt()         { return currentDt; }
        AtomicReference<Double>   currentSoftening()  { return currentSoftening; }
        AtomicLong                tickCounter()       { return tickCounter; }
        String                    scenarioName()      { return scenarioName.get(); }
        void                      setScenarioName(String s) { scenarioName.set(s); }

        AtomicReference<List<Snapshot>> replayBuffer() { return replayBuffer; }
        AtomicLong                      replayCursor() { return replayCursor; }
        Consumer<String>                textSink()     { return textSink; }
        void                            setTextSink(Consumer<String> sink) { this.textSink = sink; }

        void setHandle(ScheduledFuture<?> handle) { this.handle = handle; }
        void cancel() { if (handle != null) handle.cancel(false); }
    }
}
