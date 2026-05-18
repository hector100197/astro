package mx.astro.simulation.infrastructure.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps to {@code export_jobs} (V3 migration). */
@Entity
@Table(name = "export_jobs")
public class ExportJobEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "scenario_name", nullable = false)
    private String scenarioName;

    @Column(name = "n_bodies", nullable = false)
    private int nBodies;

    @Column(name = "n_steps", nullable = false)
    private int nSteps;

    @Column(name = "dt", nullable = false)
    private double dt;

    @Column(name = "softening", nullable = false)
    private double softening;

    @Column(name = "seed", nullable = false)
    private long seed;

    @Column(name = "snapshot_every", nullable = false)
    private int snapshotEvery;

    @Column(name = "progress_steps", nullable = false)
    private int progressSteps;

    @Column(name = "hdf5_path")
    private String hdf5Path;

    @Column(name = "report_dir")
    private String reportDir;

    @Column(name = "validation_json")
    private String validationJson;

    @Column(name = "validation_verdict")
    private String validationVerdict;

    @Column(name = "error_message")
    private String errorMessage;

    protected ExportJobEntity() {} // JPA

    public ExportJobEntity(UUID id, Instant createdAt, String status,
                           String scenarioName, int nBodies, int nSteps,
                           double dt, double softening, long seed, int snapshotEvery) {
        this.id = id;
        this.createdAt = createdAt;
        this.status = status;
        this.scenarioName = scenarioName;
        this.nBodies = nBodies;
        this.nSteps = nSteps;
        this.dt = dt;
        this.softening = softening;
        this.seed = seed;
        this.snapshotEvery = snapshotEvery;
        this.progressSteps = 0;
    }

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getStatus() { return status; }
    public String getScenarioName() { return scenarioName; }
    public int getNBodies() { return nBodies; }
    public int getNSteps() { return nSteps; }
    public double getDt() { return dt; }
    public double getSoftening() { return softening; }
    public long getSeed() { return seed; }
    public int getSnapshotEvery() { return snapshotEvery; }
    public int getProgressSteps() { return progressSteps; }
    public String getHdf5Path() { return hdf5Path; }
    public String getReportDir() { return reportDir; }
    public String getValidationJson() { return validationJson; }
    public String getValidationVerdict() { return validationVerdict; }
    public String getErrorMessage() { return errorMessage; }

    public void setStatus(String s) { this.status = s; }
    public void setStartedAt(Instant t) { this.startedAt = t; }
    public void setFinishedAt(Instant t) { this.finishedAt = t; }
    public void setProgressSteps(int p) { this.progressSteps = p; }
    public void setHdf5Path(String p) { this.hdf5Path = p; }
    public void setReportDir(String d) { this.reportDir = d; }
    public void setValidationJson(String j) { this.validationJson = j; }
    public void setValidationVerdict(String v) { this.validationVerdict = v; }
    public void setErrorMessage(String m) { this.errorMessage = m; }
}
