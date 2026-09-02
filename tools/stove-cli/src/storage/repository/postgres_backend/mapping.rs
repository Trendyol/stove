use postgres::Row;

use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, Run, RunStatus, Snapshot, Span, Test, TestStatus,
};

pub(super) fn app_summary_from_row(row: &Row) -> AppSummary {
  AppSummary {
    app_name: row.get(0),
    latest_run_id: row.get(1),
    latest_status: parse_run_status(row.get::<_, String>(2).as_str()),
    stove_version: row.get(3),
    metadata: parse_json(row.get::<_, String>(4).as_str()),
  }
}

pub(super) fn run_from_row(row: &Row) -> Run {
  Run {
    id: row.get(0),
    app_name: row.get(1),
    started_at: row.get(2),
    ended_at: row.get(3),
    status: parse_run_status(row.get::<_, String>(4).as_str()),
    total_tests: row.get(5),
    passed: row.get(6),
    failed: row.get(7),
    duration_ms: row.get(8),
    stove_version: row.get(9),
    systems: parse_json(row.get::<_, String>(10).as_str()),
    metadata: parse_json(row.get::<_, String>(11).as_str()),
  }
}

pub(super) fn test_from_row(row: &Row) -> Test {
  Test {
    id: row.get(0),
    run_id: row.get(1),
    test_name: row.get(2),
    spec_name: row.get(3),
    test_path: parse_json(row.get::<_, String>(4).as_str()),
    started_at: row.get(5),
    ended_at: row.get(6),
    status: parse_test_status(row.get::<_, String>(7).as_str()),
    duration_ms: row.get(8),
    error: row.get(9),
  }
}

pub(super) fn entry_from_row(row: &Row) -> Entry {
  Entry {
    id: row.get(0),
    run_id: row.get(1),
    test_id: row.get(2),
    timestamp: row.get(3),
    system: row.get(4),
    action: row.get(5),
    result: parse_test_status(row.get::<_, String>(6).as_str()),
    input: row.get(7),
    output: row.get(8),
    metadata: row.get(9),
    expected: row.get(10),
    actual: row.get(11),
    error: row.get(12),
    trace_id: row.get(13),
    assertion_id: row.get(14),
    attempt_count: row.get(15),
    failure_count: row.get(16),
  }
}

pub(super) fn span_from_row(row: &Row) -> Span {
  Span {
    id: row.get(0),
    run_id: row.get(1),
    trace_id: row.get(2),
    span_id: row.get(3),
    parent_span_id: row.get(4),
    operation_name: row.get(5),
    service_name: row.get(6),
    start_time_nanos: row.get(7),
    end_time_nanos: row.get(8),
    status: row.get(9),
    attributes: row.get(10),
    exception_type: row.get(11),
    exception_message: row.get(12),
    exception_stack_trace: row.get(13),
  }
}

pub(super) fn snapshot_from_row(row: &Row) -> Snapshot {
  Snapshot {
    id: row.get(0),
    run_id: row.get(1),
    test_id: row.get(2),
    system: row.get(3),
    state_json: row.get(4),
    summary: row.get(5),
    captured_at: row.get(6),
    trigger: row.get(7),
  }
}

pub(super) fn mock_interaction_from_row(row: &Row) -> MockInteraction {
  let near_misses: Option<String> = row.get(17);
  MockInteraction {
    id: row.get(0),
    run_id: row.get(1),
    test_id: row.get(2),
    timestamp: row.get(3),
    system: row.get(4),
    protocol: row.get(5),
    method: row.get(6),
    target: row.get(7),
    matched: row.get(8),
    stub_id: row.get(9),
    attribution: row.get(10),
    request_body: row.get(11),
    request_body_truncated: row.get(12),
    response_body: row.get(13),
    response_body_truncated: row.get(14),
    status: row.get(15),
    latency_ms: row.get(16),
    near_misses: near_misses.as_deref().map(parse_json).unwrap_or_default(),
    trace_id: row.get(18),
    scenario_name: row.get(19),
    scenario_state: row.get(20),
    next_scenario_state: row.get(21),
    configured_delay_ms: row.get(22),
    fault: row.get(23),
    client_deadline_ms: row.get(24),
  }
}

pub(super) fn mock_warning_from_row(row: &Row) -> MockWarning {
  MockWarning {
    id: row.get(0),
    run_id: row.get(1),
    test_id: row.get(2),
    timestamp: row.get(3),
    system: row.get(4),
    kind: row.get(5),
    message: row.get(6),
    stub_id: row.get(7),
    target: row.get(8),
  }
}

fn parse_json<T: serde::de::DeserializeOwned + Default>(value: &str) -> T {
  serde_json::from_str(value).unwrap_or_default()
}

fn parse_run_status(value: &str) -> RunStatus {
  value.parse().unwrap_or(RunStatus::Running)
}

fn parse_test_status(value: &str) -> TestStatus {
  value.parse().unwrap_or(TestStatus::Running)
}
