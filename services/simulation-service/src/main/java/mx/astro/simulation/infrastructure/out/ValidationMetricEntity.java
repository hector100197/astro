package mx.astro.simulation.infrastructure.out;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps to {@code validation_metrics} (V2 migration). Composite PK on
 * (run_id, step_index) modeled with {@link IdClass}.
 */
@Entity
@Table(name = "validation_metrics")
@IdClass(ValidationMetricEntity.PK.class)
public class ValidationMetricEntity {

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Id
    @Column(name = "step_index", nullable = false)
    private long stepIndex;

    @Column(name = "sim_time", nullable = false)
    private double simTime;

    @Column(name = "kinetic_energy", nullable = false)
    private double kineticEnergy;

    @Column(name = "potential_energy", nullable = false)
    private double potentialEnergy;

    @Column(name = "total_energy", nullable = false)
    private double totalEnergy;

    @Column(name = "momentum_x", nullable = false)
    private double momentumX;
    @Column(name = "momentum_y", nullable = false)
    private double momentumY;
    @Column(name = "momentum_z", nullable = false)
    private double momentumZ;

    @Column(name = "angular_l_x", nullable = false)
    private double angularLX;
    @Column(name = "angular_l_y", nullable = false)
    private double angularLY;
    @Column(name = "angular_l_z", nullable = false)
    private double angularLZ;

    @Column(name = "virial_ratio")
    private Double virialRatio;

    protected ValidationMetricEntity() {} // JPA

    public ValidationMetricEntity(
            UUID runId, long stepIndex, double simTime,
            double K, double U, double E,
            double Px, double Py, double Pz,
            double Lx, double Ly, double Lz,
            Double Q
    ) {
        this.runId = runId;
        this.stepIndex = stepIndex;
        this.simTime = simTime;
        this.kineticEnergy = K;
        this.potentialEnergy = U;
        this.totalEnergy = E;
        this.momentumX = Px; this.momentumY = Py; this.momentumZ = Pz;
        this.angularLX = Lx; this.angularLY = Ly; this.angularLZ = Lz;
        this.virialRatio = Q;
    }

    public UUID getRunId() { return runId; }
    public long getStepIndex() { return stepIndex; }

    /** Composite PK class — required by JPA's IdClass strategy. */
    public static class PK implements Serializable {
        private static final long serialVersionUID = 1L;
        private UUID runId;
        private long stepIndex;

        public PK() {}
        public PK(UUID runId, long stepIndex) {
            this.runId = runId;
            this.stepIndex = stepIndex;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK p)) return false;
            return stepIndex == p.stepIndex && Objects.equals(runId, p.runId);
        }

        @Override public int hashCode() { return Objects.hash(runId, stepIndex); }
    }
}
