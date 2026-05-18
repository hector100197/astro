package mx.astro.simulation.domain;

/**
 * Immutable point-mass body in the simulation.
 * Domain layer — no infrastructure imports.
 */
public record Body(
        double x, double y, double z,
        double vx, double vy, double vz,
        double mass
) {}
