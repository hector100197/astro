-- astro initial schema
-- Loaded once on docker-compose first start.
-- Subsequent migrations live in services/*/src/main/resources/db/migration via Flyway.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Each simulation run, regardless of mode (live, capture, headless).
CREATE TABLE IF NOT EXISTS simulation_runs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          TEXT NOT NULL CHECK (status IN ('queued','running','paused','completed','stopped','failed')),
    mode            TEXT NOT NULL CHECK (mode IN ('live','headless')),
    scenario_name   TEXT,
    hdf5_path       TEXT,
    manifest        JSONB NOT NULL,
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_simulation_runs_status ON simulation_runs(status);
CREATE INDEX IF NOT EXISTS idx_simulation_runs_created ON simulation_runs(created_at DESC);

-- Run parameters as flat key-value (the manifest already has structured form;
-- this table is for fast filtering / search).
CREATE TABLE IF NOT EXISTS parameters (
    run_id          UUID NOT NULL REFERENCES simulation_runs(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    value           TEXT NOT NULL,
    PRIMARY KEY (run_id, name)
);

-- Time series of validation diagnostics per snapshot.
CREATE TABLE IF NOT EXISTS validation_metrics (
    run_id           UUID NOT NULL REFERENCES simulation_runs(id) ON DELETE CASCADE,
    step_index       BIGINT NOT NULL,
    sim_time         DOUBLE PRECISION NOT NULL,
    kinetic_energy   DOUBLE PRECISION NOT NULL,
    potential_energy DOUBLE PRECISION NOT NULL,
    total_energy     DOUBLE PRECISION NOT NULL,
    momentum_x       DOUBLE PRECISION NOT NULL,
    momentum_y       DOUBLE PRECISION NOT NULL,
    momentum_z       DOUBLE PRECISION NOT NULL,
    angular_l_x      DOUBLE PRECISION NOT NULL,
    angular_l_y      DOUBLE PRECISION NOT NULL,
    angular_l_z      DOUBLE PRECISION NOT NULL,
    virial_ratio     DOUBLE PRECISION,
    PRIMARY KEY (run_id, step_index)
);

-- Boolean test outcomes (Kepler, energy conservation, virial).
CREATE TABLE IF NOT EXISTS validation_tests (
    run_id          UUID NOT NULL REFERENCES simulation_runs(id) ON DELETE CASCADE,
    test_name       TEXT NOT NULL,
    passed          BOOLEAN NOT NULL,
    measured_value  DOUBLE PRECISION,
    threshold       DOUBLE PRECISION,
    notes           TEXT,
    PRIMARY KEY (run_id, test_name)
);

-- Headless export jobs (export-service domain).
CREATE TABLE IF NOT EXISTS export_jobs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id          UUID REFERENCES simulation_runs(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          TEXT NOT NULL CHECK (status IN ('queued','running','completed','failed','cancelled')),
    config          JSONB NOT NULL,
    output_path     TEXT,
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_export_jobs_status ON export_jobs(status);
