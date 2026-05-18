-- V4: report_path
--
-- After an export job completes successfully, a post-processing pipeline
-- (binary detection, escaper detection, plots, LaTeX/PDF rendering) writes
-- a self-contained report bundle into <jobId>-report/. This column stores
-- the absolute path to that directory so the controller can serve any of
-- the artifacts (report.json, report.tex, report.pdf, plots).
--
-- Nullable because (a) older jobs predate the pipeline and (b) failed jobs
-- never get a report.

ALTER TABLE export_jobs
    ADD COLUMN report_dir TEXT;
