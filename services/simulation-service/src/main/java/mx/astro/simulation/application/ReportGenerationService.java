package mx.astro.simulation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import mx.astro.simulation.domain.JobReport;
import mx.astro.simulation.domain.Snapshot;
import mx.astro.simulation.domain.ValidationReport;
import mx.astro.simulation.infrastructure.out.ExportJobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Top-level pipeline that turns a finished batch job into a downloadable
 * report bundle.
 *
 * <p>Architecture: all analysis runs in-process in the JVM, against the
 * snapshot list collected during the integration loop. We do NOT round-trip
 * through the on-disk HDF5 because the kernel's libhdf5 holds an exclusive
 * grip on the file for the entire JVM lifetime, blocking any other libhdf5
 * (jHDF, h5py, even subprocess-spawned) from reading it.
 *
 * <p>Output bundle in {@code <hdf5_dir>/<jobId>-report/}:
 * <ul>
 *   <li>{@code report.json} — the {@link JobReport} as JSON</li>
 *   <li>{@code report.tex}  — LaTeX source for users who want journal-grade output</li>
 *   <li>{@code report.pdf}  — synthesised PDF (always present, via PDFBox)</li>
 * </ul>
 */
@Service
@Profile("!mock")
public class ReportGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerationService.class);

    private final InMemoryJobReportAnalyser analyser;
    private final ValidationService validationService;
    private final LatexReportRenderer latexRenderer;
    private final PdfBoxReportRenderer pdfboxRenderer;
    private final PythonPlotInvoker plotInvoker;
    private final ObjectMapper mapper;

    public ReportGenerationService(
            InMemoryJobReportAnalyser analyser,
            ValidationService validationService,
            LatexReportRenderer latexRenderer,
            PdfBoxReportRenderer pdfboxRenderer,
            PythonPlotInvoker plotInvoker
    ) {
        this.analyser = analyser;
        this.validationService = validationService;
        this.latexRenderer = latexRenderer;
        this.pdfboxRenderer = pdfboxRenderer;
        this.plotInvoker = plotInvoker;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Run the full pipeline using snapshots already in JVM memory. Returns a
     * bundle with the artifact directory plus the serialised validation report
     * so the caller can persist the verdict alongside the job row.
     */
    public ReportBundle generate(ExportJobEntity job, List<Snapshot> snapshots) throws IOException {
        Path hdf5 = Path.of(job.getHdf5Path());
        Path reportDir = hdf5.getParent().resolve(job.getId() + "-report");
        Files.createDirectories(reportDir);

        // ---- 1. In-memory analysis ----
        JobReport report = analyser.analyse(job, snapshots);

        // ---- 2. report.json ----
        Path jsonPath = reportDir.resolve("report.json");
        mapper.writeValue(jsonPath.toFile(), report);

        // ---- 3. matplotlib plots (best-effort) ----
        // Must run BEFORE the renderers so the PNGs are present when the
        // LaTeX/PDF renderers look for them to embed. Failure here is logged
        // but does not block report generation.
        plotInvoker.generatePlots(reportDir);

        // ---- 4. report.tex ----
        latexRenderer.render(report, reportDir);

        // ---- 5. report.pdf via PDFBox ----
        pdfboxRenderer.render(report, reportDir);
        Path pdfPath = reportDir.resolve("report.pdf");
        if (Files.exists(pdfPath)) {
            log.info("Report PDF ready at {}", pdfPath);
        }

        // ---- 6. validation.json ----
        ValidationReport validation = validationService.validate(report);
        Path valPath = reportDir.resolve("validation.json");
        mapper.writeValue(valPath.toFile(), validation);
        String validationJson = mapper.writeValueAsString(validation);

        return new ReportBundle(reportDir, validationJson, validation.verdict());
    }

    /** Output of {@link #generate}: paths + denormalised verdict for persistence. */
    public record ReportBundle(Path reportDir, String validationJson, String validationVerdict) {}
}
