-- Deleting live events cascades into ingestion receipts once per event. Without
-- this foreign-key index, each cascade scans the entire receipts table and can
-- exceed the idle sweep's statement timeout even for a modest-sized run.
CREATE INDEX IF NOT EXISTS idx_dashboard_event_inbox_live_event_id
    ON dashboard_event_inbox(live_event_id);
