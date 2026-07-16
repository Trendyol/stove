//! Per-event preparation logic.
//!
//! Each `prepare_*` function validates the incoming protobuf event against
//! the live state, mutates the state for downstream events, and builds the
//! `PreparedDashboardEvent` that the service will fan out to both the SSE
//! broadcast and the persistence queue.

use crate::error::Result as AppResult;
use crate::ingest::FlushBehavior;
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
use crate::proto;
use crate::storage::models::NewEntry;
use crate::storage::models::NewMockInteraction;
use crate::storage::models::NewMockWarning;
use crate::storage::models::NewSpan;

use super::convert::PreparedDashboardEvent;
use super::convert::extract_test_id;
use super::convert::format_timestamp;
use super::convert::non_empty;
use super::convert::run_status;
use super::state::LiveState;
use super::state::ensure_run_known;
use super::state::ensure_test_known;
use super::state::event_type;

pub(super) fn prepare_run_started(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::RunStartedEvent,
) -> PreparedDashboardEvent {
  let started_at = format_timestamp(event.timestamp.as_ref());
  state.runs.insert(run_id.to_string());
  PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::RUN_STARTED,
      LiveDashboardPayload::RunStarted(LiveRunStartedPayload {
        app_name: event.app_name.clone(),
        started_at: started_at.clone(),
        stove_version: non_empty(&event.stove_version),
        systems: event.systems.clone(),
      }),
    ),
    persisted: PersistedDashboardEvent::RunStarted {
      run_id: run_id.to_string(),
      app_name: event.app_name.clone(),
      started_at,
      stove_version: non_empty(&event.stove_version),
      systems: event.systems.clone(),
    },
    flush: FlushBehavior::Deferred,
  }
}

pub(super) fn prepare_run_ended(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::RunEndedEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_run_known(state, run_id)?;
  let ended_at = format_timestamp(event.timestamp.as_ref());
  let status = run_status(event.failed).to_string();
  state.clear_run(run_id);
  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::RUN_ENDED,
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
    flush: FlushBehavior::Immediate,
  })
}

pub(super) fn prepare_test_started(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::TestStartedEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_run_known(state, run_id)?;
  let started_at = format_timestamp(event.timestamp.as_ref());
  state
    .tests
    .insert((run_id.to_string(), event.test_id.clone()));
  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::TEST_STARTED,
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
    flush: FlushBehavior::Deferred,
  })
}

pub(super) fn prepare_test_ended(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::TestEndedEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_test_known(state, run_id, &event.test_id)?;
  let ended_at = format_timestamp(event.timestamp.as_ref());
  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::TEST_ENDED,
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
    flush: FlushBehavior::Deferred,
  })
}

pub(super) fn prepare_entry_recorded(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::EntryRecordedEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_test_known(state, run_id, &event.test_id)?;
  if !event.trace_id.is_empty() {
    state.traces.insert(
      (run_id.to_string(), event.trace_id.clone()),
      event.test_id.clone(),
    );
  }

  let metadata = serde_json::to_string(&event.metadata)?;
  let timestamp = format_timestamp(event.timestamp.as_ref());
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
  };

  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::ENTRY_RECORDED,
      LiveDashboardPayload::EntryRecorded(LiveEntryRecordedPayload {
        id: 0,
        test_id: event.test_id.clone(),
        timestamp,
        system: event.system.clone(),
        action: event.action.clone(),
        result: event.result.clone(),
        input: non_empty(&event.input),
        output: non_empty(&event.output),
        metadata: non_empty(&metadata),
        expected: non_empty(&event.expected),
        actual: non_empty(&event.actual),
        error: non_empty(&event.error),
        trace_id: non_empty(&event.trace_id),
      }),
    ),
    persisted: PersistedDashboardEvent::EntryRecorded(entry),
    flush: FlushBehavior::Deferred,
  })
}

pub(super) fn prepare_span_recorded(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::SpanRecordedEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_run_known(state, run_id)?;
  let test_id = extract_test_id(&event.attributes).or_else(|| {
    state
      .traces
      .get(&(run_id.to_string(), event.trace_id.clone()))
      .cloned()
  });

  let attributes = serde_json::to_string(&event.attributes)?;
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

  let span = NewSpan {
    run_id: run_id.to_string(),
    trace_id: event.trace_id.clone(),
    span_id: event.span_id.clone(),
    parent_span_id: event.parent_span_id.clone(),
    operation_name: event.operation_name.clone(),
    service_name: event.service_name.clone(),
    start_time_nanos: event.start_time_nanos,
    end_time_nanos: event.end_time_nanos,
    status: event.status.clone(),
    attributes: attributes.clone(),
    exception_type: exception_type.clone(),
    exception_message: exception_message.clone(),
    exception_stack_trace: exception_stack_trace.clone(),
  };

  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::SPAN_RECORDED,
      LiveDashboardPayload::SpanRecorded(LiveSpanRecordedPayload {
        id: 0,
        test_id,
        trace_id: event.trace_id.clone(),
        span_id: event.span_id.clone(),
        parent_span_id: non_empty(&event.parent_span_id),
        operation_name: event.operation_name.clone(),
        service_name: event.service_name.clone(),
        start_time_nanos: event.start_time_nanos,
        end_time_nanos: event.end_time_nanos,
        status: event.status.clone(),
        attributes: non_empty(&attributes),
        exception_type: non_empty(&exception_type),
        exception_message: non_empty(&exception_message),
        exception_stack_trace: non_empty(&exception_stack_trace),
      }),
    ),
    persisted: PersistedDashboardEvent::SpanRecorded(span),
    flush: FlushBehavior::Deferred,
  })
}

pub(super) fn prepare_snapshot(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::SnapshotEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_test_known(state, run_id, &event.test_id)?;
  let captured_at = format_timestamp(event.timestamp.as_ref());
  let trigger = if event.trigger.is_empty() {
    "TEST_END".to_string()
  } else {
    event.trigger.clone()
  };
  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::SNAPSHOT,
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
    flush: FlushBehavior::Deferred,
  })
}

/// Interactions and warnings are diagnostics: unlike entries, they may reference tests the
/// CLI has never seen (fail-open evidence, cross-test warnings naming another test id), so
/// only the run is validated and the test id is carried through as-is.
pub(super) fn prepare_mock_interaction(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::MockInteractionEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_run_known(state, run_id)?;
  let timestamp = format_timestamp(event.timestamp.as_ref());
  let near_misses_json = serde_json::to_string(&event.near_misses)?;
  let interaction = NewMockInteraction {
    run_id: run_id.to_string(),
    test_id: non_empty(&event.test_id),
    timestamp: timestamp.clone(),
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
    near_misses: near_misses_json,
    trace_id: non_empty(&event.trace_id),
  };

  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::MOCK_INTERACTION,
      LiveDashboardPayload::MockInteraction(LiveMockInteractionPayload {
        id: 0,
        test_id: interaction.test_id.clone(),
        timestamp,
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
        near_misses: event.near_misses.clone(),
        trace_id: interaction.trace_id.clone(),
      }),
    ),
    persisted: PersistedDashboardEvent::MockInteraction(interaction),
    flush: FlushBehavior::Deferred,
  })
}

pub(super) fn prepare_mock_warning(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::MockWarningEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_run_known(state, run_id)?;
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

  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::MOCK_WARNING,
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
    flush: FlushBehavior::Deferred,
  })
}

fn live_event(run_id: &str, event_type: &str, payload: LiveDashboardPayload) -> LiveDashboardEvent {
  LiveDashboardEvent {
    seq: 0,
    run_id: run_id.to_string(),
    event_type: event_type.to_string(),
    payload,
  }
}
