package mx.astro.simulation.application;

import mx.astro.simulation.domain.JobReport;
import mx.astro.simulation.domain.JobReport.SnapshotPoint;
import mx.astro.simulation.domain.ValidationReport;
import mx.astro.simulation.domain.ValidationReport.ValidationCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the validation catalog against synthetic {@link JobReport}s. We don't
 * exercise the Fortran kernel here — that's covered by {@code
 * PhysicsValidationTest}. The goal is to lock in the threshold logic and the
 * aggregate-verdict rules so future tweaks (looser or stricter bands) don't
 * regress silently.
 *
 * <p>The verdict logic is: any {@code fail} → {@code fail}; otherwise any
 * {@code warn} → {@code warn}; otherwise {@code pass}.
 */
class ValidationServiceTest {

    private final ValidationService svc = new ValidationService();

    // ================================================================
    // Aggregate verdict
    // ================================================================

    @Test
    @DisplayName("All checks within strict tolerance → verdict=pass + 'NBODY6-grade'")
    void allPass_yieldsPassVerdict() {
        JobReport report = healthyReport();
        ValidationReport v = svc.validate(report);

        assertEquals("pass", v.verdict());
        assertTrue(v.summary().startsWith("NBODY6-grade"));
        assertEquals(6, v.checks().size());
        assertTrue(v.checks().stream().allMatch(c -> "pass".equals(c.severity())),
                "every check should be pass: " + summarise(v));
    }

    @Test
    @DisplayName("Energy in warning band but no critical fail → verdict=warn")
    void anyWarn_noFail_yieldsWarnVerdict() {
        JobReport report = baseReport(Map.of(
                "dE_over_E_initial", 5e-3,        // > 1e-3 pass, < 1e-2 warn → warn
                "worst_dE_over_E",   5e-3,
                "dL_over_L_initial", 1e-15        // pass
        ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.05), escaperFractionZero());

        ValidationReport v = svc.validate(report);

        assertEquals("warn", v.verdict());
        assertTrue(v.summary().startsWith("Marginal"));
        assertTrue(v.summary().contains("warning"));
    }

    @Test
    @DisplayName("One check exceeds fail threshold → verdict=fail (plural form correct)")
    void anyFail_yieldsFailVerdict() {
        JobReport report = baseReport(Map.of(
                "dE_over_E_initial", 0.05,        // > 1e-2 → fail
                "worst_dE_over_E",   0.05,        // > 1e-2 → fail
                "dL_over_L_initial", 1e-15
        ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.05), escaperFractionZero());

        ValidationReport v = svc.validate(report);

        assertEquals("fail", v.verdict());
        assertTrue(v.summary().startsWith("Failed"));
        // Two failures: "2 checks exceed" (plural). Catches the singular/plural typo regression.
        assertTrue(v.summary().contains("2 checks exceed"),
                "expected plural 'checks exceed' but got: " + v.summary());
    }

    @Test
    @DisplayName("Single failure uses singular 'check exceeds'")
    void singleFail_usesSingularForm() {
        // Only the worst-case energy fails; the final one stays just below the fail threshold.
        JobReport report = baseReport(Map.of(
                "dE_over_E_initial", 5e-3,        // warn
                "worst_dE_over_E",   0.05,        // fail
                "dL_over_L_initial", 1e-15
        ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.05), escaperFractionZero());

        ValidationReport v = svc.validate(report);

        assertEquals("fail", v.verdict());
        assertTrue(v.summary().contains("1 check exceeds"),
                "expected singular 'check exceeds' but got: " + v.summary());
    }

    // ================================================================
    // Per-check thresholds
    // ================================================================

    @Nested
    @DisplayName("Energy conservation thresholds")
    class EnergyThresholds {

        @Test
        @DisplayName("dE/E exactly at pass boundary (1e-3) is still pass")
        void energyAtPassBoundary() {
            JobReport report = baseReport(Map.of(
                    "dE_over_E_initial", 1e-3,
                    "worst_dE_over_E",   1e-3,
                    "dL_over_L_initial", 1e-15
            ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.0), escaperFractionZero());

            assertCheckSeverity(svc.validate(report), "energy_final", "pass");
            assertCheckSeverity(svc.validate(report), "energy_worst", "pass");
        }

        @Test
        @DisplayName("dE/E just above pass (1.1e-3) is warn")
        void energyJustAbovePass() {
            JobReport report = baseReport(Map.of(
                    "dE_over_E_initial", 1.1e-3,
                    "worst_dE_over_E",   1.1e-3,
                    "dL_over_L_initial", 1e-15
            ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.0), escaperFractionZero());

            assertCheckSeverity(svc.validate(report), "energy_final", "warn");
        }

        @Test
        @DisplayName("dE/E exactly at warn boundary (1e-2) is still warn")
        void energyAtWarnBoundary() {
            JobReport report = baseReport(Map.of(
                    "dE_over_E_initial", 1e-2,
                    "worst_dE_over_E",   1e-2,
                    "dL_over_L_initial", 1e-15
            ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.0), escaperFractionZero());

            assertCheckSeverity(svc.validate(report), "energy_final", "warn");
        }
    }

    @Nested
    @DisplayName("Angular momentum conservation")
    class AngularMomentum {

        @Test
        @DisplayName("Round-off level (1e-15) is pass — leapfrog is symplectic")
        void roundOffIsPass() {
            JobReport report = healthyReport();
            assertCheckSeverity(svc.validate(report), "angular_momentum", "pass");
        }

        @Test
        @DisplayName("Above pass threshold (1e-8) is warn")
        void smallDriftIsWarn() {
            JobReport report = baseReport(Map.of(
                    "dE_over_E_initial", 1e-5,
                    "worst_dE_over_E",   1e-5,
                    "dL_over_L_initial", 1e-8         // > 1e-10, < 1e-6 → warn
            ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.0), escaperFractionZero());

            assertCheckSeverity(svc.validate(report), "angular_momentum", "warn");
        }
    }

    @Nested
    @DisplayName("Virial equilibrium check (uses second half of timeline)")
    class Virial {

        @Test
        @DisplayName("First half can be wildly off; only second half counts")
        void firstHalfIgnored() {
            // Build a timeline where first half has Q far from 1, second half virialised.
            List<SnapshotPoint> timeline = new ArrayList<>();
            for (int i = 0; i < 10; i++) timeline.add(virialPoint(i, 3.0));   // way off
            for (int i = 10; i < 20; i++) timeline.add(virialPoint(i, 1.01)); // dead on

            JobReport report = reportWithTimeline(timeline);
            ValidationCheck virial = findCheck(svc.validate(report), "virial");
            assertEquals("pass", virial.severity());
            assertEquals(0.01, virial.observed(), 1e-9);
        }

        @Test
        @DisplayName("Empty timeline collapses to +Inf → fail")
        void emptyTimeline_failsVirial() {
            JobReport report = reportWithTimeline(List.of());
            ValidationCheck virial = findCheck(svc.validate(report), "virial");
            assertEquals("fail", virial.severity());
        }
    }

    @Nested
    @DisplayName("Half-mass radius stability r50(t_f) / r50(0)")
    class R50Stability {

        @Test
        @DisplayName("Ratio of 1.5 (modest expansion) → pass")
        void modestExpansion() {
            JobReport report = reportWithTimeline(List.of(
                    r50Point(0,  /*r50*/ 1.0),
                    r50Point(1,  /*r50*/ 1.5)
            ));
            assertCheckSeverity(svc.validate(report), "r50_stability", "pass");
        }

        @Test
        @DisplayName("Ratio of 4 (significant expansion) → warn")
        void significantExpansion() {
            JobReport report = reportWithTimeline(List.of(
                    r50Point(0, 1.0),
                    r50Point(1, 4.0)
            ));
            assertCheckSeverity(svc.validate(report), "r50_stability", "warn");
        }

        @Test
        @DisplayName("Ratio of 10 (cluster disruption) → fail")
        void clusterDisruption() {
            JobReport report = reportWithTimeline(List.of(
                    r50Point(0, 1.0),
                    r50Point(1, 10.0)
            ));
            assertCheckSeverity(svc.validate(report), "r50_stability", "fail");
        }
    }

    @Nested
    @DisplayName("Escaper fraction N_esc / N")
    class EscaperFraction {

        @Test
        @DisplayName("0 escapers in 3000 bodies → pass")
        void zeroEscapers() {
            JobReport report = healthyReport();   // healthy has 0 escapers
            assertCheckSeverity(svc.validate(report), "escaper_fraction", "pass");
        }

        @Test
        @DisplayName("100 of 1000 (10%) → warn")
        void tenPercent_warn() {
            JobReport report = baseReport(Map.of(
                    "dE_over_E_initial", 1e-5,
                    "worst_dE_over_E",   1e-5,
                    "dL_over_L_initial", 1e-15
            ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.0),
                /*nBodies*/ 1000, /*nEscapers*/ 100);

            assertCheckSeverity(svc.validate(report), "escaper_fraction", "warn");
        }

        @Test
        @DisplayName("300 of 1000 (30%) → fail")
        void thirtyPercent_fail() {
            JobReport report = baseReport(Map.of(
                    "dE_over_E_initial", 1e-5,
                    "worst_dE_over_E",   1e-5,
                    "dL_over_L_initial", 1e-15
            ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.0),
                /*nBodies*/ 1000, /*nEscapers*/ 300);

            assertCheckSeverity(svc.validate(report), "escaper_fraction", "fail");
        }
    }

    // ================================================================
    // Defensive paths
    // ================================================================

    @Test
    @DisplayName("Missing conservation keys → +Inf observed → fail (defensive)")
    void missingConservationKeys_failClosed() {
        JobReport report = reportWithConservation(Map.of()); // empty map
        ValidationReport v = svc.validate(report);

        // All three conservation-derived checks should bail to fail.
        assertCheckSeverity(v, "energy_final",     "fail");
        assertCheckSeverity(v, "energy_worst",     "fail");
        assertCheckSeverity(v, "angular_momentum", "fail");
        assertEquals("fail", v.verdict());
    }

    @Test
    @DisplayName("Non-finite (NaN/Inf) observed values → fail (never crash)")
    void nonFiniteObserved_failClosed() {
        JobReport report = baseReport(Map.of(
                "dE_over_E_initial", Double.NaN,
                "worst_dE_over_E",   Double.POSITIVE_INFINITY,
                "dL_over_L_initial", 1e-15
        ), virialOffsetSecondHalf(0.005), r50FinalOverInitial(1.0), escaperFractionZero());

        assertDoesNotThrow(() -> svc.validate(report));
        ValidationReport v = svc.validate(report);
        assertCheckSeverity(v, "energy_final", "fail");
        assertCheckSeverity(v, "energy_worst", "fail");
    }

    @Test
    @DisplayName("Per-check thresholds and units are exposed verbatim for the UI")
    void checkExposesThresholdsAndUnit() {
        ValidationReport v = svc.validate(healthyReport());
        ValidationCheck dEf = findCheck(v, "energy_final");

        assertEquals(1e-3,  dEf.passThreshold());
        assertEquals(1e-2,  dEf.warnThreshold());
        assertEquals("ratio", dEf.unit());
        assertNotNull(dEf.message());
        assertFalse(dEf.message().isBlank());
    }

    // ================================================================
    // Test fixtures
    // ================================================================

    private JobReport healthyReport() {
        // Matches the pleiades N=3000 run from the live system (verdict=pass).
        return baseReport(Map.of(
                "dE_over_E_initial",  3.3e-6,
                "worst_dE_over_E",    4.4e-6,
                "dL_over_L_initial",  4.3e-15
        ), virialOffsetSecondHalf(0.0043), r50FinalOverInitial(1.0036), escaperFractionZero());
    }

    private JobReport baseReport(Map<String, Double> cons,
                                 double virialOffset,
                                 double r50Ratio,
                                 int nBodies, int nEscapers) {
        // Build a 4-point timeline so virial offset uses the second half (last 2 points)
        // and r50 reflects the requested ratio.
        List<SnapshotPoint> timeline = List.of(
                point(0, 0.0,  /*Q*/ 1.0,            /*r50*/ 1.0),
                point(1, 0.25, /*Q*/ 1.0,            /*r50*/ 1.0),
                point(2, 0.5,  /*Q*/ 1.0 + virialOffset, /*r50*/ 1.0),
                point(3, 1.0,  /*Q*/ 1.0 + virialOffset, /*r50*/ r50Ratio)
        );
        List<JobReport.EscaperEvent> escapers = new ArrayList<>(nEscapers);
        for (int i = 0; i < nEscapers; i++) {
            escapers.add(new JobReport.EscaperEvent(i, 1.0, 100, 5.0, 1.0, 0.5));
        }
        return new JobReport(
                "job-test",
                "synthetic",
                nBodies,
                100,
                0.005,
                0.01,
                42L,
                timeline,
                List.of(),
                escapers,
                cons);
    }

    private JobReport baseReport(Map<String, Double> cons,
                                 double virialOffset,
                                 double r50Ratio,
                                 EscaperParams esc) {
        return baseReport(cons, virialOffset, r50Ratio, esc.nBodies, esc.nEscapers);
    }

    private JobReport reportWithTimeline(List<SnapshotPoint> timeline) {
        return new JobReport(
                "job-test", "synthetic", 100, 100, 0.005, 0.01, 42L,
                timeline,
                List.of(),
                List.of(),
                healthyConservation());
    }

    private JobReport reportWithConservation(Map<String, Double> cons) {
        return new JobReport(
                "job-test", "synthetic", 100, 100, 0.005, 0.01, 42L,
                List.of(point(0, 0.0, 1.0, 1.0), point(1, 1.0, 1.0, 1.0)),
                List.of(),
                List.of(),
                cons);
    }

    private static Map<String, Double> healthyConservation() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("dE_over_E_initial", 1e-5);
        m.put("worst_dE_over_E", 1e-5);
        m.put("dL_over_L_initial", 1e-15);
        return m;
    }

    private static SnapshotPoint point(long step, double t, double Q, double r50) {
        return new SnapshotPoint(t, step, 0.25, -0.5, -0.25,
                new double[]{0, 0, 0}, new double[]{0, 0, 0},
                Q, r50 * 0.5, r50, r50 * 2.0,
                0, 0);
    }

    private static SnapshotPoint virialPoint(long step, double Q) {
        return point(step, step * 0.1, Q, 1.0);
    }

    private static SnapshotPoint r50Point(long step, double r50) {
        return point(step, step * 1.0, 1.0, r50);
    }

    // ---- helpers for parameter packing ----
    private static double virialOffsetSecondHalf(double offset) { return offset; }
    private static double r50FinalOverInitial(double ratio)     { return ratio; }
    private static EscaperParams escaperFractionZero()          { return new EscaperParams(3000, 0); }
    private record EscaperParams(int nBodies, int nEscapers) {}

    // ---- assertion helpers ----
    private static ValidationCheck findCheck(ValidationReport v, String id) {
        return v.checks().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing check " + id));
    }

    private static void assertCheckSeverity(ValidationReport v, String id, String expected) {
        ValidationCheck c = findCheck(v, id);
        assertEquals(expected, c.severity(),
                () -> "check " + id + ": expected " + expected
                      + " but got " + c.severity()
                      + " (observed=" + c.observed()
                      + ", passThreshold=" + c.passThreshold()
                      + ", warnThreshold=" + c.warnThreshold() + ")");
    }

    private static String summarise(ValidationReport v) {
        StringBuilder sb = new StringBuilder("\n");
        for (var c : v.checks()) {
            sb.append("  ").append(c.id())
              .append(" → ").append(c.severity())
              .append(" (obs=").append(c.observed()).append(")\n");
        }
        return sb.toString();
    }
}
