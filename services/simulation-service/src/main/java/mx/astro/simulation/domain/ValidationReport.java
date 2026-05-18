package mx.astro.simulation.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Quality assessment of a finished simulation run against accepted stellar
 * dynamics tolerances. Mirrors the kind of internal consistency table that
 * tools like NBODY6 print at the end of a job ({@code dE/E}, virial state,
 * Lagrangian radius drift, escaper count) so that a user can see at a glance
 * whether the run is publication-grade, suspicious, or broken.
 *
 * <p>{@code verdict} is the aggregate:
 * <ul>
 *   <li>{@code "pass"} — every check is within the strict (NBODY6-grade) band</li>
 *   <li>{@code "warn"} — no critical failures, but at least one check is in the warning band</li>
 *   <li>{@code "fail"} — at least one check exceeds the failure threshold</li>
 * </ul>
 *
 * <p>{@code checks} is the per-criterion breakdown the UI uses to render the
 * expanded details panel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationReport(
        String verdict,
        String summary,
        List<ValidationCheck> checks
) {
    public record ValidationCheck(
            String id,
            String label,
            String severity,
            double observed,
            double passThreshold,
            double warnThreshold,
            String unit,
            String message
    ) {}
}
