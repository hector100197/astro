-- V2: validation_metrics
--
-- Time series of physics diagnostics, sampled from the live snapshot stream
-- at a configurable rate (default 5 Hz; the streaming runs at 60 Hz but we
-- decimate before persisting to keep DB write rate low).
--
-- Composite PK on (run_id, step_index) is fine because step_index is a
-- monotonically increasing integer per run.

CREATE TABLE validation_metrics (
    run_id            UUID              NOT NULL REFERENCES simulation_runs(id) ON DELETE CASCADE,
    step_index        BIGINT            NOT NULL,
    sim_time          DOUBLE PRECISION  NOT NULL,
    kinetic_energy    DOUBLE PRECISION  NOT NULL,
    potential_energy  DOUBLE PRECISION  NOT NULL,
    total_energy      DOUBLE PRECISION  NOT NULL,
    momentum_x        DOUBLE PRECISION  NOT NULL,
    momentum_y        DOUBLE PRECISION  NOT NULL,
    momentum_z        DOUBLE PRECISION  NOT NULL,
    angular_l_x       DOUBLE PRECISION  NOT NULL,
    angular_l_y       DOUBLE PRECISION  NOT NULL,
    angular_l_z       DOUBLE PRECISION  NOT NULL,
    virial_ratio      DOUBLE PRECISION,
    PRIMARY KEY (run_id, step_index)
);

-- Reverse index for time-based queries (e.g. "energy of run X over the last 60s").
CREATE INDEX idx_validation_metrics_runtime ON validation_metrics(run_id, sim_time);
