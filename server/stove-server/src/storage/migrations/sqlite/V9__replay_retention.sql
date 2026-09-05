-- Keep deletion knowledge after a run and its live events have been pruned.
CREATE TABLE live_event_retention (
  run_id TEXT PRIMARY KEY,
  deleted_through INTEGER NOT NULL
);
CREATE INDEX idx_live_event_retention_cursor ON live_event_retention(deleted_through);
-- Pre-upgrade deletion history is unknown: older cursors must resynchronize once.
INSERT INTO live_event_retention SELECT '', COALESCE(MAX(id), 0) FROM live_events;
CREATE TRIGGER record_live_event_deletion AFTER DELETE ON live_events
BEGIN
  INSERT INTO live_event_retention(run_id, deleted_through) VALUES (OLD.run_id, OLD.id)
    ON CONFLICT(run_id) DO UPDATE SET deleted_through = MAX(deleted_through, excluded.deleted_through);
END;

CREATE INDEX idx_live_events_type_id ON live_events(event_type, id);

CREATE INDEX idx_tests_run_started_id ON tests(run_id, started_at, id);
