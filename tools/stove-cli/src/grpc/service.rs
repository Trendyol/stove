use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use tonic::{Request, Response, Status, Streaming};
use tracing::warn;

use crate::error::{AppError, Result as AppResult};
use crate::ingest::{
  DEFAULT_MAX_BATCH_DELAY, DEFAULT_MAX_BATCH_SIZE, EventIngestor, LiveEntryRecordedPayload,
  LivePortalEvent, LivePortalPayload, LiveRunEndedPayload, LiveRunStartedPayload,
  LiveSnapshotPayload, LiveSpanRecordedPayload, LiveTestEndedPayload, LiveTestStartedPayload,
  PersistedPortalEvent,
};
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::models::{NewEntry, NewSpan};
use crate::storage::repository::Repository;

/// gRPC service implementation that receives events from Stove test processes.
pub struct PortalEventServiceImpl {
  #[allow(dead_code)]
  repository: Arc<Repository>,
  ingestor: EventIngestor,
  sse_manager: Arc<SseManager>,
  next_live_seq: AtomicU64,
  state: Mutex<LiveState>,
}

impl PortalEventServiceImpl {
  #[must_use]
  pub fn new(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Self {
    Self::new_with_ingest_config(
      repository,
      sse_manager,
      DEFAULT_MAX_BATCH_SIZE,
      DEFAULT_MAX_BATCH_DELAY,
    )
  }

  #[must_use]
  pub fn new_with_ingest_config(
    repository: Arc<Repository>,
    sse_manager: Arc<SseManager>,
    max_batch_size: usize,
    max_batch_delay: Duration,
  ) -> Self {
    Self {
      repository: repository.clone(),
      ingestor: EventIngestor::with_config(repository, max_batch_size, max_batch_delay),
      sse_manager,
      next_live_seq: AtomicU64::new(0),
      state: Mutex::new(LiveState::default()),
    }
  }

  pub async fn flush_pending(&self) -> AppResult<()> {
    self.ingestor.flush_pending().await
  }

  /// Queue persistence work and immediately broadcast the full event to SSE.
  fn process_event(&self, event: &proto::PortalEvent) -> std::result::Result<(), Status> {
    let Some(prepared) = self.prepare_event(event).map_err(to_status)? else {
      return Ok(());
    };

    self
      .ingestor
      .enqueue(prepared.persisted, prepared.flush_immediately)
      .map_err(to_status)?;

    let seq = self.next_live_seq.fetch_add(1, Ordering::Relaxed) + 1;
    let live_event = prepared.live.with_seq(seq);
    match serde_json::to_string(&live_event) {
      Ok(json) => self.sse_manager.broadcast(&json),
      Err(error) => warn!(%error, "Failed to serialize live SSE event"),
    }

    Ok(())
  }

  fn prepare_event(&self, event: &proto::PortalEvent) -> AppResult<Option<PreparedPortalEvent>> {
    let run_id = event.run_id.clone();
    let Some(inner_event) = &event.event else {
      warn!("Received PortalEvent with no event payload");
      return Ok(None);
    };

    let mut state = self.state.lock().expect("portal live state lock poisoned");

    let prepared = match inner_event {
      proto::portal_event::Event::RunStarted(e) => {
        let started_at = format_timestamp(e.timestamp.as_ref());
        state.runs.insert(run_id.clone());
        PreparedPortalEvent {
          live: LivePortalEvent {
            seq: 0,
            run_id: run_id.clone(),
            event_type: "run_started".to_string(),
            payload: LivePortalPayload::RunStarted(LiveRunStartedPayload {
              app_name: e.app_name.clone(),
              started_at: started_at.clone(),
              systems: e.systems.clone(),
            }),
          },
          persisted: PersistedPortalEvent::RunStarted {
            run_id,
            app_name: e.app_name.clone(),
            started_at,
            systems: e.systems.clone(),
          },
          flush_immediately: false,
        }
      }
      proto::portal_event::Event::RunEnded(e) => {
        ensure_run_known(&state, &run_id)?;
        let ended_at = format_timestamp(e.timestamp.as_ref());
        let status = run_status(e.failed).to_string();
        state.clear_run(&run_id);
        PreparedPortalEvent {
          live: LivePortalEvent {
            seq: 0,
            run_id: run_id.clone(),
            event_type: "run_ended".to_string(),
            payload: LivePortalPayload::RunEnded(LiveRunEndedPayload {
              ended_at: ended_at.clone(),
              status,
              total_tests: e.total_tests,
              passed: e.passed,
              failed: e.failed,
              duration_ms: e.duration_ms,
            }),
          },
          persisted: PersistedPortalEvent::RunEnded {
            run_id,
            ended_at,
            total_tests: e.total_tests,
            passed: e.passed,
            failed: e.failed,
            duration_ms: e.duration_ms,
          },
          flush_immediately: true,
        }
      }
      proto::portal_event::Event::TestStarted(e) => {
        ensure_run_known(&state, &run_id)?;
        let started_at = format_timestamp(e.timestamp.as_ref());
        state.tests.insert((run_id.clone(), e.test_id.clone()));
        PreparedPortalEvent {
          live: LivePortalEvent {
            seq: 0,
            run_id: run_id.clone(),
            event_type: "test_started".to_string(),
            payload: LivePortalPayload::TestStarted(LiveTestStartedPayload {
              test_id: e.test_id.clone(),
              test_name: e.test_name.clone(),
              spec_name: e.spec_name.clone(),
              started_at: started_at.clone(),
              status: "RUNNING".to_string(),
            }),
          },
          persisted: PersistedPortalEvent::TestStarted {
            run_id,
            test_id: e.test_id.clone(),
            test_name: e.test_name.clone(),
            spec_name: e.spec_name.clone(),
            started_at,
          },
          flush_immediately: false,
        }
      }
      proto::portal_event::Event::TestEnded(e) => {
        ensure_test_known(&state, &run_id, &e.test_id)?;
        let ended_at = format_timestamp(e.timestamp.as_ref());
        PreparedPortalEvent {
          live: LivePortalEvent {
            seq: 0,
            run_id: run_id.clone(),
            event_type: "test_ended".to_string(),
            payload: LivePortalPayload::TestEnded(LiveTestEndedPayload {
              test_id: e.test_id.clone(),
              status: e.status.clone(),
              duration_ms: e.duration_ms,
              error: non_empty(&e.error),
              ended_at: ended_at.clone(),
            }),
          },
          persisted: PersistedPortalEvent::TestEnded {
            run_id,
            test_id: e.test_id.clone(),
            status: e.status.clone(),
            duration_ms: e.duration_ms,
            error: non_empty(&e.error),
            ended_at,
          },
          flush_immediately: false,
        }
      }
      proto::portal_event::Event::EntryRecorded(e) => {
        ensure_test_known(&state, &run_id, &e.test_id)?;
        if !e.trace_id.is_empty() {
          state
            .traces
            .insert((run_id.clone(), e.trace_id.clone()), e.test_id.clone());
        }

        let metadata = serde_json::to_string(&e.metadata)?;
        let timestamp = format_timestamp(e.timestamp.as_ref());
        let entry = NewEntry {
          run_id: run_id.clone(),
          test_id: e.test_id.clone(),
          timestamp: timestamp.clone(),
          system: e.system.clone(),
          action: e.action.clone(),
          result: e.result.clone(),
          input: e.input.clone(),
          output: e.output.clone(),
          metadata: metadata.clone(),
          expected: e.expected.clone(),
          actual: e.actual.clone(),
          error: e.error.clone(),
          trace_id: e.trace_id.clone(),
        };

        PreparedPortalEvent {
          live: LivePortalEvent {
            seq: 0,
            run_id: run_id.clone(),
            event_type: "entry_recorded".to_string(),
            payload: LivePortalPayload::EntryRecorded(LiveEntryRecordedPayload {
              id: 0,
              test_id: e.test_id.clone(),
              timestamp,
              system: e.system.clone(),
              action: e.action.clone(),
              result: e.result.clone(),
              input: non_empty(&e.input),
              output: non_empty(&e.output),
              metadata: non_empty(&metadata),
              expected: non_empty(&e.expected),
              actual: non_empty(&e.actual),
              error: non_empty(&e.error),
              trace_id: non_empty(&e.trace_id),
            }),
          },
          persisted: PersistedPortalEvent::EntryRecorded(entry),
          flush_immediately: false,
        }
      }
      proto::portal_event::Event::SpanRecorded(e) => {
        ensure_run_known(&state, &run_id)?;
        let test_id = extract_test_id(&e.attributes).or_else(|| {
          state
            .traces
            .get(&(run_id.clone(), e.trace_id.clone()))
            .cloned()
        });

        let attributes = serde_json::to_string(&e.attributes)?;
        let (exception_type, exception_message, exception_stack_trace) = e
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
          run_id: run_id.clone(),
          trace_id: e.trace_id.clone(),
          span_id: e.span_id.clone(),
          parent_span_id: e.parent_span_id.clone(),
          operation_name: e.operation_name.clone(),
          service_name: e.service_name.clone(),
          start_time_nanos: e.start_time_nanos,
          end_time_nanos: e.end_time_nanos,
          status: e.status.clone(),
          attributes: attributes.clone(),
          exception_type: exception_type.clone(),
          exception_message: exception_message.clone(),
          exception_stack_trace: exception_stack_trace.clone(),
        };

        PreparedPortalEvent {
          live: LivePortalEvent {
            seq: 0,
            run_id,
            event_type: "span_recorded".to_string(),
            payload: LivePortalPayload::SpanRecorded(LiveSpanRecordedPayload {
              id: 0,
              test_id,
              trace_id: e.trace_id.clone(),
              span_id: e.span_id.clone(),
              parent_span_id: non_empty(&e.parent_span_id),
              operation_name: e.operation_name.clone(),
              service_name: e.service_name.clone(),
              start_time_nanos: e.start_time_nanos,
              end_time_nanos: e.end_time_nanos,
              status: e.status.clone(),
              attributes: non_empty(&attributes),
              exception_type: non_empty(&exception_type),
              exception_message: non_empty(&exception_message),
              exception_stack_trace: non_empty(&exception_stack_trace),
            }),
          },
          persisted: PersistedPortalEvent::SpanRecorded(span),
          flush_immediately: false,
        }
      }
      proto::portal_event::Event::Snapshot(e) => {
        ensure_test_known(&state, &run_id, &e.test_id)?;
        PreparedPortalEvent {
          live: LivePortalEvent {
            seq: 0,
            run_id: run_id.clone(),
            event_type: "snapshot".to_string(),
            payload: LivePortalPayload::Snapshot(LiveSnapshotPayload {
              id: 0,
              test_id: e.test_id.clone(),
              system: e.system.clone(),
              state_json: e.state_json.clone(),
              summary: e.summary.clone(),
            }),
          },
          persisted: PersistedPortalEvent::Snapshot {
            run_id,
            test_id: e.test_id.clone(),
            system: e.system.clone(),
            state_json: e.state_json.clone(),
            summary: e.summary.clone(),
          },
          flush_immediately: false,
        }
      }
    };

    Ok(Some(prepared))
  }
}

#[tonic::async_trait]
impl proto::portal_event_service_server::PortalEventService for PortalEventServiceImpl {
  async fn stream_events(
    &self,
    request: Request<Streaming<proto::PortalEvent>>,
  ) -> std::result::Result<Response<proto::EventAck>, Status> {
    let mut stream = request.into_inner();
    while let Some(event) = stream.message().await? {
      self.process_event(&event)?;
    }
    Ok(Response::new(proto::EventAck { accepted: true }))
  }

  async fn send_event(
    &self,
    request: Request<proto::PortalEvent>,
  ) -> std::result::Result<Response<proto::EventAck>, Status> {
    self.process_event(&request.into_inner())?;
    Ok(Response::new(proto::EventAck { accepted: true }))
  }
}

struct PreparedPortalEvent {
  live: LivePortalEvent,
  persisted: PersistedPortalEvent,
  flush_immediately: bool,
}

#[derive(Default)]
struct LiveState {
  runs: HashSet<String>,
  tests: HashSet<(String, String)>,
  traces: HashMap<(String, String), String>,
}

impl LiveState {
  fn clear_run(&mut self, run_id: &str) {
    self.runs.remove(run_id);
    self.tests.retain(|(known_run_id, _)| known_run_id != run_id);
    self.traces.retain(|(known_run_id, _), _| known_run_id != run_id);
  }
}

fn ensure_run_known(state: &LiveState, run_id: &str) -> AppResult<()> {
  if state.runs.contains(run_id) {
    Ok(())
  } else {
    Err(AppError::InvalidEvent(format!(
      "received event for unknown run `{run_id}`"
    )))
  }
}

fn ensure_test_known(state: &LiveState, run_id: &str, test_id: &str) -> AppResult<()> {
  ensure_run_known(state, run_id)?;
  if state.tests.contains(&(run_id.to_string(), test_id.to_string())) {
    Ok(())
  } else {
    Err(AppError::InvalidEvent(format!(
      "received event for unknown test `{test_id}` in run `{run_id}`"
    )))
  }
}

fn extract_test_id(attributes: &HashMap<String, String>) -> Option<String> {
  [
    "x-stove-test-id",
    "X-Stove-Test-Id",
    "stove.test.id",
    "stove_test_id",
  ]
  .iter()
  .find_map(|key| attributes.get(*key))
  .cloned()
}

fn run_status(failed: i32) -> &'static str {
  if failed > 0 { "FAILED" } else { "PASSED" }
}

fn format_timestamp(ts: Option<&prost_types::Timestamp>) -> String {
  ts.map(|timestamp| {
    #[allow(clippy::cast_sign_loss)]
    chrono::DateTime::from_timestamp(timestamp.seconds, timestamp.nanos as u32)
      .map(|datetime| datetime.to_rfc3339())
      .unwrap_or_default()
  })
  .unwrap_or_default()
}

fn non_empty(value: &str) -> Option<String> {
  if value.is_empty() {
    None
  } else {
    Some(value.to_string())
  }
}

#[allow(clippy::needless_pass_by_value)]
fn to_status(error: AppError) -> Status {
  match error {
    AppError::InvalidEvent(message) => Status::invalid_argument(message),
    other => Status::internal(other.to_string()),
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::storage::database::Database;

  fn test_service() -> PortalEventServiceImpl {
    let db = Database::open(":memory:").unwrap();
    let repo = Arc::new(Repository::new(db));
    let sse = Arc::new(SseManager::new());
    PortalEventServiceImpl::new_with_ingest_config(repo, sse, 50, Duration::from_secs(60))
  }

  fn ts(seconds: i64) -> Option<prost_types::Timestamp> {
    Some(prost_types::Timestamp { seconds, nanos: 0 })
  }

  #[tokio::test]
  async fn no_broadcast_on_invalid_event_order() {
    let svc = test_service();
    let mut rx = svc.sse_manager.subscribe();

    let result = svc.process_event(&proto::PortalEvent {
      run_id: "nonexistent-run".to_string(),
      event: Some(proto::portal_event::Event::TestStarted(
        proto::TestStartedEvent {
          test_id: "t-1".to_string(),
          test_name: "orphan test".to_string(),
          spec_name: "Spec".to_string(),
          timestamp: ts(1_704_067_200),
        },
      )),
    });

    assert!(result.is_err(), "invalid event ordering should be rejected");
    assert!(rx.try_recv().is_err(), "invalid events must not be broadcast");
    assert!(svc.repository.get_runs(None).unwrap().is_empty());
    svc.flush_pending().await.unwrap();
    assert!(svc.repository.get_runs(None).unwrap().is_empty());
  }

  #[tokio::test]
  async fn broadcast_fires_before_batch_flush() {
    let svc = test_service();
    let mut rx = svc.sse_manager.subscribe();

    svc
      .process_event(&proto::PortalEvent {
        run_id: "run-1".to_string(),
        event: Some(proto::portal_event::Event::RunStarted(
          proto::RunStartedEvent {
            timestamp: ts(1_704_067_200),
            app_name: "my-api".to_string(),
            systems: vec!["HTTP".to_string()],
          },
        )),
      })
      .unwrap();

    let msg = rx.try_recv().expect("broadcast should be sent on success");
    assert!(msg.contains("run_started"));
    assert!(
      svc.repository.get_runs(None).unwrap().is_empty(),
      "run should not be visible in SQLite before an explicit flush"
    );

    svc.flush_pending().await.unwrap();

    let runs = svc.repository.get_runs(None).unwrap();
    assert_eq!(runs.len(), 1);
  }

  #[tokio::test]
  async fn process_run_started_event() {
    let svc = test_service();
    let event = proto::PortalEvent {
      run_id: "run-1".to_string(),
      event: Some(proto::portal_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(prost_types::Timestamp {
            seconds: 1_704_067_200,
            nanos: 0,
          }),
          app_name: "product-api".to_string(),
          systems: vec!["HTTP".to_string(), "Kafka".to_string()],
        },
      )),
    };

    svc.process_event(&event).unwrap();
    svc.flush_pending().await.unwrap();

    let runs = svc.repository.get_runs(None).unwrap();
    assert_eq!(runs.len(), 1);
    assert_eq!(runs[0].app_name, "product-api");
  }

  #[tokio::test]
  async fn process_full_lifecycle() {
    let svc = test_service();

    svc
      .process_event(&proto::PortalEvent {
        run_id: "run-1".to_string(),
        event: Some(proto::portal_event::Event::RunStarted(
          proto::RunStartedEvent {
            timestamp: Some(prost_types::Timestamp {
              seconds: 1_704_067_200,
              nanos: 0,
            }),
            app_name: "test-app".to_string(),
            systems: vec![],
          },
        )),
      })
      .unwrap();

    svc
      .process_event(&proto::PortalEvent {
        run_id: "run-1".to_string(),
        event: Some(proto::portal_event::Event::TestStarted(
          proto::TestStartedEvent {
            test_id: "test-1".to_string(),
            test_name: "my test".to_string(),
            spec_name: "MySpec".to_string(),
            timestamp: Some(prost_types::Timestamp {
              seconds: 1_704_067_201,
              nanos: 0,
            }),
          },
        )),
      })
      .unwrap();

    svc
      .process_event(&proto::PortalEvent {
        run_id: "run-1".to_string(),
        event: Some(proto::portal_event::Event::EntryRecorded(
          proto::EntryRecordedEvent {
            test_id: "test-1".to_string(),
            timestamp: Some(prost_types::Timestamp {
              seconds: 1_704_067_202,
              nanos: 0,
            }),
            system: "HTTP".to_string(),
            action: "GET /api".to_string(),
            result: "PASSED".to_string(),
            input: String::new(),
            output: String::new(),
            metadata: std::collections::HashMap::default(),
            expected: String::new(),
            actual: String::new(),
            error: String::new(),
            trace_id: String::new(),
          },
        )),
      })
      .unwrap();

    svc
      .process_event(&proto::PortalEvent {
        run_id: "run-1".to_string(),
        event: Some(proto::portal_event::Event::TestEnded(
          proto::TestEndedEvent {
            test_id: "test-1".to_string(),
            status: "PASSED".to_string(),
            duration_ms: 500,
            error: String::new(),
            timestamp: Some(prost_types::Timestamp {
              seconds: 1_704_067_203,
              nanos: 0,
            }),
          },
        )),
      })
      .unwrap();

    svc
      .process_event(&proto::PortalEvent {
        run_id: "run-1".to_string(),
        event: Some(proto::portal_event::Event::RunEnded(proto::RunEndedEvent {
          timestamp: Some(prost_types::Timestamp {
            seconds: 1_704_067_210,
            nanos: 0,
          }),
          total_tests: 1,
          passed: 1,
          failed: 0,
          duration_ms: 10000,
        })),
      })
      .unwrap();

    svc.flush_pending().await.unwrap();

    let runs = svc.repository.get_runs(None).unwrap();
    assert_eq!(runs.len(), 1);
    assert_eq!(runs[0].status, crate::storage::models::RunStatus::Passed);

    let tests = svc.repository.get_tests_for_run("run-1").unwrap();
    assert_eq!(tests.len(), 1);
    assert_eq!(tests[0].status, crate::storage::models::TestStatus::Passed);

    let entries = svc.repository.get_entries("run-1", "test-1").unwrap();
    assert_eq!(entries.len(), 1);
  }
}
