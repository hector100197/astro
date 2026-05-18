-- V5: validation
--
-- Each completed job runs through a fixed catalog of physical-consistency
-- checks (energy/momentum conservation, virial equilibrium, half-mass radius
-- drift, escaper fraction). We persist:
--   * validation_json    — full ValidationReport as JSON (rich detail for UI)
--   * validation_verdict — denormalised top-level verdict ("pass"/"warn"/"fail")
--     so the list endpoint can colour-code badges without parsing JSON.
--
-- Both nullable: failed runs and pre-V5 jobs have neither.

ALTER TABLE export_jobs
    ADD COLUMN validation_json    TEXT,
    ADD COLUMN validation_verdict TEXT;
