package mx.astro.simulation.application;

import mx.astro.simulation.domain.JobReport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Generates a PDF directly with Apache PDFBox — used as a fallback when the
 * host has no {@code pdflatex} installed. Output is plain (single column,
 * monospace tables, embedded plot PNGs) but always works without external
 * dependencies, which matters for the workshop / community-distribution case
 * where users may not have a TeX install.
 *
 * <p>The LaTeX path is preferred when available because the typography is
 * journal-grade; this path is the "always works" guarantee.
 */
@Component
public class PdfBoxReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxReportRenderer.class);

    private static final float MARGIN = 50f;
    private static final float LINE_HEIGHT = 13f;
    private static final float TITLE_SIZE = 18f;
    private static final float HEADING_SIZE = 13f;
    private static final float BODY_SIZE = 10f;
    private static final float MONO_SIZE = 9f;

    /**
     * Render the report to {@code outDir/report.pdf}. Plots ({@code energy.png},
     * etc.) are embedded if they exist next to the .tex/.json file.
     */
    public Path render(JobReport report, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Path pdfPath = outDir.resolve("report.pdf");

        try (PDDocument doc = new PDDocument()) {
            var serif = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            var serifBold = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
            var mono = new PDType1Font(Standard14Fonts.FontName.COURIER);

            Renderer r = new Renderer(doc, serif, serifBold, mono);
            r.newPage();

            r.title("N-body simulation report");
            r.subtitle("scenario: " + report.scenario() + "  ·  job " + report.jobId());

            r.heading("Run parameters");
            r.kv("Bodies (N)",      Integer.toString(report.nBodies()));
            r.kv("Integrator step", String.format(Locale.ROOT, "Δt = %.4g", report.dt()));
            r.kv("Softening",       String.format(Locale.ROOT, "ε  = %.4g", report.softening()));
            r.kv("Total steps",     Integer.toString(report.nSteps()));
            r.kv("Random seed",     Long.toString(report.seed()));
            r.kv("Snapshots",       Integer.toString(report.timeline().size()));

            r.heading("Conservation diagnostics");
            for (var e : report.conservation().entrySet()) {
                r.kv(prettyKey(e.getKey()), String.format(Locale.ROOT, "%.3e", e.getValue()));
            }

            embedFigure(r, outDir, "energy.png", "Energy timeline (K, U, E vs t).");
            embedFigure(r, outDir, "virial.png", "Virial ratio Q = -2K/U vs t.");
            embedFigure(r, outDir, "lagrangian.png", "Lagrangian radii (r10 / r50 / r90) vs t.");
            embedFigure(r, outDir, "binaries.png", "Binary catalog timeline.");
            embedFigure(r, outDir, "escapers.png", "Cumulative escaper timeline.");

            renderBinariesTable(r, report.binaries());
            renderEscapersTable(r, report.escapers());

            r.close();
            doc.save(pdfPath.toFile());
        }
        log.info("Wrote PDFBox report to {}", pdfPath);
        return pdfPath;
    }

    // ============================================================
    // Section helpers
    // ============================================================

    private static void embedFigure(Renderer r, Path outDir, String filename, String caption)
            throws IOException {
        Path img = outDir.resolve(filename);
        if (!Files.exists(img)) return;
        r.image(img, 480, caption);
    }

    private static void renderBinariesTable(Renderer r, List<JobReport.BinaryEvent> bins) throws IOException {
        r.heading("Binary catalog");
        if (bins.isEmpty()) {
            r.body("No bound pairs detected during the run.");
            return;
        }
        long hard = bins.stream().filter(JobReport.BinaryEvent::hard).count();
        r.body(bins.size() + " pair events; " + hard + " classified hard (|E_pair| > kT).");
        var top = bins.stream()
                .sorted((a, b) -> Double.compare(a.separation(), b.separation()))
                .limit(10).toList();
        r.body("Top-" + top.size() + " tightest pairs:");
        r.tableHeader(new String[]{"t", "step", "A", "B", "r", "a", "e", "hard"});
        for (var b : top) {
            r.tableRow(new String[]{
                    String.format(Locale.ROOT, "%.3f", b.simTime()),
                    Long.toString(b.stepIndex()),
                    Integer.toString(b.bodyA()),
                    Integer.toString(b.bodyB()),
                    String.format(Locale.ROOT, "%.3e", b.separation()),
                    String.format(Locale.ROOT, "%.3e", b.semiMajorAxis()),
                    String.format(Locale.ROOT, "%.3f", b.eccentricity()),
                    b.hard() ? "Y" : "N"
            });
        }
    }

    private static void renderEscapersTable(Renderer r, List<JobReport.EscaperEvent> esc) throws IOException {
        r.heading("Escaper catalog");
        if (esc.isEmpty()) {
            r.body("No bodies escaped the cluster during the run.");
            return;
        }
        r.body(esc.size() + " bodies became unbound. First 10 (chronological):");
        r.tableHeader(new String[]{"body", "t_esc", "step", "r", "|v|"});
        esc.stream().limit(10).forEach(e -> {
            try {
                r.tableRow(new String[]{
                        Integer.toString(e.bodyIndex()),
                        String.format(Locale.ROOT, "%.3f", e.escapeTime()),
                        Long.toString(e.escapeStepIndex()),
                        String.format(Locale.ROOT, "%.3e", e.escapeRadius()),
                        String.format(Locale.ROOT, "%.3e", e.escapeSpeed())
                });
            } catch (IOException ignored) {}
        });
    }

    private static String prettyKey(String k) {
        return switch (k) {
            case "dE_over_E_initial"    -> "|ΔE/E₀| (final)";
            case "dL_over_L_initial"    -> "|ΔL/L₀| (final)";
            case "worst_dE_over_E"      -> "|ΔE/E₀| (worst)";
            default -> k;
        };
    }

    // ============================================================
    // Renderer — page-aware text + image cursor
    // ============================================================

    /**
     * Tracks the y cursor and current page; provides primitives that auto-paginate
     * when the cursor reaches the bottom margin. Extracted into its own class so
     * the "render this section" methods read like a typesetting recipe.
     */
    private static final class Renderer {
        private final PDDocument doc;
        private final PDType1Font serif, serifBold, mono;
        private PDPage page;
        private PDPageContentStream cs;
        private float cursorY;

        Renderer(PDDocument doc, PDType1Font serif, PDType1Font serifBold, PDType1Font mono) {
            this.doc = doc; this.serif = serif; this.serifBold = serifBold; this.mono = mono;
        }

        void newPage() throws IOException {
            close();
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            cursorY = page.getMediaBox().getHeight() - MARGIN;
        }

        void close() throws IOException {
            if (cs != null) { cs.close(); cs = null; }
        }

        private void ensure(float needed) throws IOException {
            if (cursorY - needed < MARGIN) newPage();
        }

        void title(String s) throws IOException {
            ensure(TITLE_SIZE + 6);
            cs.beginText();
            cs.setFont(serifBold, TITLE_SIZE);
            cs.newLineAtOffset(MARGIN, cursorY);
            cs.showText(s);
            cs.endText();
            cursorY -= TITLE_SIZE + 6;
        }
        void subtitle(String s) throws IOException {
            ensure(BODY_SIZE + 14);
            cs.beginText();
            cs.setFont(serif, BODY_SIZE);
            cs.newLineAtOffset(MARGIN, cursorY);
            cs.showText(s);
            cs.endText();
            cursorY -= BODY_SIZE + 14;
        }
        void heading(String s) throws IOException {
            cursorY -= 10;
            ensure(HEADING_SIZE + 8);
            cs.beginText();
            cs.setFont(serifBold, HEADING_SIZE);
            cs.newLineAtOffset(MARGIN, cursorY);
            cs.showText(s);
            cs.endText();
            cursorY -= HEADING_SIZE + 6;
        }
        void body(String s) throws IOException {
            ensure(LINE_HEIGHT);
            cs.beginText();
            cs.setFont(serif, BODY_SIZE);
            cs.newLineAtOffset(MARGIN, cursorY);
            cs.showText(sanitize(s));
            cs.endText();
            cursorY -= LINE_HEIGHT;
        }
        void kv(String key, String value) throws IOException {
            ensure(LINE_HEIGHT);
            cs.beginText();
            cs.setFont(serif, BODY_SIZE);
            cs.newLineAtOffset(MARGIN, cursorY);
            cs.showText(sanitize(key + ":"));
            cs.endText();
            cs.beginText();
            cs.setFont(mono, MONO_SIZE);
            cs.newLineAtOffset(MARGIN + 160, cursorY);
            cs.showText(sanitize(value));
            cs.endText();
            cursorY -= LINE_HEIGHT;
        }
        void tableHeader(String[] cells) throws IOException {
            ensure(LINE_HEIGHT * 1.2f);
            float colW = (page.getMediaBox().getWidth() - 2 * MARGIN) / cells.length;
            cs.setFont(serifBold, MONO_SIZE);
            for (int i = 0; i < cells.length; i++) {
                cs.beginText();
                cs.newLineAtOffset(MARGIN + i * colW, cursorY);
                cs.showText(sanitize(cells[i]));
                cs.endText();
            }
            cursorY -= LINE_HEIGHT;
        }
        void tableRow(String[] cells) throws IOException {
            ensure(LINE_HEIGHT);
            float colW = (page.getMediaBox().getWidth() - 2 * MARGIN) / cells.length;
            cs.setFont(mono, MONO_SIZE);
            for (int i = 0; i < cells.length; i++) {
                cs.beginText();
                cs.newLineAtOffset(MARGIN + i * colW, cursorY);
                cs.showText(sanitize(cells[i]));
                cs.endText();
            }
            cursorY -= LINE_HEIGHT;
        }
        void image(Path img, float maxWidth, String caption) throws IOException {
            PDImageXObject xobj = PDImageXObject.createFromFile(img.toString(), doc);
            float ratio = (float) xobj.getHeight() / xobj.getWidth();
            float w = Math.min(maxWidth, page.getMediaBox().getWidth() - 2 * MARGIN);
            float h = w * ratio;
            ensure(h + LINE_HEIGHT + 8);
            cursorY -= h;
            cs.drawImage(xobj, MARGIN, cursorY, w, h);
            cursorY -= 4;
            cs.beginText();
            cs.setFont(serif, BODY_SIZE - 1);
            cs.newLineAtOffset(MARGIN, cursorY);
            cs.showText(sanitize("Figure: " + caption));
            cs.endText();
            cursorY -= LINE_HEIGHT;
        }

        /** WinAnsi (the Standard14 font encoding) chokes on glyphs outside Latin-1 +
         *  cannot render e.g. subscripts. Strip / substitute the few we use. */
        private static String sanitize(String s) {
            if (s == null) return "";
            return s.replace("Δ", "d")
                    .replace("ε", "eps")
                    .replace("·", "-")
                    .replace("|", "|")
                    .replace("ℓ", "l")
                    .replace("∞", "inf")
                    .replace("₀", "0")
                    .replace("₁", "1")
                    .replace("₂", "2")
                    .replace("₃", "3")
                    .replace("₄", "4")
                    .replace("₅", "5")
                    .replace("₆", "6")
                    .replace("₇", "7")
                    .replace("₈", "8")
                    .replace("₉", "9");
        }
    }
}
