CREATE INDEX idx_runs_started_at_id ON runs(started_at DESC, id DESC);
CREATE INDEX idx_runs_app_started_at ON runs(app_name, started_at DESC, id DESC);
DROP INDEX IF EXISTS idx_runs_app_name;
CREATE INDEX idx_tests_run_started_at_id ON tests(run_id, started_at, id);
