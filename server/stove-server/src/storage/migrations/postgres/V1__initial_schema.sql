CREATE TABLE IF NOT EXISTS runs (
    id TEXT PRIMARY KEY,
    app_name TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    total_tests INTEGER NOT NULL DEFAULT 0,
    passed INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    systems TEXT NOT NULL DEFAULT '[]',
    stove_version TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT runs_metadata_is_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE TABLE IF NOT EXISTS tests (
    id TEXT NOT NULL,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    test_name TEXT NOT NULL,
    spec_name TEXT NOT NULL DEFAULT '',
    test_path TEXT NOT NULL DEFAULT '[]',
    started_at TEXT NOT NULL,
    ended_at TEXT,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    duration_ms BIGINT,
    error TEXT,
    PRIMARY KEY (run_id, id)
);

CREATE TABLE IF NOT EXISTS entries (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
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
    assertion_id TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS spans (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    trace_id TEXT NOT NULL,
    span_id TEXT NOT NULL,
    parent_span_id TEXT,
    operation_name TEXT NOT NULL,
    service_name TEXT NOT NULL,
    start_time_nanos BIGINT NOT NULL,
    end_time_nanos BIGINT NOT NULL,
    status TEXT NOT NULL,
    attributes TEXT,
    exception_type TEXT,
    exception_message TEXT,
    exception_stack_trace TEXT
);

CREATE TABLE IF NOT EXISTS snapshots (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    test_id TEXT NOT NULL,
    system TEXT NOT NULL,
    state_json TEXT NOT NULL,
    summary TEXT NOT NULL,
    captured_at TEXT,
    trigger_kind TEXT NOT NULL DEFAULT 'TEST_END'
);

CREATE TABLE IF NOT EXISTS mock_interactions (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    test_id TEXT,
    timestamp TEXT NOT NULL,
    system TEXT NOT NULL,
    protocol TEXT NOT NULL,
    method TEXT NOT NULL,
    target TEXT NOT NULL,
    matched BOOLEAN NOT NULL,
    stub_id TEXT,
    attribution TEXT NOT NULL,
    request_body TEXT,
    request_body_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    response_body TEXT,
    response_body_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL,
    latency_ms BIGINT,
    near_misses TEXT,
    trace_id TEXT,
    scenario_name TEXT,
    scenario_state TEXT,
    next_scenario_state TEXT,
    configured_delay_ms BIGINT,
    fault TEXT,
    client_deadline_ms BIGINT
);

CREATE TABLE IF NOT EXISTS mock_warnings (
    id BIGSERIAL PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    test_id TEXT,
    timestamp TEXT NOT NULL,
    system TEXT NOT NULL,
    kind TEXT NOT NULL,
    message TEXT NOT NULL,
    stub_id TEXT,
    target TEXT
);

CREATE INDEX IF NOT EXISTS idx_tests_run_id ON tests(run_id);
CREATE INDEX IF NOT EXISTS idx_entries_run_test ON entries(run_id, test_id);
CREATE INDEX IF NOT EXISTS idx_entries_run_test_assertion ON entries(run_id, test_id, assertion_id);
CREATE INDEX IF NOT EXISTS idx_spans_run_id ON spans(run_id);
CREATE INDEX IF NOT EXISTS idx_spans_trace_id ON spans(trace_id);
CREATE INDEX IF NOT EXISTS idx_snapshots_run_test ON snapshots(run_id, test_id);
CREATE INDEX IF NOT EXISTS idx_runs_app_name ON runs(app_name);
CREATE INDEX IF NOT EXISTS idx_runs_metadata ON runs USING GIN (metadata jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_mock_interactions_run_test ON mock_interactions(run_id, test_id);
CREATE INDEX IF NOT EXISTS idx_mock_warnings_run_test ON mock_warnings(run_id, test_id);
