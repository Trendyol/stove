//! Per-event preparation logic.
//!
//! Each `prepare_*` function translates one protobuf event into the durable
//! domain mutation and its corresponding live dashboard payload.

use std::collections::BTreeMap;

use crate::error::Result as AppResult;
use crate::ingest::LiveDashboardEvent;
use crate::ingest::LiveDashboardPayload;
use crate::ingest::LiveEntryRecordedPayload;
use crate::ingest::LiveMockInteractionPayload;
use crate::ingest::LiveMockWarningPayload;
use crate::ingest::LiveRunEndedPayload;
use crate::ingest::LiveRunStartedPayload;
use crate::ingest::LiveSnapshotPayload;
use crate::ingest::LiveSpanRecordedPayload;
use crate::ingest::LiveTestEndedPayload;
use crate::ingest::LiveTestStartedPayload;
use crate::ingest::PersistedDashboardEvent;
use crate::ingest::PreparedDashboardEvent;
use crate::proto;
use crate::storage::models::NewEntry;
use crate::storage::models::NewMockInteraction;
use crate::storage::models::NewMockWarning;
use crate::storage::models::NewSpan;
use crate::storage::models::OpenAssertion;
use uuid::Uuid;

use super::convert::extract_test_id;
use super::convert::format_timestamp;
use super::convert::non_empty;
use super::convert::run_status;

pub(super) fn prepare_run_started(
  run_id: &str,
  event: &proto::RunStartedEvent,
) -> PreparedDashboardEvent {
  let started_at = format_timestamp(event.timestamp.as_ref());
  let stove_version = non_empty(&event.stove_version);
  let metadata = event
    .metadata
    .clone()
    .into_iter()
    .collect::<BTreeMap<_, _>>();
  PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::RunStarted(LiveRunStartedPayload {
        app_name: event.app_name.clone(),
        started_at: started_at.clone(),
        stove_version: stove_version.clone(),
        systems: event.systems.clone(),
        metadata: metadata.clone(),
      }),
    ),
    persisted: PersistedDashboardEvent::RunStarted {
      run_id: run_id.to_string(),
      app_name: event.app_name.clone(),
      started_at,
      stove_version,
      systems: event.systems.clone(),
      metadata,
    },
  }
}

pub(super) fn prepare_run_ended(
  run_id: &str,
  event: &proto::RunEndedEvent,
) -> PreparedDashboardEvent {
  let ended_at = format_timestamp(event.timestamp.as_ref());
  let status = run_status(event.failed).to_string();
  PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::RunEnded(LiveRunEndedPayload {
        ended_at: ended_at.clone(),
        status,
        total_tests: event.total_tests,
        passed: event.passed,
        failed: event.failed,
        duration_ms: event.duration_ms,
      }),
    ),
    persisted: PersistedDashboardEvent::RunEnded {
      run_id: run_id.to_string(),
      ended_at,
      total_tests: event.total_tests,
      passed: event.passed,
      failed: event.failed,
      duration_ms: event.duration_ms,
    },
  }
}

pub(super) fn prepare_test_started(
  run_id: &str,
  event: &proto::TestStartedEvent,
) -> PreparedDashboardEvent {
  let started_at = format_timestamp(event.timestamp.as_ref());
  PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::TestStarted(LiveTestStartedPayload {
        test_id: event.test_id.clone(),
        test_name: event.test_name.clone(),
        spec_name: event.spec_name.clone(),
        test_path: event.test_path.clone(),
        started_at: started_at.clone(),
        status: "RUNNING".to_string(),
      }),
    ),
    persisted: PersistedDashboardEvent::TestStarted {
      run_id: run_id.to_string(),
      test_id: event.test_id.clone(),
      test_name: event.test_name.clone(),
      spec_name: event.spec_name.clone(),
      test_path: event.test_path.clone(),
      started_at,
    },
  }
}

pub(super) fn prepare_test_ended(
  run_id: &str,
  event: &proto::TestEndedEvent,
) -> PreparedDashboardEvent {
  let ended_at = format_timestamp(event.timestamp.as_ref());
  PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::TestEnded(LiveTestEndedPayload {
        test_id: event.test_id.clone(),
        status: event.status.clone(),
        duration_ms: event.duration_ms,
        error: non_empty(&event.error),
        ended_at: ended_at.clone(),
      }),
    ),
    persisted: PersistedDashboardEvent::TestEnded {
      run_id: run_id.to_string(),
      test_id: event.test_id.clone(),
      status: event.status.clone(),
      duration_ms: event.duration_ms,
      error: non_empty(&event.error),
      ended_at,
    },
  }
}

pub(super) fn prepare_entry_recorded(
  run_id: &str,
  event: &proto::EntryRecordedEvent,
  open_assertion: Option<OpenAssertion>,
) -> AppResult<PreparedDashboardEvent> {
  let metadata = serde_json::to_string(&event.metadata)?;
  let timestamp = format_timestamp(event.timestamp.as_ref());
  let (assertion_id, attempt_count, failure_count) = assertion_attempt(event, open_assertion);
  let entry = NewEntry {
    run_id: run_id.to_string(),
    test_id: event.test_id.clone(),
    timestamp: timestamp.clone(),
    system: event.system.clone(),
    action: event.action.clone(),
    result: event.result.clone(),
    input: event.input.clone(),
    output: event.output.clone(),
    metadata: metadata.clone(),
    expected: event.expected.clone(),
    actual: event.actual.clone(),
    error: event.error.clone(),
    trace_id: event.trace_id.clone(),
    assertion_id: assertion_id.clone(),
    correlation_key: assertion_correlation_key(event)?,
  };

  Ok(PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::EntryRecorded(live_entry(&entry, attempt_count, failure_count)),
    ),
    persisted: PersistedDashboardEvent::EntryRecorded(entry),
  })
}

/// The current reporting protocol does not carry a call-site identity, so the CLI
/// derives a best-effort correlation signature from the assertion's semantic
/// action, input, and expectation. Result-specific fields are deliberately excluded.
pub(super) fn assertion_correlation_key(event: &proto::EntryRecordedEvent) -> AppResult<String> {
  Ok(serde_json::to_string(&[
    &event.test_id,
    &event.system,
    &event.action,
    &event.input,
    &event.expected,
  ])?)
}

pub(super) fn prepare_span_recorded(
  run_id: &str,
  event: &proto::SpanRecordedEvent,
  trace_test_id: Option<String>,
) -> AppResult<PreparedDashboardEvent> {
  let test_id = extract_test_id(&event.attributes).or(trace_test_id);
  let span = new_span(run_id, event)?;

  Ok(PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::SpanRecorded(live_span(&span, test_id)),
    ),
    persisted: PersistedDashboardEvent::SpanRecorded(span),
  })
}

pub(super) fn prepare_snapshot(
  run_id: &str,
  event: &proto::SnapshotEvent,
) -> PreparedDashboardEvent {
  let captured_at = format_timestamp(event.timestamp.as_ref());
  let trigger = if event.trigger.is_empty() {
    "TEST_END".to_string()
  } else {
    event.trigger.clone()
  };
  PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::Snapshot(LiveSnapshotPayload {
        id: 0,
        test_id: event.test_id.clone(),
        system: event.system.clone(),
        state_json: event.state_json.clone(),
        summary: event.summary.clone(),
        captured_at: non_empty(&captured_at),
        trigger: trigger.clone(),
      }),
    ),
    persisted: PersistedDashboardEvent::Snapshot {
      run_id: run_id.to_string(),
      test_id: event.test_id.clone(),
      system: event.system.clone(),
      state_json: event.state_json.clone(),
      summary: event.summary.clone(),
      captured_at,
      trigger,
    },
  }
}

/// Interactions and warnings are diagnostics: unlike entries, they may reference tests the
/// CLI has never seen (fail-open evidence, cross-test warnings naming another test id), so
/// only the run is validated and the test id is carried through as-is.
pub(super) fn prepare_mock_interaction(
  run_id: &str,
  event: &proto::MockInteractionEvent,
) -> AppResult<PreparedDashboardEvent> {
  let interaction = new_mock_interaction(run_id, event)?;

  Ok(PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::MockInteraction(live_mock_interaction(
        &interaction,
        event.near_misses.clone(),
      )),
    ),
    persisted: PersistedDashboardEvent::MockInteraction(interaction),
  })
}

pub(super) fn prepare_mock_warning(
  run_id: &str,
  event: &proto::MockWarningEvent,
) -> PreparedDashboardEvent {
  let timestamp = format_timestamp(event.timestamp.as_ref());
  let warning = NewMockWarning {
    run_id: run_id.to_string(),
    test_id: non_empty(&event.test_id),
    timestamp: timestamp.clone(),
    system: event.system.clone(),
    kind: event.kind.clone(),
    message: event.message.clone(),
    stub_id: non_empty(&event.stub_id),
    target: non_empty(&event.target),
  };

  PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::MockWarning(LiveMockWarningPayload {
        id: 0,
        test_id: warning.test_id.clone(),
        timestamp,
        system: warning.system.clone(),
        kind: warning.kind.clone(),
        message: warning.message.clone(),
        stub_id: warning.stub_id.clone(),
        target: warning.target.clone(),
      }),
    ),
    persisted: PersistedDashboardEvent::MockWarning(warning),
  }
}

fn assertion_attempt(
  event: &proto::EntryRecordedEvent,
  open_assertion: Option<OpenAssertion>,
) -> (String, i64, i64) {
  let failed = i64::from(matches!(event.result.as_str(), "FAILED" | "ERROR"));
  open_assertion.map_or_else(
    || (Uuid::new_v4().to_string(), 1, failed),
    |open| {
      (
        open.assertion_id,
        open.attempt_count + 1,
        open.failure_count + failed,
      )
    },
  )
}

fn live_entry(
  entry: &NewEntry,
  attempt_count: i64,
  failure_count: i64,
) -> LiveEntryRecordedPayload {
  LiveEntryRecordedPayload {
    id: 0,
    test_id: entry.test_id.clone(),
    timestamp: entry.timestamp.clone(),
    system: entry.system.clone(),
    action: entry.action.clone(),
    result: entry.result.clone(),
    input: non_empty(&entry.input),
    output: non_empty(&entry.output),
    metadata: non_empty(&entry.metadata),
    expected: non_empty(&entry.expected),
    actual: non_empty(&entry.actual),
    error: non_empty(&entry.error),
    trace_id: non_empty(&entry.trace_id),
    assertion_id: entry.assertion_id.clone(),
    attempt_count,
    failure_count,
  }
}

fn new_span(run_id: &str, event: &proto::SpanRecordedEvent) -> AppResult<NewSpan> {
  let (exception_type, exception_message, exception_stack_trace) = event
    .exception
    .as_ref()
    .map(|exception| {
      (
        exception.r#type.clone(),
        exception.message.clone(),
        exception.stack_trace.join("\n"),
      )
    })
    .unwrap_or_default();
  Ok(NewSpan {
    run_id: run_id.to_string(),
    trace_id: event.trace_id.clone(),
    span_id: event.span_id.clone(),
    parent_span_id: event.parent_span_id.clone(),
    operation_name: event.operation_name.clone(),
    service_name: event.service_name.clone(),
    start_time_nanos: event.start_time_nanos,
    end_time_nanos: event.end_time_nanos,
    status: event.status.clone(),
    attributes: serde_json::to_string(&event.attributes)?,
    exception_type,
    exception_message,
    exception_stack_trace,
  })
}

fn live_span(span: &NewSpan, test_id: Option<String>) -> LiveSpanRecordedPayload {
  LiveSpanRecordedPayload {
    id: 0,
    test_id,
    trace_id: span.trace_id.clone(),
    span_id: span.span_id.clone(),
    parent_span_id: non_empty(&span.parent_span_id),
    operation_name: span.operation_name.clone(),
    service_name: span.service_name.clone(),
    start_time_nanos: span.start_time_nanos,
    end_time_nanos: span.end_time_nanos,
    status: span.status.clone(),
    attributes: non_empty(&span.attributes),
    exception_type: non_empty(&span.exception_type),
    exception_message: non_empty(&span.exception_message),
    exception_stack_trace: non_empty(&span.exception_stack_trace),
  }
}

fn new_mock_interaction(
  run_id: &str,
  event: &proto::MockInteractionEvent,
) -> AppResult<NewMockInteraction> {
  Ok(NewMockInteraction {
    run_id: run_id.to_string(),
    test_id: non_empty(&event.test_id),
    timestamp: format_timestamp(event.timestamp.as_ref()),
    system: event.system.clone(),
    protocol: event.protocol.clone(),
    method: event.method.clone(),
    target: event.target.clone(),
    matched: event.matched,
    stub_id: non_empty(&event.stub_id),
    attribution: event.attribution().as_str_name().to_string(),
    request_body: event.request_body.clone(),
    request_body_truncated: event.request_body_truncated,
    response_body: event.response_body.clone(),
    response_body_truncated: event.response_body_truncated,
    status: event.status.clone(),
    latency_ms: (event.latency_ms >= 0).then_some(event.latency_ms),
    near_misses: serde_json::to_string(&event.near_misses)?,
    trace_id: non_empty(&event.trace_id),
    scenario_name: non_empty(&event.scenario_name),
    scenario_state: non_empty(&event.scenario_state),
    next_scenario_state: non_empty(&event.next_scenario_state),
    configured_delay_ms: (event.configured_delay_ms >= 0).then_some(event.configured_delay_ms),
    fault: non_empty(&event.fault),
    client_deadline_ms: (event.client_deadline_ms >= 0).then_some(event.client_deadline_ms),
  })
}

fn live_mock_interaction(
  interaction: &NewMockInteraction,
  near_misses: Vec<String>,
) -> LiveMockInteractionPayload {
  LiveMockInteractionPayload {
    id: 0,
    test_id: interaction.test_id.clone(),
    timestamp: interaction.timestamp.clone(),
    system: interaction.system.clone(),
    protocol: interaction.protocol.clone(),
    method: interaction.method.clone(),
    target: interaction.target.clone(),
    matched: interaction.matched,
    stub_id: interaction.stub_id.clone(),
    attribution: interaction.attribution.clone(),
    request_body: non_empty(&interaction.request_body),
    request_body_truncated: interaction.request_body_truncated,
    response_body: non_empty(&interaction.response_body),
    response_body_truncated: interaction.response_body_truncated,
    status: interaction.status.clone(),
    latency_ms: interaction.latency_ms,
    near_misses,
    trace_id: interaction.trace_id.clone(),
    scenario_name: interaction.scenario_name.clone(),
    scenario_state: interaction.scenario_state.clone(),
    next_scenario_state: interaction.next_scenario_state.clone(),
    configured_delay_ms: interaction.configured_delay_ms,
    fault: interaction.fault.clone(),
    client_deadline_ms: interaction.client_deadline_ms,
  }
}
