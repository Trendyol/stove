ALTER TABLE runs ADD COLUMN last_activity_at TEXT NOT NULL DEFAULT '';
UPDATE runs SET last_activity_at = COALESCE(
  (SELECT strftime('%Y-%m-%dT%H:%M:%fZ', MAX(created_at)) FROM dashboard_event_inbox WHERE run_id = runs.id),
  strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
CREATE INDEX idx_runs_idle ON runs(status, last_activity_at);
CREATE TABLE dashboard_settings (
  setting_key TEXT PRIMARY KEY,
  setting_value TEXT NOT NULL
);

-- SQLite requires rebuilding the outbox to allow parentless deletion notifications.
-- Preserve receipts and the high-water mark even if all old events were deleted.
CREATE TEMP TABLE saved_inbox AS SELECT * FROM dashboard_event_inbox;
CREATE TEMP TABLE saved_events AS SELECT * FROM live_events;
CREATE TEMP TABLE saved_event_sequence AS SELECT seq FROM sqlite_sequence WHERE name = 'live_events';
DROP TABLE dashboard_event_inbox;
DROP TABLE live_events;
CREATE TABLE live_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    run_id TEXT,
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
);
INSERT INTO live_events SELECT * FROM saved_events;
UPDATE sqlite_sequence SET seq = MAX(seq, COALESCE((SELECT seq FROM saved_event_sequence), 0))
    WHERE name = 'live_events';
INSERT INTO sqlite_sequence(name, seq)
    SELECT 'live_events', seq FROM saved_event_sequence
    WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'live_events');
CREATE INDEX idx_live_events_run_id_id ON live_events(run_id, id);
CREATE TABLE dashboard_event_inbox (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    sequence INTEGER,
    live_event_id INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (run_id, sequence),
    FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE,
    FOREIGN KEY (live_event_id) REFERENCES live_events(id) ON DELETE CASCADE
);
INSERT INTO dashboard_event_inbox SELECT * FROM saved_inbox;
DROP TABLE saved_inbox;
DROP TABLE saved_events;
DROP TABLE saved_event_sequence;
