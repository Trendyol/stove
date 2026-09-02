use diesel::Queryable;
use diesel::QueryableByName;
use diesel::sql_types::{BigInt, Nullable, Text};

use crate::storage::models::{
  AppSummary, Entry, MockInteraction, MockWarning, OpenAssertion, Run, RunStatus, Snapshot, Span,
  Test, TestStatus,
};

#[derive(Queryable)]
pub(super) struct RunRow<M> {
  id: String,
  app_name: String,
  started_at: String,
  ended_at: Option<String>,
  status: String,
  total_tests: i32,
  passed: i32,
  failed: i32,
  duration_ms: Option<i64>,
  systems: String,
  stove_version: Option<String>,
  metadata: M,
}

impl From<RunRow<String>> for Run {
  fn from(row: RunRow<String>) -> Self {
    row.into_domain(|metadata| parse_json(&metadata))
  }
}

impl From<RunRow<serde_json::Value>> for Run {
  fn from(row: RunRow<serde_json::Value>) -> Self {
    row.into_domain(|metadata| serde_json::from_value(metadata).unwrap_or_default())
  }
}

impl<M> RunRow<M> {
  fn into_domain(self, convert_metadata: impl FnOnce(M) -> BTreeMap<String, String>) -> Run {
    Run {
      id: self.id,
      app_name: self.app_name,
      started_at: self.started_at,
      ended_at: self.ended_at,
      status: parse_run_status(&self.status),
      total_tests: self.total_tests,
      passed: self.passed,
      failed: self.failed,
      duration_ms: self.duration_ms,
      stove_version: self.stove_version,
      systems: parse_json(&self.systems),
      metadata: convert_metadata(self.metadata),
    }
  }
}

#[derive(Queryable)]
pub(super) struct TestRow {
  id: String,
  run_id: String,
  test_name: String,
  spec_name: String,
  test_path: String,
  started_at: String,
  ended_at: Option<String>,
  status: String,
  duration_ms: Option<i64>,
  error: Option<String>,
}

impl From<TestRow> for Test {
  fn from(row: TestRow) -> Self {
    Self {
      id: row.id,
      run_id: row.run_id,
      test_name: row.test_name,
      spec_name: row.spec_name,
      test_path: parse_json(&row.test_path),
      started_at: row.started_at,
      ended_at: row.ended_at,
      status: parse_test_status(&row.status),
      duration_ms: row.duration_ms,
      error: row.error,
    }
  }
}

#[derive(Queryable, QueryableByName)]
pub(super) struct SpanRow {
  #[diesel(sql_type = BigInt)]
  id: i64,
  #[diesel(sql_type = Text)]
  run_id: String,
  #[diesel(sql_type = Text)]
  trace_id: String,
  #[diesel(sql_type = Text)]
  span_id: String,
  #[diesel(sql_type = Nullable<Text>)]
  parent_span_id: Option<String>,
  #[diesel(sql_type = Text)]
  operation_name: String,
  #[diesel(sql_type = Text)]
  service_name: String,
  #[diesel(sql_type = BigInt)]
  start_time_nanos: i64,
  #[diesel(sql_type = BigInt)]
  end_time_nanos: i64,
  #[diesel(sql_type = Text)]
  status: String,
  #[diesel(sql_type = Nullable<Text>)]
  attributes: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  exception_type: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  exception_message: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  exception_stack_trace: Option<String>,
}

impl From<SpanRow> for Span {
  fn from(row: SpanRow) -> Self {
    Self {
      id: row.id,
      run_id: row.run_id,
      trace_id: row.trace_id,
      span_id: row.span_id,
      parent_span_id: row.parent_span_id,
      operation_name: row.operation_name,
      service_name: row.service_name,
      start_time_nanos: row.start_time_nanos,
      end_time_nanos: row.end_time_nanos,
      status: row.status,
      attributes: row.attributes,
      exception_type: row.exception_type,
      exception_message: row.exception_message,
      exception_stack_trace: row.exception_stack_trace,
    }
  }
}

#[derive(Queryable)]
pub(super) struct SnapshotRow {
  id: i64,
  run_id: String,
  test_id: String,
  system: String,
  state_json: String,
  summary: String,
  captured_at: Option<String>,
  trigger_kind: String,
}

impl From<SnapshotRow> for Snapshot {
  fn from(row: SnapshotRow) -> Self {
    Self {
      id: row.id,
      run_id: row.run_id,
      test_id: row.test_id,
      system: row.system,
      state_json: row.state_json,
      summary: row.summary,
      captured_at: row.captured_at,
      trigger: row.trigger_kind,
    }
  }
}

#[derive(Queryable)]
pub(super) struct MockInteractionRow {
  id: i64,
  run_id: String,
  test_id: Option<String>,
  timestamp: String,
  system: String,
  protocol: String,
  method: String,
  target: String,
  matched: bool,
  stub_id: Option<String>,
  attribution: String,
  request_body: Option<String>,
  request_body_truncated: bool,
  response_body: Option<String>,
  response_body_truncated: bool,
  status: String,
  latency_ms: Option<i64>,
  near_misses: Option<String>,
  trace_id: Option<String>,
  scenario_name: Option<String>,
  scenario_state: Option<String>,
  next_scenario_state: Option<String>,
  configured_delay_ms: Option<i64>,
  fault: Option<String>,
  client_deadline_ms: Option<i64>,
}

impl MockInteractionRow {
  pub(super) fn into_domain(self) -> serde_json::Result<MockInteraction> {
    let near_misses = self
      .near_misses
      .as_deref()
      .map(serde_json::from_str)
      .transpose()?
      .unwrap_or_default();
    Ok(MockInteraction {
      id: self.id,
      run_id: self.run_id,
      test_id: self.test_id,
      timestamp: self.timestamp,
      system: self.system,
      protocol: self.protocol,
      method: self.method,
      target: self.target,
      matched: self.matched,
      stub_id: self.stub_id,
      attribution: self.attribution,
      request_body: self.request_body,
      request_body_truncated: self.request_body_truncated,
      response_body: self.response_body,
      response_body_truncated: self.response_body_truncated,
      status: self.status,
      latency_ms: self.latency_ms,
      near_misses,
      trace_id: self.trace_id,
      scenario_name: self.scenario_name,
      scenario_state: self.scenario_state,
      next_scenario_state: self.next_scenario_state,
      configured_delay_ms: self.configured_delay_ms,
      fault: self.fault,
      client_deadline_ms: self.client_deadline_ms,
    })
  }
}

#[derive(Queryable)]
pub(super) struct MockWarningRow {
  id: i64,
  run_id: String,
  test_id: Option<String>,
  timestamp: String,
  system: String,
  kind: String,
  message: String,
  stub_id: Option<String>,
  target: Option<String>,
}

impl From<MockWarningRow> for MockWarning {
  fn from(row: MockWarningRow) -> Self {
    Self {
      id: row.id,
      run_id: row.run_id,
      test_id: row.test_id,
      timestamp: row.timestamp,
      system: row.system,
      kind: row.kind,
      message: row.message,
      stub_id: row.stub_id,
      target: row.target,
    }
  }
}

#[derive(QueryableByName)]
pub(super) struct OpenAssertionRow {
  #[diesel(sql_type = Text)]
  assertion_id: String,
  #[diesel(sql_type = BigInt)]
  attempt_count: i64,
  #[diesel(sql_type = BigInt)]
  failure_count: i64,
}

impl From<OpenAssertionRow> for OpenAssertion {
  fn from(row: OpenAssertionRow) -> Self {
    Self {
      assertion_id: row.assertion_id,
      attempt_count: row.attempt_count,
      failure_count: row.failure_count,
    }
  }
}

#[derive(QueryableByName)]
pub(super) struct AppSummaryRow {
  #[diesel(sql_type = Text)]
  app_name: String,
  #[diesel(sql_type = Text)]
  latest_run_id: String,
  #[diesel(sql_type = Text)]
  latest_status: String,
  #[diesel(sql_type = Nullable<Text>)]
  stove_version: Option<String>,
  #[diesel(sql_type = Text)]
  metadata: String,
}

impl AppSummaryRow {
  pub(super) fn into_domain(self) -> serde_json::Result<AppSummary> {
    Ok(AppSummary {
      app_name: self.app_name,
      latest_run_id: self.latest_run_id,
      latest_status: parse_run_status(&self.latest_status),
      stove_version: self.stove_version,
      metadata: serde_json::from_str(&self.metadata)?,
    })
  }
}

#[derive(QueryableByName)]
pub(super) struct EntryRow {
  #[diesel(sql_type = BigInt)]
  id: i64,
  #[diesel(sql_type = Text)]
  run_id: String,
  #[diesel(sql_type = Text)]
  test_id: String,
  #[diesel(sql_type = Text)]
  timestamp: String,
  #[diesel(sql_type = Text)]
  system: String,
  #[diesel(sql_type = Text)]
  action: String,
  #[diesel(sql_type = Text)]
  result: String,
  #[diesel(sql_type = Nullable<Text>)]
  input: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  output: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  metadata: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  expected: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  actual: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  error: Option<String>,
  #[diesel(sql_type = Nullable<Text>)]
  trace_id: Option<String>,
  #[diesel(sql_type = Text)]
  assertion_id: String,
  #[diesel(sql_type = BigInt)]
  attempt_count: i64,
  #[diesel(sql_type = BigInt)]
  failure_count: i64,
}

impl From<EntryRow> for Entry {
  fn from(row: EntryRow) -> Self {
    Self {
      id: row.id,
      run_id: row.run_id,
      test_id: row.test_id,
      timestamp: row.timestamp,
      system: row.system,
      action: row.action,
      result: parse_test_status(&row.result),
      input: row.input,
      output: row.output,
      metadata: row.metadata,
      expected: row.expected,
      actual: row.actual,
      error: row.error,
      trace_id: row.trace_id,
      assertion_id: row.assertion_id,
      attempt_count: row.attempt_count,
      failure_count: row.failure_count,
    }
  }
}

pub(super) fn parse_json<T: serde::de::DeserializeOwned + Default>(value: &str) -> T {
  serde_json::from_str(value).unwrap_or_default()
}

fn parse_run_status(value: &str) -> RunStatus {
  value.parse().unwrap_or(RunStatus::Running)
}

fn parse_test_status(value: &str) -> TestStatus {
  value.parse().unwrap_or(TestStatus::Running)
}
use std::collections::BTreeMap;
