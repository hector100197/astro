package mx.astro.simulation.infrastructure.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import mx.astro.simulation.application.ExportJobService;
import mx.astro.simulation.infrastructure.out.ExportJobEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for headless / batch jobs.
 *
 * <ul>
 *   <li>{@code POST /api/jobs} — submit a new job.</li>
 *   <li>{@code GET  /api/jobs?limit=20} — list recent jobs.</li>
 *   <li>{@code GET  /api/jobs/{id}} — current job status.</li>
 *   <li>{@code GET  /api/jobs/{id}/hdf5} — download the multi-snapshot HDF5.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/jobs")
@Profile("!mock")
public class ExportJobController {

    private final ExportJobService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExportJobController(ExportJobService service) {
        this.service = service;
    }

    /**
     * Submit a new headless job. Required: {@code scenarioName}, {@code nSteps}.
     * Optional with sane defaults: dt, softening, seed, snapshotEvery.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody SubmitRequest req) {
        if (req.scenarioName() == null || req.scenarioName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "scenarioName required"));
        }
        if (req.nSteps() == null || req.nSteps() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "nSteps must be > 0"));
        }
        try {
            ExportJobEntity job = service.submit(
                    req.scenarioName(),
                    req.nSteps(),
                    req.dt() != null ? req.dt() : 0.005,
                    req.softening() != null ? req.softening() : 0.01,
                    req.seed() != null ? req.seed() : 42L,
                    req.snapshotEvery() != null ? req.snapshotEvery() : 100
            );
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(toDto(job));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return service.list(limit).stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        return service.get(id)
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().<Map<String, Object>>build());
    }

    @GetMapping("/{id}/hdf5")
    public ResponseEntity<Resource> downloadHdf5(@PathVariable UUID id) {
        var jobOpt = service.get(id);
        if (jobOpt.isEmpty()) return ResponseEntity.notFound().build();

        String path = jobOpt.get().getHdf5Path();
        if (path == null || path.isBlank() || !Files.exists(Path.of(path))) {
            return ResponseEntity.notFound().build();
        }
        Resource res = new FileSystemResource(path);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment", id + "-multi.h5");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(res);
    }

    /**
     * Download the rendered report. {@code kind} is one of {@code pdf},
     * {@code tex}, or {@code json} (the raw analysis output that drives the
     * report — useful for users wanting to plot it themselves).
     */
    @GetMapping("/{id}/report.{kind}")
    public ResponseEntity<Resource> downloadReport(
            @PathVariable UUID id,
            @PathVariable String kind) {
        var jobOpt = service.get(id);
        if (jobOpt.isEmpty()) return ResponseEntity.notFound().build();

        String dir = jobOpt.get().getReportDir();
        if (dir == null || dir.isBlank()) return ResponseEntity.notFound().build();

        String filename = "report." + kind.toLowerCase();
        Path file = Path.of(dir).resolve(filename);
        if (!Files.exists(file)) return ResponseEntity.notFound().build();

        MediaType type = switch (kind.toLowerCase()) {
            case "pdf"  -> MediaType.APPLICATION_PDF;
            case "tex"  -> MediaType.TEXT_PLAIN;
            case "json" -> MediaType.APPLICATION_JSON;
            default     -> MediaType.APPLICATION_OCTET_STREAM;
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment",
                id + "-report." + kind.toLowerCase());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(type)
                .body(new FileSystemResource(file));
    }

    /**
     * Return the per-check validation report (energy, momentum, virial,
     * Lagrangian radius, escaper fraction) as JSON. Used by the badge UI
     * and any client wanting the raw thresholds and observed values.
     */
    @GetMapping("/{id}/validation")
    public ResponseEntity<Object> getValidation(@PathVariable UUID id) {
        var jobOpt = service.get(id);
        if (jobOpt.isEmpty()) return ResponseEntity.notFound().build();
        String json = jobOpt.get().getValidationJson();
        if (json == null || json.isBlank()) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(mapper.readTree(json));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "validation JSON unparseable: " + e.getMessage()));
        }
    }

    private Map<String, Object> toDto(ExportJobEntity j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("createdAt", j.getCreatedAt());
        m.put("startedAt", j.getStartedAt());
        m.put("finishedAt", j.getFinishedAt());
        m.put("status", j.getStatus());
        m.put("scenarioName", j.getScenarioName());
        m.put("nBodies", j.getNBodies());
        m.put("nSteps", j.getNSteps());
        m.put("dt", j.getDt());
        m.put("softening", j.getSoftening());
        m.put("seed", j.getSeed());
        m.put("snapshotEvery", j.getSnapshotEvery());
        m.put("progressSteps", j.getProgressSteps());
        m.put("progressPct", j.getNSteps() > 0
                ? Math.round(100.0 * j.getProgressSteps() / j.getNSteps()) : 0);
        m.put("hdf5Available", j.getHdf5Path() != null && !j.getHdf5Path().isBlank());
        m.put("reportAvailable", j.getReportDir() != null && !j.getReportDir().isBlank());
        m.put("validationAvailable", j.getValidationVerdict() != null);
        m.put("validationVerdict", j.getValidationVerdict());
        m.put("errorMessage", j.getErrorMessage());
        return m;
    }

    /** Request body for POST /api/jobs (all fields except scenarioName + nSteps optional). */
    public record SubmitRequest(
            String scenarioName,
            Integer nSteps,
            Double dt,
            Double softening,
            Long seed,
            Integer snapshotEvery
    ) {}
}
