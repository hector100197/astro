package mx.astro.simulation.application;

import mx.astro.contracts.SimulationConfigDto;

/**
 * Use case: start a new simulation run.
 *
 * TODO Sem 2-3: implement orchestration:
 *   1. Map DTO → domain SimulationConfig
 *   2. SimulationFactory creates Integrator + ForceCalculator + InitialCondition
 *   3. KernelPool acquires a Fortran context
 *   4. Persist run metadata to RunRepository
 *   5. Start step loop, publish snapshots via SnapshotPublisher port
 */
public interface RunSimulation {
    String start(SimulationConfigDto config);
}
