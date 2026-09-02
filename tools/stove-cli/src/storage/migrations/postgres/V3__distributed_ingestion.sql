ALTER TABLE entries ADD COLUMN correlation_key TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_entries_open_assertion
    ON entries(run_id, test_id, correlation_key, id DESC);

CREATE TABLE IF NOT EXISTS dashboard_settings (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS live_events (
    id BIGSERIAL PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_live_events_run_id_id ON live_events(run_id, id);

CREATE TABLE IF NOT EXISTS dashboard_event_inbox (
    event_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    sequence BIGINT,
    live_event_id BIGINT NOT NULL REFERENCES live_events(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_dashboard_event_inbox_run_sequence
    ON dashboard_event_inbox(run_id, sequence DESC);
