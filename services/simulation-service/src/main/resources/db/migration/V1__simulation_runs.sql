-- V1: simulation_runs
--
-- One row per live or headless simulation run. The `manifest` column carries
-- the full reproducibility envelope (kernel SHA, compiler flags, hardware,
-- parameters, scenario hash) as JSONB so we can index parts of it later
-- (e.g. "find all runs at dt < 0.001") without schema migrations.
--
-- Status transitions: queued → running → (paused ↔ running)
--                                       ↓
--                            (completed | stopped | failed)

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE simulation_runs (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    status          TEXT         NOT NULL CHECK (status IN
                        ('queued','running','paused','completed','stopped','failed')),
    mode            TEXT         NOT NULL CHECK (mode IN ('live','headless')),
    scenario_name   TEXT,
    hdf5_path       TEXT,
    manifest        JSONB        NOT NULL,
    error_message   TEXT
);

CREATE INDEX idx_simulation_runs_status   ON simulation_runs(status);
CREATE INDEX idx_simulation_runs_created  ON simulation_runs(created_at DESC);
CREATE INDEX idx_simulation_runs_manifest ON simulation_runs USING GIN (manifest);
