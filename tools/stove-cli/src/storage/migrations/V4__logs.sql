CREATE TABLE IF NOT EXISTS logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id TEXT NOT NULL,
    test_id TEXT,
    trace_id TEXT,
    span_id TEXT,
    timestamp TEXT NOT NULL,
    observed_timestamp TEXT NOT NULL,
    severity_text TEXT NOT NULL,
    severity_number INTEGER NOT NULL,
    logger TEXT NOT NULL,
    thread TEXT NOT NULL,
    body TEXT NOT NULL,
    exception_type TEXT,
    exception_message TEXT,
    exception_stack_trace TEXT,
    attributes TEXT,
    correlation_source TEXT NOT NULL,
    source TEXT NOT NULL,
    scope TEXT NOT NULL DEFAULT 'RUN',
    late INTEGER NOT NULL DEFAULT 0,
    truncated INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE INDEX IF NOT EXISTS idx_logs_run_test_timestamp ON logs(run_id, test_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_logs_run_trace ON logs(run_id, trace_id);
CREATE INDEX IF NOT EXISTS idx_logs_run_level ON logs(run_id, severity_number);
CREATE INDEX IF NOT EXISTS idx_logs_run_logger ON logs(run_id, logger);
CREATE INDEX IF NOT EXISTS idx_logs_run_scope ON logs(run_id, scope);
