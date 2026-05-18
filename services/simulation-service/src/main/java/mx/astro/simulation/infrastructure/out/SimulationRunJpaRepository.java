package mx.astro.simulation.infrastructure.out;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SimulationRunJpaRepository extends JpaRepository<SimulationRunEntity, UUID> {
}
