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
use crate::ingest::LiveLogRecordedPayload;
use crate::ingest::LiveLogsDroppedPayload;
use crate::ingest::LiveRunEndedPayload;
use crate::ingest::LiveRunStartedPayload;
use crate::ingest::LiveSnapshotPayload;
use crate::ingest::LiveSpanRecordedPayload;
use crate::ingest::LiveTestEndedPayload;
use crate::ingest::LiveTestStartedPayload;
use crate::ingest::PersistedDashboardEvent;
use crate::proto;
use crate::storage::models::NewEntry;
use crate::storage::models::NewLogRecord;
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
  state
    .ended_tests
    .insert((run_id.to_string(), event.test_id.clone()));
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
      }),
    ),
    persisted: PersistedDashboardEvent::Snapshot {
      run_id: run_id.to_string(),
      test_id: event.test_id.clone(),
      system: event.system.clone(),
      state_json: event.state_json.clone(),
      summary: event.summary.clone(),
    },
    flush: FlushBehavior::Deferred,
  })
}

pub(super) fn prepare_log_recorded(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::LogRecordedEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_run_known(state, run_id)?;
  if !event.trace_id.is_empty() && !event.test_id.is_empty() {
    state.traces.insert(
      (run_id.to_string(), event.trace_id.clone()),
      event.test_id.clone(),
    );
  }
  let timestamp = format_timestamp(event.timestamp.as_ref());
  let observed_timestamp = format_timestamp(event.observed_timestamp.as_ref());
  let attributes = serde_json::to_string(&event.attributes)?;
  let late = !event.test_id.is_empty()
    && state
      .ended_tests
      .contains(&(run_id.to_string(), event.test_id.clone()));
  let scope = normalize_scope(&event.scope);
  let log = NewLogRecord {
    run_id: run_id.to_string(),
    test_id: event.test_id.clone(),
    trace_id: event.trace_id.clone(),
    span_id: event.span_id.clone(),
    timestamp: timestamp.clone(),
    observed_timestamp: observed_timestamp.clone(),
    severity_text: event.severity_text.clone(),
    severity_number: event.severity_number,
    logger: event.logger.clone(),
    thread: event.thread.clone(),
    body: event.body.clone(),
    exception_type: event.exception_type.clone(),
    exception_message: event.exception_message.clone(),
    exception_stack_trace: event.exception_stack_trace.clone(),
    attributes: attributes.clone(),
    correlation_source: event.correlation_source.clone(),
    source: event.source.clone(),
    scope: scope.clone(),
    late,
    truncated: event.truncated,
  };

  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::LOG_RECORDED,
      LiveDashboardPayload::LogRecorded(LiveLogRecordedPayload {
        id: 0,
        test_id: non_empty(&event.test_id),
        trace_id: non_empty(&event.trace_id),
        span_id: non_empty(&event.span_id),
        timestamp,
        observed_timestamp,
        severity_text: event.severity_text.clone(),
        severity_number: event.severity_number,
        logger: event.logger.clone(),
        thread: event.thread.clone(),
        body: event.body.clone(),
        exception_type: non_empty(&event.exception_type),
        exception_message: non_empty(&event.exception_message),
        exception_stack_trace: non_empty(&event.exception_stack_trace),
        attributes: non_empty(&attributes),
        correlation_source: event.correlation_source.clone(),
        source: event.source.clone(),
        scope,
        late,
        truncated: event.truncated,
      }),
    ),
    persisted: PersistedDashboardEvent::LogRecorded(log),
    flush: FlushBehavior::Deferred,
  })
}

fn normalize_scope(raw: &str) -> String {
  match raw.trim().to_ascii_uppercase().as_str() {
    "TEST" => "TEST".to_string(),
    _ => "RUN".to_string(),
  }
}

pub(super) fn prepare_logs_dropped(
  state: &mut LiveState,
  run_id: &str,
  event: &proto::LogsDroppedEvent,
) -> AppResult<PreparedDashboardEvent> {
  ensure_run_known(state, run_id)?;
  let timestamp = format_timestamp(event.timestamp.as_ref());
  let attributes = serde_json::to_string(&serde_json::json!({
    "dropped_count": event.dropped_count,
    "reason": event.reason,
  }))?;
  let late = !event.test_id.is_empty()
    && state
      .ended_tests
      .contains(&(run_id.to_string(), event.test_id.clone()));
  let body = format!(
    "Dropped {} log record(s): {}",
    event.dropped_count, event.reason
  );
  let log = NewLogRecord {
    run_id: run_id.to_string(),
    test_id: event.test_id.clone(),
    trace_id: event.trace_id.clone(),
    span_id: String::new(),
    timestamp: timestamp.clone(),
    observed_timestamp: timestamp.clone(),
    severity_text: "WARN".to_string(),
    severity_number: 13,
    logger: "stove.logging".to_string(),
    thread: String::new(),
    body: body.clone(),
    exception_type: String::new(),
    exception_message: String::new(),
    exception_stack_trace: String::new(),
    attributes: attributes.clone(),
    correlation_source: "DROPPED_MARKER".to_string(),
    source: "stove".to_string(),
    scope: "RUN".to_string(),
    late,
    truncated: false,
  };

  Ok(PreparedDashboardEvent {
    live: live_event(
      run_id,
      event_type::LOGS_DROPPED,
      LiveDashboardPayload::LogsDropped(LiveLogsDroppedPayload {
        test_id: non_empty(&event.test_id),
        trace_id: non_empty(&event.trace_id),
        timestamp,
        dropped_count: event.dropped_count,
        reason: event.reason.clone(),
      }),
    ),
    persisted: PersistedDashboardEvent::LogRecorded(log),
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
