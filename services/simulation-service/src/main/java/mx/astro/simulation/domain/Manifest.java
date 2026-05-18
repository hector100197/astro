package mx.astro.simulation.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Reproducibility manifest for a simulation run.
 *
 * <p>The manifest captures everything required to re-run a past simulation
 * bit-exactly (or almost — see {@code reproducibility.md} for the dimensions
 * that bound exactness). It is serialized as JSON and persisted both inside
 * the HDF5 output's {@code /Header/Manifest} group and in the
 * {@code simulation_runs.manifest} column of Postgres.
 *
 * <p>Field groups:
 * <ul>
 *   <li><strong>kernel</strong>: git SHA + binary SHA-256 + compiler info</li>
 *   <li><strong>scenario</strong>: source (named YAML or "custom") + content hash</li>
 *   <li><strong>parameters</strong>: N, Δt, ε, integrator, force calculator,
 *       initial condition, RNG seed, units</li>
 *   <li><strong>hardware</strong>: CPU, cores, RAM, OS</li>
 *   <li><strong>software</strong>: JDK version, Spring Boot version, service git SHA</li>
 * </ul>
 *
 * <p>Domain layer — pure data, no infrastructure imports.
 */
public record Manifest(
        UUID runId,
        Instant createdAt,
        Map<String, Object> kernel,
        Map<String, Object> scenario,
        Map<String, Object> parameters,
        Map<String, Object> hardware,
        Map<String, Object> software
) {}
