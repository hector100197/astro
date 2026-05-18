package mx.astro.simulation.infrastructure.in;

import mx.astro.simulation.domain.RunRepository;
import mx.astro.simulation.domain.RunRepository.RunSummary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * REST API for the run history panel in the UI.
 *
 * <ul>
 *   <li>{@code GET /api/runs?limit=20} — most recent runs (default 20).</li>
 *   <li>{@code GET /api/runs/{id}} — full run summary by id.</li>
 *   <li>{@code GET /api/runs/{id}/hdf5} — download the HDF5 file (octet-stream).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/runs")
public class RunHistoryController {

    private final RunRepository runs;

    public RunHistoryController(RunRepository runs) {
        this.runs = runs;
    }

    @GetMapping
    public List<RunSummary> list(@RequestParam(defaultValue = "20") int limit) {
        return runs.findRecent(Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunSummary> get(@PathVariable UUID id) {
        return runs.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().<RunSummary>build());
    }

    @GetMapping("/{id}/hdf5")
    public ResponseEntity<Resource> downloadHdf5(@PathVariable UUID id) {
        var summaryOpt = runs.findById(id);
        if (summaryOpt.isEmpty()) return ResponseEntity.notFound().build();

        String path = summaryOpt.get().hdf5Path();
        if (path == null || path.isBlank() || !Files.exists(Path.of(path))) {
            return ResponseEntity.notFound().build();
        }
        Resource res = new FileSystemResource(path);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment", id + ".h5");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(res);
    }
}
