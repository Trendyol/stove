//! SQL column lists and row→struct converters shared by the read and write
//! paths of the dashboard repository. Kept separate so neither the read impl
//! nor the write impl has to depend on the other's internals.

use crate::storage::models::Entry;
use crate::storage::models::Run;
use crate::storage::models::RunStatus;
use crate::storage::models::Snapshot;
use crate::storage::models::Span;
use crate::storage::models::Test;
use crate::storage::models::TestStatus;

pub(super) const RUN_COLUMNS: &str = "id, app_name, started_at, ended_at, status, total_tests, passed, failed, duration_ms, stove_version, systems";
pub(super) const SPAN_COLUMNS: &str = "id, run_id, trace_id, span_id, parent_span_id, operation_name, service_name, start_time_nanos, end_time_nanos, status, attributes, exception_type, exception_message, exception_stack_trace";

/// Convert empty strings to `None` for optional database fields.
pub(super) fn non_empty(s: &str) -> Option<&str> {
  if s.is_empty() { None } else { Some(s) }
}

/// Parse a `RunStatus` from a database string, defaulting to `Running`.
pub(super) fn parse_run_status(s: &str) -> RunStatus {
  s.parse().unwrap_or(RunStatus::Running)
}

/// Parse a `TestStatus` from a database string, defaulting to `Running`.
pub(super) fn parse_test_status(s: &str) -> TestStatus {
  s.parse().unwrap_or(TestStatus::Running)
}

pub(super) fn run_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Run> {
  let systems_json: String = row.get(10)?;
  let systems: Vec<String> = serde_json::from_str(&systems_json).unwrap_or_default();
  Ok(Run {
    id: row.get(0)?,
    app_name: row.get(1)?,
    started_at: row.get(2)?,
    ended_at: row.get(3)?,
    status: parse_run_status(&row.get::<_, String>(4)?),
    total_tests: row.get(5)?,
    passed: row.get(6)?,
    failed: row.get(7)?,
    duration_ms: row.get(8)?,
    stove_version: row.get(9)?,
    systems,
  })
}

pub(super) fn test_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Test> {
  let test_path_json: String = row.get(4)?;
  let test_path: Vec<String> = serde_json::from_str(&test_path_json).unwrap_or_default();
  Ok(Test {
    id: row.get(0)?,
    run_id: row.get(1)?,
    test_name: row.get(2)?,
    spec_name: row.get(3)?,
    test_path,
    started_at: row.get(5)?,
    ended_at: row.get(6)?,
    status: parse_test_status(&row.get::<_, String>(7)?),
    duration_ms: row.get(8)?,
    error: row.get(9)?,
  })
}

pub(super) fn entry_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Entry> {
  Ok(Entry {
    id: row.get(0)?,
    run_id: row.get(1)?,
    test_id: row.get(2)?,
    timestamp: row.get(3)?,
    system: row.get(4)?,
    action: row.get(5)?,
    result: parse_test_status(&row.get::<_, String>(6)?),
    input: row.get(7)?,
    output: row.get(8)?,
    metadata: row.get(9)?,
    expected: row.get(10)?,
    actual: row.get(11)?,
    error: row.get(12)?,
    trace_id: row.get(13)?,
  })
}

pub(super) fn snapshot_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Snapshot> {
  Ok(Snapshot {
    id: row.get(0)?,
    run_id: row.get(1)?,
    test_id: row.get(2)?,
    system: row.get(3)?,
    state_json: row.get(4)?,
    summary: row.get(5)?,
  })
}

pub(super) fn span_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Span> {
  Ok(Span {
    id: row.get(0)?,
    run_id: row.get(1)?,
    trace_id: row.get(2)?,
    span_id: row.get(3)?,
    parent_span_id: row.get(4)?,
    operation_name: row.get(5)?,
    service_name: row.get(6)?,
    start_time_nanos: row.get(7)?,
    end_time_nanos: row.get(8)?,
    status: row.get(9)?,
    attributes: row.get(10)?,
    exception_type: row.get(11)?,
    exception_message: row.get(12)?,
    exception_stack_trace: row.get(13)?,
  })
}
