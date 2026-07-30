ALTER TABLE entries ADD COLUMN assertion_id TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_entries_run_test_assertion
    ON entries(run_id, test_id, assertion_id);
