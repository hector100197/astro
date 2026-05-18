package mx.astro.simulation.infrastructure.out;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationMetricJpaRepository
        extends JpaRepository<ValidationMetricEntity, ValidationMetricEntity.PK> {
}
