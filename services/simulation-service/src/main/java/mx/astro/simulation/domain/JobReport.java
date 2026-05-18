package mx.astro.simulation.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Aggregated post-processing report for a finished batch job. Serialised to
 * {@code report.json} alongside the multi-snapshot HDF5, then consumed by both
 * the Python plot generator and the LaTeX/PDF renderer.
 *
 * <p>The shape mirrors what an astronomer would expect to see at the top of a
 * paper's "Methods" section: provenance fields, per-snapshot timeline, hard
 * binary catalog, escaper catalog, and global conservation metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobReport(
        String jobId,
        String scenario,
        int nBodies,
        int nSteps,
        double dt,
        double softening,
        long seed,

        /** Per-snapshot diagnostics over the run's timeline. */
        List<SnapshotPoint> timeline,

        /** Bound pairs detected across the run (one entry per (snapshot, pair)). */
        List<BinaryEvent> binaries,

        /** First-time-unbound events per body. */
        List<EscaperEvent> escapers,

        /** Global conservation: dE/E_initial, dL/L_initial, dt of the worst step. */
        Map<String, Double> conservation
) {
    public record SnapshotPoint(
            double simTime, long stepIndex,
            double K, double U, double E,
            double[] P, double[] L, double Q,
            double r10, double r50, double r90,
            int nBinaries, int nEscapers
    ) {}

    public record BinaryEvent(
            double simTime, long stepIndex,
            int bodyA, int bodyB,
            double separation,
            double semiMajorAxis,
            double eccentricity,
            double periodEstimate,
            /** Pair binding energy in Hénon units (E_pair < 0 for bound). */
            double bindingEnergy,
            /** Hard if |E_pair| > kT, where kT = mean K per body. */
            boolean hard
    ) {}

    public record EscaperEvent(
            int bodyIndex,
            double escapeTime,
            long escapeStepIndex,
            double escapeRadius,
            double escapeSpeed,
            /** Energy at escape time (positive = unbound). */
            double escapeEnergy
    ) {}
}
