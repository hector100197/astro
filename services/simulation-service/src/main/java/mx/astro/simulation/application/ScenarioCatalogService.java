package mx.astro.simulation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Loads the YAML scenario catalog from {@code scenarios/} on the filesystem.
 *
 * <p>Each YAML file is parsed once and cached in memory. Schema is the same
 * one used by the Python CLI ({@code astro_nbody.cli}) and the docs:
 *
 * <pre>
 *   name: pleiades
 *   description: ...
 *   n_bodies: 3000
 *   units: henon
 *   initial_condition:
 *     type: plummer | explicit
 *     ...
 *   simulation:
 *     dt: 0.005
 *     softening: 0.01
 *     ...
 * </pre>
 *
 * <p>Headless / CLI / live UI all share this catalog so a "Pleiades" run is
 * identical regardless of how it's launched.
 */
@Service
public class ScenarioCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCatalogService.class);

    private final ObjectMapper yaml = new YAMLMapper();
    private final Path scenariosDir;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public ScenarioCatalogService(@Value("${astro.scenarios.dir:../../scenarios}") String dir) {
        this.scenariosDir = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(this.scenariosDir)) {
            log.warn("Scenarios directory not found at {} — catalog will be empty", this.scenariosDir);
        } else {
            preload();
        }
    }

    /** Names of every scenario in the catalog (filename without extension + ephemerals). */
    public List<String> names() {
        java.util.Set<String> all = new java.util.TreeSet<>();
        if (Files.isDirectory(scenariosDir)) {
            try (Stream<Path> files = Files.list(scenariosDir)) {
                files
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .map(p -> stripExt(p.getFileName().toString()))
                    .forEach(all::add);
            } catch (IOException e) {
                log.warn("Failed to list scenarios in {}: {}", scenariosDir, e.getMessage());
            }
        }
        all.addAll(cache.keySet());        // include ephemerals not backed by disk
        return new ArrayList<>(all);
    }

    /**
     * Register an ephemeral scenario (in-memory only, not saved to disk).
     * Used by POST /api/scenarios/custom — the user uploads YAML, we keep it
     * in the catalog for the lifetime of this JVM instance, and they can
     * pick it from the dropdown.
     *
     * Returns the assigned name (caller-provided or auto-generated).
     */
    public String registerEphemeral(String desiredName, Map<String, Object> data) {
        String name = (desiredName != null && !desiredName.isBlank())
                ? sanitize(desiredName)
                : "custom_" + Long.toHexString(System.currentTimeMillis());
        cache.put(name, data);
        log.info("Registered ephemeral scenario: {}", name);
        return name;
    }

    private static String sanitize(String s) {
        // Allow only [a-zA-Z0-9_-] + collapse the rest to underscore.
        return s.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** Look up a scenario by name. Returns empty if not found. */
    public Optional<Map<String, Object>> get(String name) {
        Map<String, Object> cached = cache.get(name);
        if (cached != null) return Optional.of(cached);
        Path file = scenariosDir.resolve(name + ".yaml");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yaml.readValue(file.toFile(), Map.class);
            cache.put(name, data);
            return Optional.of(data);
        } catch (IOException e) {
            log.warn("Failed to parse scenario {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns every scenario as (name, content) pairs. */
    public List<Map.Entry<String, Map<String, Object>>> all() {
        List<Map.Entry<String, Map<String, Object>>> out = new ArrayList<>();
        for (String n : names()) get(n).ifPresent(c -> out.add(Map.entry(n, c)));
        return out;
    }

    private void preload() {
        names().forEach(this::get);
        log.info("Loaded {} scenarios from {}: {}", cache.size(), scenariosDir, names());
    }

    /** Filesystem location of the scenarios catalog. Exposed for tools that
     *  need to write new YAMLs there (e.g. the Gaia importer). */
    public Path scenariosDir() {
        return scenariosDir;
    }

    /**
     * Force-evict a single name from the parse cache so the next {@link #get}
     * re-reads the YAML from disk. Use after a tool overwrites the file.
     */
    public void evict(String name) {
        cache.remove(name);
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
