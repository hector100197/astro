package mx.astro.simulation.application;

import mx.astro.simulation.domain.JobReport;
import mx.astro.simulation.domain.ValidationReport;
import mx.astro.simulation.domain.ValidationReport.ValidationCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a fixed catalog of physical-consistency checks against a {@link JobReport}
 * and produces a {@link ValidationReport} with an aggregate verdict.
 *
 * <p>Thresholds are calibrated to what stellar dynamics codes (NBODY6, Starlab,
 * MYRIAD) routinely achieve on the standard Aarseth / Plummer benchmarks. They
 * are deliberately strict on the {@code pass} band so that "NBODY6-grade" means
 * what a referee would expect.
 */
@Service
@Profile("!mock")
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    // ---- Thresholds (pass <= ..., warn <= ..., fail > warn) ----
    private static final double E_PASS = 1e-3,   E_WARN = 1e-2;
    private static final double L_PASS = 1e-10,  L_WARN = 1e-6;
    private static final double VIRIAL_PASS = 0.10, VIRIAL_WARN = 0.20;
    private static final double R50_PASS = 3.0,  R50_WARN = 5.0;
    private static final double ESC_PASS = 0.05, ESC_WARN = 0.15;

    /** Run all checks. Never throws — degrades to fail-state for missing fields. */
    public ValidationReport validate(JobReport report) {
        List<ValidationCheck> checks = new ArrayList<>(6);

        double dEfinal = report.conservation().getOrDefault("dE_over_E_initial", Double.POSITIVE_INFINITY);
        checks.add(makeCheck(
                "energy_final",
                "Energy conservation |ΔE/E₀| (final)",
                dEfinal, E_PASS, E_WARN, "ratio",
                "Reference codes achieve |ΔE/E₀| < 1e-3 on the Aarseth benchmark."));

        double dEworst = report.conservation().getOrDefault("worst_dE_over_E", Double.POSITIVE_INFINITY);
        checks.add(makeCheck(
                "energy_worst",
                "Energy conservation |ΔE/E₀| (worst step)",
                dEworst, E_PASS, E_WARN, "ratio",
                "Worst-case deviation seen at any snapshot — catches transient blow-ups."));

        double dLfinal = report.conservation().getOrDefault("dL_over_L_initial", Double.POSITIVE_INFINITY);
        checks.add(makeCheck(
                "angular_momentum",
                "Angular momentum conservation |ΔL/L₀|",
                dLfinal, L_PASS, L_WARN, "ratio",
                "Leapfrog preserves L to round-off (~1e-15); larger values suggest a bug."));

        double virialOff = virialOffsetSecondHalf(report);
        checks.add(makeCheck(
                "virial",
                "Virial equilibrium |⟨Q⟩−1| (second half)",
                virialOff, VIRIAL_PASS, VIRIAL_WARN, "dimensionless",
                "A relaxed cluster oscillates around Q = −2K/U = 1 (virial theorem)."));

        double r50Ratio = r50Ratio(report);
        checks.add(makeCheck(
                "r50_stability",
                "Half-mass radius stability r₅₀(t_f) / r₅₀(0)",
                r50Ratio, R50_PASS, R50_WARN, "ratio",
                "Bound clusters expand modestly; ratios > 5× suggest disruption or runaway."));

        double escFraction = report.nBodies() > 0
                ? (double) report.escapers().size() / report.nBodies()
                : 0.0;
        checks.add(makeCheck(
                "escaper_fraction",
                "Escaper fraction N_esc / N",
                escFraction, ESC_PASS, ESC_WARN, "fraction",
                "Stable runs lose few bodies; > 15% suggests dt too large or pathological IC."));

        String verdict = aggregateVerdict(checks);
        String summary = describeVerdict(verdict, checks);
        log.info("Validation for job {}: verdict={} ({} checks)",
                report.jobId(), verdict, checks.size());
        return new ValidationReport(verdict, summary, checks);
    }

    // ============================================================
    // Per-check helpers
    // ============================================================

    private static ValidationCheck makeCheck(
            String id, String label,
            double observed, double passThreshold, double warnThreshold,
            String unit, String message
    ) {
        String severity;
        if (!Double.isFinite(observed))      severity = "fail";
        else if (observed <= passThreshold)  severity = "pass";
        else if (observed <= warnThreshold)  severity = "warn";
        else                                 severity = "fail";
        return new ValidationCheck(id, label, severity, observed,
                passThreshold, warnThreshold, unit, message);
    }

    /** {@code |<Q>-1|} averaged over the second half of the timeline. */
    private static double virialOffsetSecondHalf(JobReport report) {
        var timeline = report.timeline();
        if (timeline == null || timeline.isEmpty()) return Double.POSITIVE_INFINITY;
        int start = timeline.size() / 2;
        double sum = 0;
        int count = 0;
        for (int i = start; i < timeline.size(); i++) {
            double q = timeline.get(i).Q();
            if (Double.isFinite(q)) { sum += q; count++; }
        }
        if (count == 0) return Double.POSITIVE_INFINITY;
        return Math.abs((sum / count) - 1.0);
    }

    /** {@code r50_final / r50_initial}, or +Inf if r50_initial is non-positive. */
    private static double r50Ratio(JobReport report) {
        var timeline = report.timeline();
        if (timeline == null || timeline.size() < 2) return Double.POSITIVE_INFINITY;
        double r0 = timeline.get(0).r50();
        double rf = timeline.get(timeline.size() - 1).r50();
        if (!(r0 > 0) || !Double.isFinite(rf)) return Double.POSITIVE_INFINITY;
        return rf / r0;
    }

    // ============================================================
    // Aggregate
    // ============================================================

    private static String aggregateVerdict(List<ValidationCheck> checks) {
        boolean anyFail = false, anyWarn = false;
        for (var c : checks) {
            if ("fail".equals(c.severity())) anyFail = true;
            else if ("warn".equals(c.severity())) anyWarn = true;
        }
        if (anyFail) return "fail";
        if (anyWarn) return "warn";
        return "pass";
    }

    private static String describeVerdict(String verdict, List<ValidationCheck> checks) {
        long warns = checks.stream().filter(c -> "warn".equals(c.severity())).count();
        long fails = checks.stream().filter(c -> "fail".equals(c.severity())).count();
        return switch (verdict) {
            case "pass" -> "NBODY6-grade — all " + checks.size() + " checks within strict tolerance.";
            case "warn" -> String.format(Locale.ROOT,
                    "Marginal — %d check%s in warning band, no critical failures.",
                    warns, warns == 1 ? "" : "s");
            case "fail" -> String.format(Locale.ROOT,
                    "Failed — %d %s the failure threshold.",
                    fails, fails == 1 ? "check exceeds" : "checks exceed");
            default -> "Unknown verdict.";
        };
    }
}
