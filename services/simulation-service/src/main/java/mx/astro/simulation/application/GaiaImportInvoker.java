package mx.astro.simulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Spawns the {@code astro_nbody.gaia_import} Python CLI to fetch a real cluster
 * from the Gaia DR3 archive and emit a Hénon-normalised scenario YAML in the
 * scenarios directory.
 *
 * <p>Same architectural pattern as {@link PythonPlotInvoker}: subprocess, no
 * libhdf5 involved, best-effort with clear error reporting. The Python side
 * does the heavy lifting (astroquery, astropy unit conversion, CoM recentring,
 * Hénon normalisation) — we just orchestrate.
 *
 * <p>Typical wall time: 10–30 s, dominated by the Gaia archive RTT.
 */
@Component
public class GaiaImportInvoker {

    private static final Logger log = LoggerFactory.getLogger(GaiaImportInvoker.class);

    private final String pythonExecutable;
    private final long timeoutSeconds;

    public GaiaImportInvoker(
            @Value("${astro.python.executable:#{null}}") String configured,
            @Value("${astro.python.gaia-timeout-seconds:120}") long timeoutSeconds
    ) {
        this.pythonExecutable = resolvePython(configured);
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Outcome of an import attempt — either a path to the new YAML, or a message. */
    public sealed interface ImportResult {
        record Success(Path yamlPath, String stdout) implements ImportResult {}
        record Failure(String message, String stdout) implements ImportResult {}
        record Unavailable(String message) implements ImportResult {}
    }

    /**
     * Run {@code python -m astro_nbody.gaia_import <cluster> -o <scenariosDir>/gaia_<cluster>.yaml}.
     *
     * @param cluster cluster name ({@code pleiades}, {@code hyades}, {@code m67}); validated by the Python side.
     * @param scenariosDir directory where the resulting YAML must land (so the catalog picks it up).
     */
    public ImportResult fetch(String cluster, Path scenariosDir) {
        if (pythonExecutable == null) {
            return new ImportResult.Unavailable(
                    "No Python executable resolved (set ASTRO_PYTHON or install python/.venv)");
        }
        String safeName = cluster.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (safeName.isBlank()) {
            return new ImportResult.Failure("invalid cluster name", "");
        }
        Path output = scenariosDir.resolve("gaia_" + safeName + ".yaml");
        try {
            Files.createDirectories(scenariosDir);
        } catch (IOException e) {
            return new ImportResult.Failure("cannot create scenarios dir: " + e.getMessage(), "");
        }

        ProcessBuilder pb = new ProcessBuilder(
                pythonExecutable, "-m", "astro_nbody.gaia_import",
                safeName, "-o", output.toString()
        );
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new ImportResult.Failure(
                        "Gaia import timed out after " + timeoutSeconds + "s", "");
            }
            String output_str = new String(p.getInputStream().readAllBytes());
            if (p.exitValue() != 0) {
                log.warn("Gaia import subprocess failed (exit {}) for {}:\n{}",
                        p.exitValue(), cluster, output_str);
                return new ImportResult.Failure(
                        "Python exited " + p.exitValue() + ": " + lastLine(output_str),
                        output_str);
            }
            if (!Files.isRegularFile(output)) {
                return new ImportResult.Failure(
                        "Python succeeded but expected YAML not found at " + output,
                        output_str);
            }
            log.info("Gaia import {} succeeded: {} ({} bytes)",
                    cluster, output, fileSizeOrZero(output));
            return new ImportResult.Success(output, output_str);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ImportResult.Failure("Subprocess error: " + e.getMessage(), "");
        }
    }

    /** Same resolution policy as {@link PythonPlotInvoker}. */
    private static String resolvePython(String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        String env = System.getenv("ASTRO_PYTHON");
        if (env != null && !env.isBlank() && Files.exists(Path.of(env))) return env;
        for (String relative : new String[]{
                "../../python/.venv/bin/python",
                "../../python/.venv/bin/python3",
                "python/.venv/bin/python",
        }) {
            Path candidate = Paths.get(relative).toAbsolutePath().normalize();
            if (Files.exists(candidate)) return candidate.toString();
        }
        return "python3";
    }

    private static String lastLine(String s) {
        if (s == null || s.isBlank()) return "";
        String[] lines = s.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i].trim();
        }
        return "";
    }

    private static long fileSizeOrZero(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }
}
