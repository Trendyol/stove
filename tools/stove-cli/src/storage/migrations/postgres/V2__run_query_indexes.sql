CREATE INDEX IF NOT EXISTS idx_runs_app_started_at
    ON runs(app_name, started_at DESC, id DESC);

-- The composite index has the same app_name prefix and also supports the
-- dashboard's ordering, so retaining the narrower index only adds write cost
-- and can lead the planner to choose a plan that always requires a sort.
DROP INDEX IF EXISTS idx_runs_app_name;
