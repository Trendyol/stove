CREATE TABLE IF NOT EXISTS runs (
    id TEXT PRIMARY KEY,
    app_name TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    total_tests INTEGER NOT NULL DEFAULT 0,
    passed INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER,
    systems TEXT NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS tests (
    id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    test_name TEXT NOT NULL,
    spec_name TEXT NOT NULL DEFAULT '',
    started_at TEXT NOT NULL,
    ended_at TEXT,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    duration_ms INTEGER,
    error TEXT,
    PRIMARY KEY (run_id, id),
    FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE TABLE IF NOT EXISTS entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    test_id TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    system TEXT NOT NULL,
    action TEXT NOT NULL,
    result TEXT NOT NULL,
    input TEXT,
    output TEXT,
    metadata TEXT,
    expected TEXT,
    actual TEXT,
    error TEXT,
    trace_id TEXT,
    FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE TABLE IF NOT EXISTS spans (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    trace_id TEXT NOT NULL,
    span_id TEXT NOT NULL,
    parent_span_id TEXT,
    operation_name TEXT NOT NULL,
    service_name TEXT NOT NULL,
    start_time_nanos INTEGER NOT NULL,
    end_time_nanos INTEGER NOT NULL,
    status TEXT NOT NULL,
    attributes TEXT,
    exception_type TEXT,
    exception_message TEXT,
    exception_stack_trace TEXT,
    FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE TABLE IF NOT EXISTS snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    test_id TEXT NOT NULL,
    system TEXT NOT NULL,
    state_json TEXT NOT NULL,
    summary TEXT NOT NULL,
    FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE INDEX IF NOT EXISTS idx_tests_run_id ON tests(run_id);
CREATE INDEX IF NOT EXISTS idx_entries_run_test ON entries(run_id, test_id);
CREATE INDEX IF NOT EXISTS idx_spans_run_id ON spans(run_id);
CREATE INDEX IF NOT EXISTS idx_spans_trace_id ON spans(trace_id);
CREATE INDEX IF NOT EXISTS idx_snapshots_run_test ON snapshots(run_id, test_id);
CREATE INDEX IF NOT EXISTS idx_runs_app_name ON runs(app_name);
