-- Mock exchange capture (the network-tab data) and mock warnings.
-- test_id is NULL for unattributed evidence: attribution is proven-only,
-- so the UI renders those in a run-level lane instead of guessing.
CREATE TABLE IF NOT EXISTS mock_interactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    test_id TEXT,
    timestamp TEXT NOT NULL,
    system TEXT NOT NULL,
    protocol TEXT NOT NULL,
    method TEXT NOT NULL,
    target TEXT NOT NULL,
    matched INTEGER NOT NULL,
    stub_id TEXT,
    attribution TEXT NOT NULL,
    request_body TEXT,
    request_body_truncated INTEGER NOT NULL DEFAULT 0,
    response_body TEXT,
    response_body_truncated INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    latency_ms INTEGER,
    near_misses TEXT,
    trace_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_mock_interactions_run_test ON mock_interactions(run_id, test_id);

CREATE TABLE IF NOT EXISTS mock_warnings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    test_id TEXT,
    timestamp TEXT NOT NULL,
    system TEXT NOT NULL,
    kind TEXT NOT NULL,
    message TEXT NOT NULL,
    stub_id TEXT,
    target TEXT
);
CREATE INDEX IF NOT EXISTS idx_mock_warnings_run_test ON mock_warnings(run_id, test_id);

-- Snapshots now carry when they were captured and why (TEST_END vs FAILURE).
ALTER TABLE snapshots ADD COLUMN captured_at TEXT;
ALTER TABLE snapshots ADD COLUMN trigger_kind TEXT NOT NULL DEFAULT 'TEST_END';
