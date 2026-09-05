mod convert;
mod preparers;

use std::collections::BTreeMap;
use std::sync::Arc;

use serde::Serialize;
use tracing::warn;
use uuid::Uuid;

use crate::error::AppError;
use crate::error::Result as AppResult;
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::models::{NewEntry, NewMockInteraction, NewMockWarning, NewSpan};
use crate::storage::repository::Repository;

/// Receives dashboard events from Stove test processes, commits them
/// transactionally, then fans committed events out to the live UI.
///
/// Shared by the gRPC service and the HTTP ingestion endpoint so both
/// transports apply identical validation, deduplication, and broadcast rules.
#[derive(Clone)]
pub struct EventIngestor {
  repository: Arc<Repository>,
  sse_manager: Arc<SseManager>,
  admission: Arc<tokio::sync::Semaphore>,
}

impl EventIngestor {
  #[must_use]
  pub fn new(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Self {
    Self::with_capacity(repository, sse_manager, 64)
  }

  /// Bound queued and running ingestion operations before scheduling blocking work.
  pub fn with_capacity(
    repository: Arc<Repository>,
    sse_manager: Arc<SseManager>,
    capacity: usize,
  ) -> Self {
    assert!(capacity > 0, "ingestion capacity must be positive");
    Self {
      repository,
      sse_manager,
      admission: Arc::new(tokio::sync::Semaphore::new(capacity)),
    }
  }

  pub(crate) async fn ingest_async(
    &self,
    event: proto::DashboardEvent,
  ) -> AppResult<proto::EventAck> {
    let permit = self
      .admission
      .clone()
      .try_acquire_owned()
      .map_err(|_| AppError::Overloaded)?;
    let ingestor = self.clone();
    tokio::task::spawn_blocking(move || {
      // Keep the permit in the blocking task, including when the request is cancelled.
      let _permit = permit;
      ingestor.ingest(&event)
    })
    .await
    .map_err(|error| AppError::Startup(format!("ingestion worker failed: {error}")))?
  }

  pub(crate) async fn ingest_batch(
    &self,
    batch: proto::DashboardEventBatch,
  ) -> AppResult<proto::BatchAck> {
    use prost::Message;
    if batch.events.is_empty() || batch.events.len() > 100 || batch.encoded_len() > 1024 * 1024 {
      return Err(AppError::InvalidEvent(
        "batch must contain 1..100 events and at most 1 MiB".into(),
      ));
    }
    let run_id = &batch.events[0].run_id;
    if batch
      .events
      .iter()
      .any(|event| &event.run_id != run_id || event.event_id.is_empty() || event.sequence == 0)
    {
      return Err(AppError::InvalidEvent(
        "batch events require one run, event IDs and positive sequences".into(),
      ));
    }
    let permit = self
      .admission
      .clone()
      .try_acquire_owned()
      .map_err(|_| AppError::Overloaded)?;
    let ingestor = self.clone();
    tokio::task::spawn_blocking(move || {
      let _permit = permit;
      let events = batch
        .events
        .into_iter()
        .map(|event| {
          (
            EventIdentity {
              event_id: event.event_id.clone(),
              sequence: Some(event.sequence),
            },
            event,
          )
        })
        .collect::<Vec<_>>();
      let outcomes = ingestor.repository.commit_dashboard_batch(&events)?;
      if outcomes.iter().any(|outcome| !outcome.duplicate) {
        ingestor.sse_manager.notify_commit();
      }
      Ok(proto::BatchAck {
        acknowledgements: events
          .into_iter()
          .zip(outcomes)
          .map(|((_, event), outcome)| proto::EventAck {
            accepted: true,
            event_id: event.event_id,
            sequence: event.sequence,
            duplicate: outcome.duplicate,
          })
          .collect(),
      })
    })
    .await
    .map_err(|error| AppError::Startup(format!("ingestion worker failed: {error}")))?
  }

  /// Commit an event transactionally before acknowledging it to the producer.
  pub(crate) fn ingest(&self, event: &proto::DashboardEvent) -> AppResult<proto::EventAck> {
    let identity = EventIdentity {
      event_id: if event.event_id.is_empty() {
        Uuid::new_v4().to_string()
      } else {
        event.event_id.clone()
      },
      sequence: (event.sequence > 0).then_some(event.sequence),
    };
    let outcome = self.repository.commit_dashboard_event(&identity, event)?;
    if !outcome.duplicate {
      self.sse_manager.notify_commit();
    }
    let mut acknowledgement = Self::accepted_ack();
    acknowledgement.event_id.clone_from(&event.event_id);
    acknowledgement.sequence = event.sequence;
    acknowledgement.duplicate = outcome.duplicate;
    Ok(acknowledgement)
  }

  pub(crate) fn accepted_ack() -> proto::EventAck {
    proto::EventAck {
      accepted: true,
      ..Default::default()
    }
  }

  pub(crate) fn prepare_event(
    lookup: &mut impl PreparationLookup,
    event: &proto::DashboardEvent,
  ) -> AppResult<Option<PreparedDashboardEvent>> {
    let Some(inner_event) = &event.event else {
      warn!("Received DashboardEvent with no event payload");
      return Ok(None);
    };

    let prepared = match inner_event {
      proto::dashboard_event::Event::RunStarted(inner) => {
        Ok(preparers::prepare_run_started(&event.run_id, inner))
      }
      proto::dashboard_event::Event::RunEnded(inner) => {
        Ok(preparers::prepare_run_ended(&event.run_id, inner))
      }
      proto::dashboard_event::Event::TestStarted(inner) => {
        Ok(preparers::prepare_test_started(&event.run_id, inner))
      }
      proto::dashboard_event::Event::TestEnded(inner) => {
        Ok(preparers::prepare_test_ended(&event.run_id, inner))
      }
      proto::dashboard_event::Event::EntryRecorded(inner) => {
        let correlation_key = preparers::assertion_correlation_key(inner)?;
        let open_assertion =
          lookup.get_open_assertion(&event.run_id, &inner.test_id, &correlation_key)?;
        preparers::prepare_entry_recorded(&event.run_id, inner, open_assertion)
      }
      proto::dashboard_event::Event::SpanRecorded(inner) => {
        let trace_test_id = lookup.get_test_id_for_trace(&event.run_id, &inner.trace_id)?;
        preparers::prepare_span_recorded(&event.run_id, inner, trace_test_id)
      }
      proto::dashboard_event::Event::Snapshot(inner) => {
        Ok(preparers::prepare_snapshot(&event.run_id, inner))
      }
      proto::dashboard_event::Event::MockInteraction(inner) => {
        preparers::prepare_mock_interaction(&event.run_id, inner)
      }
      proto::dashboard_event::Event::MockWarning(inner) => {
        Ok(preparers::prepare_mock_warning(&event.run_id, inner))
      }
    }?;

    Ok(Some(prepared))
  }
}

pub(crate) trait PreparationLookup {
  fn get_open_assertion(
    &mut self,
    run_id: &str,
    test_id: &str,
    correlation_key: &str,
  ) -> AppResult<Option<crate::storage::models::OpenAssertion>>;
  fn get_test_id_for_trace(&mut self, run_id: &str, trace_id: &str) -> AppResult<Option<String>>;
}

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

#[derive(Clone, Debug, Serialize)]
pub struct LiveDashboardEvent {
  pub seq: u64,
  pub run_id: String,
  pub event_type: String,
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
      event_type: payload.event_type().to_string(),
      payload,
    }
  }

  /// Use the committed database identity when publishing materialized evidence.
  #[must_use]
  pub(crate) fn with_record_id(mut self, id: Option<i64>) -> Self {
    if let Some(id) = id {
      match &mut self.payload {
        LiveDashboardPayload::EntryRecorded(payload) => payload.id = id,
        LiveDashboardPayload::SpanRecorded(payload) => payload.id = id,
        LiveDashboardPayload::Snapshot(payload) => payload.id = id,
        LiveDashboardPayload::MockInteraction(payload) => payload.id = id,
        LiveDashboardPayload::MockWarning(payload) => payload.id = id,
        _ => {}
      }
    }
    self
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

#[derive(Clone, Debug, Serialize)]
#[serde(untagged)]
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

#[derive(Clone, Debug, Serialize)]
pub struct LiveRunStartedPayload {
  pub app_name: String,
  pub started_at: String,
  pub stove_version: Option<String>,
  pub systems: Vec<String>,
  pub metadata: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveRunEndedPayload {
  pub ended_at: String,
  pub status: String,
  pub total_tests: i32,
  pub passed: i32,
  pub failed: i32,
  pub duration_ms: i64,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveTestStartedPayload {
  pub test_id: String,
  pub test_name: String,
  pub spec_name: String,
  pub test_path: Vec<String>,
  pub started_at: String,
  pub status: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveTestEndedPayload {
  pub test_id: String,
  pub status: String,
  pub duration_ms: i64,
  pub error: Option<String>,
  pub ended_at: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveEntryRecordedPayload {
  pub id: i64,
  pub test_id: String,
  pub timestamp: String,
  pub system: String,
  pub action: String,
  pub result: String,
  pub input: Option<String>,
  pub output: Option<String>,
  pub metadata: Option<String>,
  pub expected: Option<String>,
  pub actual: Option<String>,
  pub error: Option<String>,
  pub trace_id: Option<String>,
  pub assertion_id: String,
  pub attempt_count: i64,
  pub failure_count: i64,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveSpanRecordedPayload {
  pub id: i64,
  pub test_id: Option<String>,
  pub trace_id: String,
  pub span_id: String,
  pub parent_span_id: Option<String>,
  pub operation_name: String,
  pub service_name: String,
  pub start_time_nanos: i64,
  pub end_time_nanos: i64,
  pub status: String,
  pub attributes: Option<String>,
  pub exception_type: Option<String>,
  pub exception_message: Option<String>,
  pub exception_stack_trace: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveSnapshotPayload {
  pub id: i64,
  pub test_id: String,
  pub system: String,
  pub state_json: String,
  pub summary: String,
  pub captured_at: Option<String>,
  pub trigger: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveMockInteractionPayload {
  pub id: i64,
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub protocol: String,
  pub method: String,
  pub target: String,
  pub matched: bool,
  pub stub_id: Option<String>,
  pub attribution: String,
  pub request_body: Option<String>,
  pub request_body_truncated: bool,
  pub response_body: Option<String>,
  pub response_body_truncated: bool,
  pub status: String,
  pub latency_ms: Option<i64>,
  pub near_misses: Vec<String>,
  pub trace_id: Option<String>,
  pub scenario_name: Option<String>,
  pub scenario_state: Option<String>,
  pub next_scenario_state: Option<String>,
  pub configured_delay_ms: Option<i64>,
  pub fault: Option<String>,
  pub client_deadline_ms: Option<i64>,
}

#[derive(Clone, Debug, Serialize)]
pub struct LiveMockWarningPayload {
  pub id: i64,
  pub test_id: Option<String>,
  pub timestamp: String,
  pub system: String,
  pub kind: String,
  pub message: String,
  pub stub_id: Option<String>,
  pub target: Option<String>,
}

fn live_record_id(seq: u64) -> i64 {
  let bounded = seq.min(i64::MAX as u64);
  -bounded.cast_signed()
}

#[cfg(test)]
mod tests;
