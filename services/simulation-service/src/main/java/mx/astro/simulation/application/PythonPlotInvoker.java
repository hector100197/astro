package mx.astro.simulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort generator for matplotlib PNG plots that the report renderers
 * embed if present.
 *
 * <p>Architecture note: the Fortran kernel's libhdf5 is locked into this JVM
 * for the process lifetime, so any in-JVM HDF5 reader is blocked. Spawning
 * a subprocess that <em>also</em> opens the same HDF5 file fails for the same
 * reason. The clean solution we adopt here is that the Python subprocess
 * reads the <strong>report.json</strong> (a pure-Java analysis we already
 * produce), not the HDF5 — Python never touches libhdf5 in this path, so
 * everything just works.
 *
 * <p>Best-effort because a missing virtualenv or a stale matplotlib must not
 * fail the job. The PDF/LaTeX renderers gracefully skip image embedding when
 * the PNGs aren't there, so a Python failure simply degrades the report from
 * "plots + tables" to "tables only".
 */
@Component
@Profile("!mock")
public class PythonPlotInvoker {

    private static final Logger log = LoggerFactory.getLogger(PythonPlotInvoker.class);

    /** Override via {@code ASTRO_PYTHON} env var or {@code astro.python.executable}
     *  Spring property. Falls back to the per-repo virtualenv at python/.venv. */
    private final String pythonExecutable;
    private final long timeoutSeconds;

    public PythonPlotInvoker(
            @Value("${astro.python.executable:#{null}}") String configured,
            @Value("${astro.python.timeout-seconds:60}") long timeoutSeconds
    ) {
        this.pythonExecutable = resolvePython(configured);
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Run {@code python -m astro_nbody.report_plots --from-json <reportDir>/report.json
     * <reportDir>}. Returns the number of PNGs found in {@code reportDir} after the
     * subprocess exits — useful for the caller to decide whether to log a
     * "plots ready" or "plots skipped" line.
     */
    public int generatePlots(Path reportDir) {
        if (pythonExecutable == null) {
            log.info("Python executable not resolved; skipping plot generation for {}", reportDir);
            return 0;
        }
        Path reportJson = reportDir.resolve("report.json");
        if (!Files.exists(reportJson)) {
            log.warn("report.json missing at {} — cannot generate plots", reportJson);
            return 0;
        }
        ProcessBuilder pb = new ProcessBuilder(
                pythonExecutable, "-m", "astro_nbody.report_plots",
                "--from-json", reportJson.toString(), reportDir.toString()
        );
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Python plot generation timed out after {}s for {}",
                        timeoutSeconds, reportDir);
                return 0;
            }
            String output = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0) {
                log.warn("Python plot subprocess exited {} for {}:\n{}",
                        p.exitValue(), reportDir, output);
                return 0;
            }
            int count = countPngs(reportDir);
            log.info("Generated {} plot PNGs for {}", count, reportDir);
            if (log.isDebugEnabled()) log.debug("python output:\n{}", output);
            return count;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Plot generation failed for {}: {}", reportDir, e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------
    // Resolution helpers
    // ------------------------------------------------------------

    private static String resolvePython(String configured) {
        // 1. Explicit Spring property wins.
        if (configured != null && !configured.isBlank()) return configured;
        // 2. Env var.
        String env = System.getenv("ASTRO_PYTHON");
        if (env != null && !env.isBlank() && Files.exists(Path.of(env))) return env;
        // 3. Per-repo virtualenv, the developer-default install path.
        // simulation-service runs from {repo}/services/simulation-service, so
        // ../../python/.venv/bin/python is the canonical hit.
        for (String relative : new String[]{
                "../../python/.venv/bin/python",
                "../../python/.venv/bin/python3",
                "python/.venv/bin/python",
        }) {
            Path candidate = Paths.get(relative).toAbsolutePath().normalize();
            if (Files.exists(candidate)) return candidate.toString();
        }
        // 4. Plain `python3` on PATH (last resort — may lack matplotlib).
        return "python3";
    }

    private static int countPngs(Path dir) {
        try {
            return (int) Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Exposed for tests and diagnostics. */
    public String resolvedPython() { return pythonExecutable; }
}
