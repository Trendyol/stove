CREATE INDEX idx_runs_started_at_id ON runs(started_at DESC, id DESC);
CREATE INDEX idx_tests_run_started_at_id ON tests(run_id, started_at, id);
