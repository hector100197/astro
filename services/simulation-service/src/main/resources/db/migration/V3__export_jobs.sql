-- V3: export_jobs
--
-- Headless / batch jobs requested via POST /api/jobs. Each row tracks the
-- lifecycle of a long-running simulation: queued → running → completed/failed.
-- The hdf5_path points at a multi-snapshot file (one .h5 with all timesteps),
-- distinct from the single-snapshot .h5 that live mode writes on stream stop.

CREATE TABLE export_jobs (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    status          TEXT         NOT NULL CHECK (status IN
                        ('queued','running','completed','failed','cancelled')),
    scenario_name   TEXT         NOT NULL,
    n_bodies        INTEGER      NOT NULL,
    n_steps         INTEGER      NOT NULL,
    dt              DOUBLE PRECISION NOT NULL,
    softening       DOUBLE PRECISION NOT NULL,
    seed            BIGINT       NOT NULL DEFAULT 42,
    snapshot_every  INTEGER      NOT NULL DEFAULT 100,
    progress_steps  INTEGER      NOT NULL DEFAULT 0,
    hdf5_path       TEXT,
    error_message   TEXT
);

CREATE INDEX idx_export_jobs_status   ON export_jobs(status);
CREATE INDEX idx_export_jobs_created  ON export_jobs(created_at DESC);
