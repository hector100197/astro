package mx.astro.simulation.infrastructure.in;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import mx.astro.simulation.application.GaiaImportInvoker;
import mx.astro.simulation.application.ScenarioCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only catalog of scenarios available to the live UI and to clients.
 *
 * <ul>
 *   <li>{@code GET /api/scenarios} — list of scenarios with one-line summary
 *       per entry, no full body data.</li>
 *   <li>{@code GET /api/scenarios/{name}} — full YAML content as JSON.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioCatalogService catalog;
    private final GaiaImportInvoker gaiaImporter;

    public ScenarioController(ScenarioCatalogService catalog,
                              GaiaImportInvoker gaiaImporter) {
        this.catalog = catalog;
        this.gaiaImporter = gaiaImporter;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var entry : catalog.all()) {
            Map<String, Object> data = entry.getValue();
            Map<String, Object> ic = castMap(data.get("initial_condition"));
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("name", entry.getKey());
            summary.put("displayName", data.getOrDefault("name", entry.getKey()));
            summary.put("description", data.get("description"));
            summary.put("nBodies", data.get("n_bodies"));
            summary.put("icType", ic == null ? "unknown" : ic.get("type"));
            summary.put("supported", isSupported(ic));
            out.add(summary);
        }
        return out;
    }

    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String name) {
        return catalog.get(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().<Map<String, Object>>build());
    }

    /**
     * Register an ephemeral custom scenario from raw YAML body.
     *
     * <p>Body: text/plain or application/x-yaml — the scenario YAML.
     * Returns the assigned scenario name (sanitised from the YAML's
     * {@code name} field, or auto-generated). The scenario lives in
     * memory until JVM restart and appears in {@code GET /api/scenarios}
     * immediately, ready to load via {@code loadScenario}.
     */
    @PostMapping(value = "/custom",
                 consumes = { MediaType.TEXT_PLAIN_VALUE, "application/x-yaml", MediaType.APPLICATION_JSON_VALUE })
    public ResponseEntity<Map<String, Object>> registerCustom(@RequestBody String body) {
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty body"));
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new YAMLMapper().readValue(body, Map.class);

            // Light validation: must have initial_condition with a known type.
            Object icObj = data.get("initial_condition");
            if (!(icObj instanceof Map<?, ?> ic)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "missing initial_condition block"));
            }
            String type = String.valueOf(ic.get("type"));
            if (!"plummer".equals(type) && !"explicit".equals(type)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "initial_condition.type must be 'plummer' or 'explicit', got: " + type));
            }

            String desiredName = data.get("name") instanceof String s ? s : null;
            String registered = catalog.registerEphemeral(desiredName, data);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("name", registered);
            response.put("nBodies", data.get("n_bodies"));
            response.put("icType", type);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "could not parse YAML: " + e.getMessage()));
        }
    }

    /**
     * Fetch a real cluster from Gaia DR3 by name and register it as a permanent
     * scenario YAML in the catalog directory. Wall time: 10–30 s
     * (Gaia archive RTT). The cluster name must be one of the curated set
     * known to {@code astro_nbody.gaia_import.KNOWN_CLUSTERS} (currently
     * {@code pleiades}, {@code hyades}, {@code m67}).
     */
    @PostMapping("/import-gaia")
    public ResponseEntity<Map<String, Object>> importFromGaia(
            @RequestParam("cluster") String cluster) {
        if (cluster == null || cluster.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cluster required"));
        }
        GaiaImportInvoker.ImportResult result =
                gaiaImporter.fetch(cluster, catalog.scenariosDir());
        return switch (result) {
            case GaiaImportInvoker.ImportResult.Success s -> {
                String name = stripExt(s.yamlPath().getFileName().toString());
                catalog.evict(name);            // ensure fresh re-parse
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("name", name);
                summary.put("yamlPath", s.yamlPath().toString());
                catalog.get(name).ifPresent(data -> {
                    summary.put("displayName", data.getOrDefault("name", name));
                    summary.put("nBodies", data.get("n_bodies"));
                    Map<String, Object> ic = castMap(data.get("initial_condition"));
                    summary.put("icType", ic == null ? "unknown" : ic.get("type"));
                });
                yield ResponseEntity.ok(summary);
            }
            case GaiaImportInvoker.ImportResult.Failure f -> ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", f.message(), "stdout", f.stdout()));
            case GaiaImportInvoker.ImportResult.Unavailable u -> ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", u.message()));
        };
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static boolean isSupported(Map<String, Object> ic) {
        if (ic == null) return false;
        String type = String.valueOf(ic.get("type"));
        // Live UI integrator supports gravitational ICs only — Hénon-Heiles
        // (external_potential) is CLI-only for now.
        return "plummer".equals(type) || "explicit".equals(type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }
}
