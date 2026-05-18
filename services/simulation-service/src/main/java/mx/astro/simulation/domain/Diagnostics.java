package mx.astro.simulation.domain;

/**
 * Per-snapshot physical diagnostics.
 *
 * <ul>
 *   <li>{@code kineticEnergy}    — K = ½ Σ mᵢ vᵢ²</li>
 *   <li>{@code potentialEnergy}  — U = -G Σᵢ Σⱼ&gt;ᵢ mᵢmⱼ / |rᵢ - rⱼ|</li>
 *   <li>{@code totalEnergy}      — E = K + U (must drift &lt; 0.1% over 10⁴ steps for leapfrog)</li>
 *   <li>{@code momentum}         — P = Σ mᵢ vᵢ (Newtonian conservation: dP/dt = 0)</li>
 *   <li>{@code angularL}         — L = Σ mᵢ rᵢ × vᵢ (Newtonian conservation: dL/dt = 0)</li>
 *   <li>{@code virialRatio}      — Q = 2K / |U| (~1 for relaxed bound system)</li>
 * </ul>
 *
 * Domain layer — no infrastructure imports. The serialized form lives in
 * {@code shared-contracts/DiagnosticsDto}.
 */
public record Diagnostics(
        double simTime,
        long stepIndex,
        double kineticEnergy,
        double potentialEnergy,
        double totalEnergy,
        double[] momentum,
        double[] angularL,
        double virialRatio,
        /**
         * Lagrangian radii — distance from the centre-of-mass enclosing the
         * given mass fraction. Standard cluster analysis: r10 = inner core,
         * r50 = half-mass radius (canonical size scale), r90 = outer halo.
         * Zero when computed without positions (mock profile).
         */
        double r10,
        double r50,
        double r90
) {}
