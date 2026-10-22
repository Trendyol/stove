ALTER TABLE runs ADD COLUMN last_activity_at TEXT NOT NULL DEFAULT '';
ALTER TABLE runs ADD COLUMN abandoned_at TEXT;
UPDATE runs SET last_activity_at = COALESCE(
  (SELECT strftime('%Y-%m-%dT%H:%M:%fZ', MAX(created_at)) FROM dashboard_event_inbox WHERE run_id = runs.id),
  strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
CREATE INDEX idx_runs_idle ON runs(status, last_activity_at);
CREATE TABLE dashboard_settings (
  setting_key TEXT PRIMARY KEY,
  setting_value TEXT NOT NULL
);
