CREATE TABLE live_event_retention (
  run_id TEXT PRIMARY KEY,
  deleted_through BIGINT NOT NULL
);
CREATE INDEX idx_live_event_retention_cursor ON live_event_retention(deleted_through);
INSERT INTO live_event_retention SELECT '', COALESCE(MAX(id), 0) FROM live_events;
-- Per-run markers avoid a global row lock in ingestion/retention transactions.
CREATE FUNCTION record_live_event_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO live_event_retention(run_id, deleted_through) VALUES (OLD.run_id, OLD.id)
    ON CONFLICT(run_id) DO UPDATE
    SET deleted_through = GREATEST(live_event_retention.deleted_through, excluded.deleted_through);
  RETURN OLD;
END;
$$;
CREATE TRIGGER record_live_event_deletion AFTER DELETE ON live_events
  FOR EACH ROW EXECUTE FUNCTION record_live_event_deletion();

CREATE INDEX idx_live_events_type_id ON live_events(event_type, id);

CREATE INDEX idx_tests_run_started_id ON tests(run_id, started_at, id);
