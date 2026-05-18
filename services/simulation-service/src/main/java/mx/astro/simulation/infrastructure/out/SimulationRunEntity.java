package mx.astro.simulation.infrastructure.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Maps to {@code simulation_runs} (V1 migration). The {@code manifest}
 * column is JSONB on Postgres; we use {@link JdbcTypeCode} with
 * {@link SqlTypes#JSON} so Hibernate serializes/deserializes via Jackson.
 */
@Entity
@Table(name = "simulation_runs")
public class SimulationRunEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "mode", nullable = false)
    private String mode;

    @Column(name = "scenario_name")
    private String scenarioName;

    @Column(name = "hdf5_path")
    private String hdf5Path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> manifest;

    @Column(name = "error_message")
    private String errorMessage;

    protected SimulationRunEntity() {} // JPA

    public SimulationRunEntity(UUID id, Instant createdAt, String status, String mode,
                               Map<String, Object> manifest) {
        this.id = id;
        this.createdAt = createdAt;
        this.status = status;
        this.mode = mode;
        this.manifest = manifest;
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getStatus() { return status; }
    public String getMode() { return mode; }
    public String getScenarioName() { return scenarioName; }
    public String getHdf5Path() { return hdf5Path; }
    public Map<String, Object> getManifest() { return manifest; }
    public String getErrorMessage() { return errorMessage; }

    public void setStatus(String status) { this.status = status; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setHdf5Path(String hdf5Path) { this.hdf5Path = hdf5Path; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }
}
