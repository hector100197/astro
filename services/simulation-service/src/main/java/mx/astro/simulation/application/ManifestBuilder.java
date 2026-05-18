package mx.astro.simulation.application;

import mx.astro.simulation.domain.DiagnosticsCalculator;
import mx.astro.simulation.domain.InitialCondition;
import mx.astro.simulation.domain.Integrator;
import mx.astro.simulation.domain.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds {@link Manifest} instances populated with everything we know about
 * the running environment at the moment a simulation starts.
 *
 * <p>The expensive bits (binary SHA-256, git SHA via subprocess) are computed
 * lazily and cached, since they don't change between runs of the same
 * service instance.
 */
@Service
public class ManifestBuilder {

    private static final Logger log = LoggerFactory.getLogger(ManifestBuilder.class);

    private final String kernelLibraryPath;
    private final int openmpThreads;

    // Cached because they're expensive to compute and constant per JVM lifetime.
    private volatile String cachedBinarySha = null;
    private volatile String cachedGitSha    = null;

    public ManifestBuilder(
            @Value("${astro.kernel.library-path}") String kernelLibraryPath,
            @Value("${astro.kernel.openmp-threads:8}") int openmpThreads
    ) {
        this.kernelLibraryPath = kernelLibraryPath;
        this.openmpThreads = openmpThreads;
    }

    public Manifest build(
            UUID runId,
            int n, double dt, double softening, long seed,
            String integratorName,
            String initialConditionName,
            String diagnosticsName,
            String scenarioName
    ) {
        return new Manifest(
                runId,
                Instant.now(),
                kernelInfo(),
                scenarioInfo(scenarioName),
                parametersInfo(n, dt, softening, seed,
                        integratorName, initialConditionName, diagnosticsName),
                hardwareInfo(),
                softwareInfo()
        );
    }

    /**
     * Convenience overload using the live Strategy beans for {@code name()}.
     */
    public Manifest build(
            UUID runId,
            int n, double dt, double softening, long seed,
            Integrator integrator,
            InitialCondition ic,
            DiagnosticsCalculator diag,
            String scenarioName
    ) {
        return build(runId, n, dt, softening, seed,
                integrator.name(), ic.name(), diag.name(), scenarioName);
    }

    // ---------- private builders ----------

    private Map<String, Object> kernelInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("library_path", kernelLibraryPath);
        m.put("binary_sha256", binarySha());
        m.put("git_sha", gitSha());
        m.put("compiler", "gfortran (compile-time)");
        m.put("compile_flags", "-O3 -march=native -fopenmp -fimplicit-none -std=f2018 -fPIC");
        m.put("openmp_threads", openmpThreads);
        return m;
    }

    private Map<String, Object> scenarioInfo(String scenarioName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", scenarioName != null ? scenarioName : "default_plummer");
        return m;
    }

    private Map<String, Object> parametersInfo(int n, double dt, double softening, long seed,
                                               String integrator, String ic, String diag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n_bodies", n);
        m.put("dt", dt);
        m.put("softening", softening);
        m.put("integrator", integrator);
        m.put("initial_condition", ic);
        m.put("diagnostics_calculator", diag);
        m.put("rng_seed", seed);
        m.put("units", "henon");
        return m;
    }

    private Map<String, Object> hardwareInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpu_arch", System.getProperty("os.arch"));
        m.put("cores", Runtime.getRuntime().availableProcessors());
        m.put("ram_max_bytes", Runtime.getRuntime().maxMemory());
        m.put("os_name", System.getProperty("os.name"));
        m.put("os_version", System.getProperty("os.version"));
        return m;
    }

    private Map<String, Object> softwareInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("java_version", System.getProperty("java.version"));
        m.put("java_vendor", System.getProperty("java.vendor"));
        m.put("spring_boot_version", SpringBootVersion.getVersion());
        m.put("service_name", "simulation-service");
        return m;
    }

    // ---------- expensive things, cached ----------

    private String binarySha() {
        String cached = cachedBinarySha;
        if (cached != null) return cached;

        try (InputStream in = Files.newInputStream(Path.of(kernelLibraryPath))) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) md.update(buf, 0, read);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            cached = sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("Failed to compute kernel SHA-256: {}", e.getMessage());
            cached = "unknown";
        }
        cachedBinarySha = cached;
        return cached;
    }

    private String gitSha() {
        String cached = cachedGitSha;
        if (cached != null) return cached;

        cached = runGit("rev-parse", "HEAD");
        if (cached == null) cached = "unknown";
        cachedGitSha = cached;
        return cached;
    }

    private String runGit(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            if (exit != 0 || out.isBlank()) return null;
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
