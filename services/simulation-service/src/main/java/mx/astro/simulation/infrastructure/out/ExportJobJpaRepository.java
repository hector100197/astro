package mx.astro.simulation.infrastructure.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExportJobJpaRepository extends JpaRepository<ExportJobEntity, UUID> {
    Page<ExportJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
