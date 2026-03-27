use std::sync::Arc;

use tonic::{Request, Response, Status, Streaming};
use tracing::warn;

use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::models::{NewEntry, NewSpan};
use crate::storage::repository::Repository;

/// gRPC service implementation that receives events from Stove test processes.
pub struct PortalEventServiceImpl {
  repository: Arc<Repository>,
  sse_manager: Arc<SseManager>,
}

impl PortalEventServiceImpl {
  #[must_use]
  pub fn new(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Self {
    Self {
      repository,
      sse_manager,
    }
  }

  /// Process a single portal event by dispatching to the appropriate repository method
  /// and broadcasting to SSE clients.
  fn process_event(&self, event: &proto::PortalEvent) -> Result<(), Status> {
    if let Ok(json) = serde_json::to_string(&EventJson::from(event)) {
      self.sse_manager.broadcast(&json);
    }

    let run_id = &event.run_id;
    match &event.event {
      Some(proto::portal_event::Event::RunStarted(e)) => {
        let ts = format_timestamp(e.timestamp.as_ref());
        self
          .repository
          .save_run_start(run_id, &e.app_name, &ts, &e.systems)
      }
      Some(proto::portal_event::Event::RunEnded(e)) => {
        let ts = format_timestamp(e.timestamp.as_ref());
        self.repository.save_run_end(
          run_id,
          &ts,
          e.total_tests,
          e.passed,
          e.failed,
          e.duration_ms,
        )
      }
      Some(proto::portal_event::Event::TestStarted(e)) => {
        let ts = format_timestamp(e.timestamp.as_ref());
        self
          .repository
          .save_test_start(run_id, &e.test_id, &e.test_name, &e.spec_name, &ts)
      }
      Some(proto::portal_event::Event::TestEnded(e)) => {
        let ts = format_timestamp(e.timestamp.as_ref());
        self
          .repository
          .save_test_end(run_id, &e.test_id, &e.status, e.duration_ms, &e.error, &ts)
      }
      Some(proto::portal_event::Event::EntryRecorded(e)) => self.save_entry(run_id, e),
      Some(proto::portal_event::Event::SpanRecorded(e)) => self.save_span(run_id, e),
      Some(proto::portal_event::Event::Snapshot(e)) => {
        self
          .repository
          .save_snapshot(run_id, &e.test_id, &e.system, &e.state_json, &e.summary)
      }
      None => {
        warn!("Received PortalEvent with no event payload");
        return Ok(());
      }
    }
    .map_err(to_status)
  }

  fn save_entry(&self, run_id: &str, e: &proto::EntryRecordedEvent) -> crate::error::Result<()> {
    let metadata = serde_json::to_string(&e.metadata).unwrap_or_default();
    self.repository.save_entry(&NewEntry {
      run_id: run_id.into(),
      test_id: e.test_id.clone(),
      timestamp: format_timestamp(e.timestamp.as_ref()),
      system: e.system.clone(),
      action: e.action.clone(),
      result: e.result.clone(),
      input: e.input.clone(),
      output: e.output.clone(),
      metadata,
      expected: e.expected.clone(),
      actual: e.actual.clone(),
      error: e.error.clone(),
      trace_id: e.trace_id.clone(),
    })
  }

  fn save_span(&self, run_id: &str, e: &proto::SpanRecordedEvent) -> crate::error::Result<()> {
    let (ex_type, ex_msg, ex_trace) = e
      .exception
      .as_ref()
      .map(|ex| {
        (
          ex.r#type.clone(),
          ex.message.clone(),
          ex.stack_trace.join("\n"),
        )
      })
      .unwrap_or_default();

    self.repository.save_span(&NewSpan {
      run_id: run_id.into(),
      trace_id: e.trace_id.clone(),
      span_id: e.span_id.clone(),
      parent_span_id: e.parent_span_id.clone(),
      operation_name: e.operation_name.clone(),
      service_name: e.service_name.clone(),
      start_time_nanos: e.start_time_nanos,
      end_time_nanos: e.end_time_nanos,
      status: e.status.clone(),
      attributes: serde_json::to_string(&e.attributes).unwrap_or_default(),
      exception_type: ex_type,
      exception_message: ex_msg,
      exception_stack_trace: ex_trace,
    })
  }
}

#[tonic::async_trait]
impl proto::portal_event_service_server::PortalEventService for PortalEventServiceImpl {
  async fn stream_events(
    &self,
    request: Request<Streaming<proto::PortalEvent>>,
  ) -> Result<Response<proto::EventAck>, Status> {
    let mut stream = request.into_inner();
    while let Some(event) = stream.message().await? {
      self.process_event(&event)?;
    }
    Ok(Response::new(proto::EventAck { accepted: true }))
  }

  async fn send_event(
    &self,
    request: Request<proto::PortalEvent>,
  ) -> Result<Response<proto::EventAck>, Status> {
    self.process_event(&request.into_inner())?;
    Ok(Response::new(proto::EventAck { accepted: true }))
  }
}

/// Minimal JSON representation of a portal event for SSE broadcasting.
#[derive(serde::Serialize)]
struct EventJson {
  run_id: String,
  event_type: String,
}

impl From<&proto::PortalEvent> for EventJson {
  fn from(event: &proto::PortalEvent) -> Self {
    let event_type = match &event.event {
      Some(proto::portal_event::Event::RunStarted(_)) => "run_started",
      Some(proto::portal_event::Event::RunEnded(_)) => "run_ended",
      Some(proto::portal_event::Event::TestStarted(_)) => "test_started",
      Some(proto::portal_event::Event::TestEnded(_)) => "test_ended",
      Some(proto::portal_event::Event::EntryRecorded(_)) => "entry_recorded",
      Some(proto::portal_event::Event::SpanRecorded(_)) => "span_recorded",
      Some(proto::portal_event::Event::Snapshot(_)) => "snapshot",
      None => "unknown",
    };
    Self {
      run_id: event.run_id.clone(),
      event_type: event_type.to_string(),
    }
  }
}

fn format_timestamp(ts: Option<&prost_types::Timestamp>) -> String {
  ts.map(|t| {
    #[allow(clippy::cast_sign_loss)]
    chrono::DateTime::from_timestamp(t.seconds, t.nanos as u32)
      .map(|dt| dt.to_rfc3339())
      .unwrap_or_default()
  })
  .unwrap_or_default()
}

#[allow(clippy::needless_pass_by_value)]
fn to_status(e: crate::error::AppError) -> Status {
  Status::internal(e.to_string())
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::storage::database::Database;

  fn test_service() -> PortalEventServiceImpl {
    let db = Database::open(":memory:").unwrap();
    let repo = Arc::new(Repository::new(db));
    let sse = Arc::new(SseManager::new());
    PortalEventServiceImpl::new(repo, sse)
  }

  #[test]
  fn process_run_started_event() {
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

    let runs = svc.repository.get_runs(None).unwrap();
    assert_eq!(runs.len(), 1);
    assert_eq!(runs[0].app_name, "product-api");
  }

  #[test]
  fn process_full_lifecycle() {
    let svc = test_service();

    // Run started
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

    // Test started
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

    // Entry recorded
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

    // Test ended
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

    // Run ended
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

    // Verify
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
