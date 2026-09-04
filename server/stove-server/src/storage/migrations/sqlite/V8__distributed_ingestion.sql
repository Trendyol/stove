ALTER TABLE entries ADD COLUMN correlation_key TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_entries_open_assertion
    ON entries(run_id, test_id, correlation_key, id DESC);

CREATE TABLE IF NOT EXISTS dashboard_event_inbox (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    sequence INTEGER,
    live_event_id INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (run_id, sequence),
    FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE,
    FOREIGN KEY (live_event_id) REFERENCES live_events(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS live_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_live_events_run_id_id ON live_events(run_id, id);
