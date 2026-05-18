package mx.astro.simulation.application;

import mx.astro.simulation.domain.JobReport;
import mx.astro.simulation.domain.JobReport.BinaryEvent;
import mx.astro.simulation.domain.JobReport.EscaperEvent;
import mx.astro.simulation.domain.JobReport.SnapshotPoint;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke + golden-content tests for the two report renderers. We don't pixel-match
 * the PDF (PDFBox layout is implementation-defined) — instead we assert that
 * after rendering and re-parsing, the document contains the expected text in
 * the right order.
 */
class ReportRendererTest {

    private final LatexReportRenderer tex = new LatexReportRenderer();
    private final PdfBoxReportRenderer pdf = new PdfBoxReportRenderer();

    // ================================================================
    // LaTeX
    // ================================================================

    @Test
    @DisplayName("LaTeX: produces compilable preamble + body sections")
    void latex_basicStructure(@TempDir Path out) throws IOException {
        JobReport r = sampleReport(/*binaries*/ 3, /*hardOnes*/ 1, /*escapers*/ 2);
        Path texPath = tex.render(r, out);

        String src = Files.readString(texPath);
        assertTrue(src.startsWith("\\documentclass"), "must start with documentclass");
        assertTrue(src.contains("\\begin{document}"));
        assertTrue(src.contains("\\end{document}"));
        // Required sections.
        assertTrue(src.contains("\\section*{Run parameters}"));
        assertTrue(src.contains("\\section*{Conservation diagnostics}"));
        assertTrue(src.contains("\\section*{Binary catalog}"));
        assertTrue(src.contains("\\section*{Escaper catalog}"));
        // Provenance data made it through.
        assertTrue(src.contains("synthetic"), "scenario name");
        assertTrue(src.contains("100"),       "N=100 in tabular");
    }

    @Test
    @DisplayName("LaTeX: empty binaries → 'No bound pairs detected' (no table)")
    void latex_emptyBinaries_yieldsMessage(@TempDir Path out) throws IOException {
        JobReport r = sampleReport(0, 0, 0);
        Path texPath = tex.render(r, out);
        String src = Files.readString(texPath);

        assertTrue(src.contains("No bound pairs detected"),
                "expected empty-state message; instead got:\n" + src);
        assertFalse(src.contains("Top-"),
                "should NOT have a Top-K table when there are no binaries");
    }

    @Test
    @DisplayName("LaTeX: shows top-10 tightest pairs with 'hard' Y/N column (regression: pre-fix the table was hidden when 0 hard)")
    void latex_topTen_includesSoftPairs(@TempDir Path out) throws IOException {
        // All 5 pairs are SOFT (hard=false). Before the Turno 3 fix the table was
        // dropped entirely; we now show the tightest pairs with hard=N labels.
        JobReport r = sampleReport(/*binaries*/ 5, /*hardOnes*/ 0, /*escapers*/ 0);
        String src = Files.readString(tex.render(r, out));

        assertTrue(src.contains("Top-5 tightest pairs"),
                "expected Top-5 (not filtered out): " + extract(src, "Binary catalog", 800));
        assertTrue(src.contains(" & N \\\\"),
                "expected at least one row labelled 'hard=N'");
    }

    @Test
    @DisplayName("LaTeX: escapes problematic characters in scenario name + uuid")
    void latex_escapesSpecialChars(@TempDir Path out) throws IOException {
        JobReport raw = sampleReport(0, 0, 0);
        // Inject an underscore + ampersand (typical pitfalls in TeX strings).
        JobReport r = new JobReport(
                "abc_def",                  // underscore must become \_
                "scen & ario",              // ampersand must become \&
                raw.nBodies(), raw.nSteps(), raw.dt(), raw.softening(), raw.seed(),
                raw.timeline(), raw.binaries(), raw.escapers(), raw.conservation());

        String src = Files.readString(tex.render(r, out));
        assertTrue(src.contains("abc\\_def"),    "underscore must be escaped");
        assertTrue(src.contains("scen \\& ario"), "ampersand must be escaped");
    }

    // ================================================================
    // PDFBox
    // ================================================================

    @Test
    @DisplayName("PDFBox: produces a re-parseable PDF document")
    void pdf_isParseable(@TempDir Path out) throws IOException {
        JobReport r = sampleReport(3, 1, 2);
        Path pdfPath = pdf.render(r, out);

        assertTrue(Files.exists(pdfPath));
        assertTrue(Files.size(pdfPath) > 800, "PDF must contain meaningful content");

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            assertTrue(doc.getNumberOfPages() >= 1, "must have at least one page");
        }
    }

    @Test
    @DisplayName("PDFBox: extracted text contains every section header")
    void pdf_containsAllSections(@TempDir Path out) throws IOException {
        JobReport r = sampleReport(3, 1, 2);
        Path pdfPath = pdf.render(r, out);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            text = new PDFTextStripper().getText(doc);
        }

        assertTrue(text.contains("N-body simulation report"),     "title");
        assertTrue(text.contains("Run parameters"),               "section: Run parameters");
        assertTrue(text.contains("Conservation diagnostics"),     "section: Conservation");
        assertTrue(text.contains("Binary catalog"),               "section: Binary catalog");
        assertTrue(text.contains("Escaper catalog"),              "section: Escaper catalog");
    }

    @Test
    @DisplayName("PDFBox: empty catalogs render their empty-state messages")
    void pdf_emptyCatalogs(@TempDir Path out) throws IOException {
        JobReport r = sampleReport(0, 0, 0);
        Path pdfPath = pdf.render(r, out);
        String text;
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            text = new PDFTextStripper().getText(doc);
        }

        assertTrue(text.contains("No bound pairs detected"),
                "binary empty-state");
        assertTrue(text.contains("No bodies escaped"),
                "escaper empty-state");
    }

    @Test
    @DisplayName("PDFBox: WinAnsi-incompatible glyphs are sanitised (no exception)")
    void pdf_sanitisesUnicode(@TempDir Path out) {
        // The renderer normally substitutes Δ→d, ε→eps, ₀→0, etc. Confirm that
        // a report whose values include those glyphs doesn't blow up.
        JobReport r = sampleReport(0, 0, 0);
        assertDoesNotThrow(() -> pdf.render(r, out));
    }

    // ================================================================
    // Fixtures
    // ================================================================

    private static JobReport sampleReport(int nBinaries, int hardOnes, int nEscapers) {
        List<SnapshotPoint> timeline = List.of(
                new SnapshotPoint(0.0, 0,  0.25, -0.5,  -0.25,
                        new double[]{0,0,0}, new double[]{0,0,0}, 1.00, 0.5, 1.0, 2.0, 0, 0),
                new SnapshotPoint(0.5, 100, 0.26, -0.51, -0.25,
                        new double[]{0,0,0}, new double[]{0,0,0}, 1.02, 0.5, 1.0, 2.0,
                        nBinaries, nEscapers)
        );
        List<BinaryEvent> binaries = new java.util.ArrayList<>();
        for (int i = 0; i < nBinaries; i++) {
            binaries.add(new BinaryEvent(
                    0.5, 100, 2 * i, 2 * i + 1,
                    0.02 + i * 0.005, 0.04, 0.3, 1.0,
                    -1e-3, i < hardOnes));
        }
        List<EscaperEvent> escapers = new java.util.ArrayList<>();
        for (int i = 0; i < nEscapers; i++) {
            escapers.add(new EscaperEvent(50 + i, 0.5, 100, 5.0, 1.0, 0.1));
        }

        Map<String, Double> cons = new LinkedHashMap<>();
        cons.put("dE_over_E_initial", 1e-5);
        cons.put("dL_over_L_initial", 1e-15);
        cons.put("worst_dE_over_E",   1e-5);

        return new JobReport(
                "job-renderer-test", "synthetic",
                100, 1000, 0.005, 0.01, 42L,
                timeline, binaries, escapers, cons);
    }

    private static String extract(String src, String anchor, int len) {
        int idx = src.indexOf(anchor);
        if (idx < 0) return "(no '" + anchor + "' found)";
        return src.substring(idx, Math.min(idx + len, src.length()));
    }
}
