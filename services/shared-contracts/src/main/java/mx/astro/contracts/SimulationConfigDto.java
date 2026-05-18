package mx.astro.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire-format DTO for simulation configuration. Shared between simulation-service
 * and export-service so both speak the same vocabulary.
 *
 * Hexagonal note: this lives outside any service's domain. Each service maps
 * this DTO to its own internal {@code SimulationConfig} value object.
 */
public record SimulationConfigDto(
        @JsonProperty("n_bodies") int nBodies,
        @JsonProperty("dt") double dt,
        @JsonProperty("softening") double softening,
        @JsonProperty("n_steps") int nSteps,
        @JsonProperty("integrator") String integrator,
        @JsonProperty("force_calculator") String forceCalculator,
        @JsonProperty("initial_condition") String initialCondition,
        @JsonProperty("rng_seed") long rngSeed
) {
    public SimulationConfigDto {
        if (nBodies <= 0)   throw new IllegalArgumentException("nBodies must be positive");
        if (dt <= 0.0)      throw new IllegalArgumentException("dt must be positive");
        if (softening <= 0) throw new IllegalArgumentException("softening must be positive");
        if (nSteps <= 0)    throw new IllegalArgumentException("nSteps must be positive");
    }
}
