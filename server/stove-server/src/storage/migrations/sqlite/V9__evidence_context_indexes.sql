-- Exact evidence context follows timestamp/ID order and parent identities.
CREATE INDEX IF NOT EXISTS idx_entries_context ON entries(run_id, test_id, timestamp, id);
CREATE INDEX IF NOT EXISTS idx_spans_parent_lookup ON spans(run_id, trace_id, span_id, id);
