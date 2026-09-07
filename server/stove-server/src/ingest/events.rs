//! Durable mutations and typed live events produced by ingestion.

use std::collections::BTreeMap;

use serde::Serialize;
use utoipa::ToSchema;

use crate::storage::models::{
  NewEntry, NewMockInteraction, NewMockWarning, NewSpan, RunStatus, SpanStatus, TestStatus,
};

#[derive(Clone, Debug)]
pub struct EventIdentity {
  pub event_id: String,
  pub sequence: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommitOutcome {
  pub duplicate: bool,
  pub live_event_id: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredLiveEvent {
  pub id: u64,
  pub json: String,
}

#[derive(Clone, Debug)]
pub enum PersistedDashboardEvent {
  RunStarted {
    run_id: String,
    app_name: String,
    started_at: String,
    stove_version: Option<String>,
    systems: Vec<String>,
    metadata: BTreeMap<String, String>,
  },
  RunEnded {
    run_id: String,
    ended_at: String,
    total_tests: i32,
    passed: i32,
    failed: i32,
    duration_ms: i64,
  },
  TestStarted {
    run_id: String,
    test_id: String,
    test_name: String,
    spec_name: String,
    test_path: Vec<String>,
    started_at: String,
  },
  TestEnded {
    run_id: String,
    test_id: String,
    status: String,
    duration_ms: i64,
    error: Option<String>,
    ended_at: String,
  },
  EntryRecorded(NewEntry),
  SpanRecorded(NewSpan),
  Snapshot {
    run_id: String,
    test_id: String,
    system: String,
    state_json: String,
    summary: String,
    captured_at: String,
    trigger: String,
  },
  MockInteraction(NewMockInteraction),
  MockWarning(NewMockWarning),
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveDashboardEvent {
  pub seq: u64,
  pub run_id: String,
  #[serde(flatten)]
  pub payload: LiveDashboardPayload,
}

pub(crate) struct PreparedDashboardEvent {
  pub(crate) live: LiveDashboardEvent,
  pub(crate) persisted: PersistedDashboardEvent,
}

impl LiveDashboardEvent {
  #[must_use]
  pub(crate) fn new(run_id: &str, payload: LiveDashboardPayload) -> Self {
    Self {
      seq: 0,
      run_id: run_id.to_string(),
      payload,
    }
  }

  #[must_use]
  pub const fn event_type(&self) -> &'static str {
    self.payload.event_type()
  }

  #[must_use]
  pub fn with_seq(mut self, seq: u64) -> Self {
    self.seq = seq;
    let temp_id = live_record_id(seq);
    match &mut self.payload {
      LiveDashboardPayload::EntryRecorded(payload) => payload.id = temp_id,
      LiveDashboardPayload::SpanRecorded(payload) => payload.id = temp_id,
      LiveDashboardPayload::Snapshot(payload) => payload.id = temp_id,
      LiveDashboardPayload::MockInteraction(payload) => payload.id = temp_id,
      LiveDashboardPayload::MockWarning(payload) => payload.id = temp_id,
      LiveDashboardPayload::RunStarted(_)
      | LiveDashboardPayload::RunEnded(_)
      | LiveDashboardPayload::TestStarted(_)
      | LiveDashboardPayload::TestEnded(_) => {}
    }
    self
  }
}

#[derive(Clone, Debug, Serialize, ToSchema)]
#[serde(tag = "event_type", content = "payload", rename_all = "snake_case")]
pub enum LiveDashboardPayload {
  RunStarted(LiveRunStartedPayload),
  RunEnded(LiveRunEndedPayload),
  TestStarted(LiveTestStartedPayload),
  TestEnded(LiveTestEndedPayload),
  EntryRecorded(LiveEntryRecordedPayload),
  SpanRecorded(LiveSpanRecordedPayload),
  Snapshot(LiveSnapshotPayload),
  MockInteraction(LiveMockInteractionPayload),
  MockWarning(LiveMockWarningPayload),
}

impl LiveDashboardPayload {
  const fn event_type(&self) -> &'static str {
    match self {
      Self::RunStarted(_) => "run_started",
      Self::RunEnded(_) => "run_ended",
      Self::TestStarted(_) => "test_started",
      Self::TestEnded(_) => "test_ended",
      Self::EntryRecorded(_) => "entry_recorded",
      Self::SpanRecorded(_) => "span_recorded",
      Self::Snapshot(_) => "snapshot",
      Self::MockInteraction(_) => "mock_interaction",
      Self::MockWarning(_) => "mock_warning",
    }
  }
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveRunStartedPayload {
  pub app_name: String,
  pub started_at: String,
  #[schema(required = true)]
  pub stove_version: Option<String>,
  pub systems: Vec<String>,
  pub metadata: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveRunEndedPayload {
  pub ended_at: String,
  pub status: RunStatus,
  pub total_tests: i32,
  pub passed: i32,
  pub failed: i32,
  pub duration_ms: i64,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveTestStartedPayload {
  pub test_id: String,
  pub test_name: String,
  pub spec_name: String,
  pub test_path: Vec<String>,
  pub started_at: String,
  pub status: TestStatus,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveTestEndedPayload {
  pub test_id: String,
  pub status: TestStatus,
  pub duration_ms: i64,
  #[schema(required = true)]
  pub error: Option<String>,
  pub ended_at: String,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveEntryRecordedPayload {
  pub id: i64,
  pub test_id: String,
  pub timestamp: String,
  pub system: String,
  pub action: String,
  pub result: TestStatus,
  #[schema(required = true)]
  pub input: Option<String>,
  #[schema(required = true)]
  pub output: Option<String>,
  #[schema(required = true)]
  pub metadata: Option<String>,
  #[schema(required = true)]
  pub expected: Option<String>,
  #[schema(required = true)]
  pub actual: Option<String>,
  #[schema(required = true)]
  pub error: Option<String>,
  #[schema(required = true)]
  pub trace_id: Option<String>,
  pub assertion_id: String,
  pub attempt_count: i64,
  pub failure_count: i64,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveSpanRecordedPayload {
  pub id: i64,
  #[schema(required = true)]
  pub test_id: Option<String>,
  pub trace_id: String,
  pub span_id: String,
  #[schema(required = true)]
  pub parent_span_id: Option<String>,
  pub operation_name: String,
  pub service_name: String,
  pub start_time_nanos: i64,
  pub end_time_nanos: i64,
  pub status: SpanStatus,
  #[schema(required = true)]
  pub attributes: Option<String>,
  #[schema(required = true)]
  pub exception_type: Option<String>,
  #[schema(required = true)]
  pub exception_message: Option<String>,
  #[schema(required = true)]
  pub exception_stack_trace: Option<String>,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveSnapshotPayload {
  pub id: i64,
  pub test_id: String,
  pub system: String,
  pub state_json: String,
  pub summary: String,
  #[schema(required = true)]
  pub captured_at: Option<String>,
  pub trigger: String,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveMockInteractionPayload {
  pub id: i64,
  #[schema(required = true)]
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub protocol: String,
  pub method: String,
  pub target: String,
  pub matched: bool,
  #[schema(required = true)]
  pub stub_id: Option<String>,
  pub attribution: String,
  #[schema(required = true)]
  pub request_body: Option<String>,
  pub request_body_truncated: bool,
  #[schema(required = true)]
  pub response_body: Option<String>,
  pub response_body_truncated: bool,
  pub status: String,
  #[schema(required = true)]
  pub latency_ms: Option<i64>,
  pub near_misses: Vec<String>,
  #[schema(required = true)]
  pub trace_id: Option<String>,
  #[schema(required = true)]
  pub scenario_name: Option<String>,
  #[schema(required = true)]
  pub scenario_state: Option<String>,
  #[schema(required = true)]
  pub next_scenario_state: Option<String>,
  #[schema(required = true)]
  pub configured_delay_ms: Option<i64>,
  #[schema(required = true)]
  pub fault: Option<String>,
  #[schema(required = true)]
  pub client_deadline_ms: Option<i64>,
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct LiveMockWarningPayload {
  pub id: i64,
  #[schema(required = true)]
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub kind: String,
  pub message: String,
  #[schema(required = true)]
  pub stub_id: Option<String>,
  #[schema(required = true)]
  pub target: Option<String>,
}

fn live_record_id(seq: u64) -> i64 {
  let bounded = seq.min(i64::MAX as u64);
  -bounded.cast_signed()
}
