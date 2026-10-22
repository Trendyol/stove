ALTER TABLE runs ADD COLUMN last_activity_at TEXT NOT NULL DEFAULT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"');
ALTER TABLE runs ADD COLUMN abandoned_at TEXT;
UPDATE runs SET last_activity_at = COALESCE(
  (SELECT to_char(MAX(created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"') FROM dashboard_event_inbox WHERE run_id = runs.id),
  last_activity_at);
CREATE INDEX idx_runs_idle ON runs(status, last_activity_at);
