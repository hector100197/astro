package mx.astro.simulation.infrastructure;

import mx.astro.simulation.domain.DiagnosticsCalculator;
import mx.astro.simulation.domain.InitialCondition;
import mx.astro.simulation.domain.Integrator;
import mx.astro.simulation.infrastructure.out.CircularOrbitIntegrator;
import mx.astro.simulation.infrastructure.out.MockDiagnosticsCalculator;
import mx.astro.simulation.infrastructure.out.UniformDiskInitialCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the V1 mock Strategy beans for the {@code mock} profile only.
 *
 * <p>The default profile (no {@code mock} active) uses the Fortran-backed
 * adapters discovered via {@code @Component} scanning:
 * {@link mx.astro.simulation.infrastructure.out.FortranInitialCondition},
 * {@link mx.astro.simulation.infrastructure.out.FortranIntegrator},
 * {@link mx.astro.simulation.infrastructure.out.RealDiagnosticsCalculator}.
 *
 * <p>The mock profile is for quick verification of the streaming pipeline
 * without needing the kernel built; the default profile exercises the
 * real Fortran kernel and physics.
 */
@Configuration
@Profile("mock")
public class SimulationBeansConfig {

    @Bean
    public InitialCondition initialCondition() {
        return new UniformDiskInitialCondition();
    }

    @Bean
    public Integrator integrator() {
        return new CircularOrbitIntegrator();
    }

    @Bean
    public DiagnosticsCalculator diagnosticsCalculator() {
        return new MockDiagnosticsCalculator();
    }
}
