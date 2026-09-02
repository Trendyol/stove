//! gRPC service that receives dashboard events from Stove test processes,
//! commits them transactionally, then fans committed events out to the live UI.
//!
//! The implementation is split across this module: `mod.rs` owns the service
//! struct, the tonic trait impl, and the orchestration that dispatches each
//! incoming event; `preparers.rs` owns the per-event preparation logic;
//! `convert.rs` owns the protobuf↔internal conversion helpers.

mod convert;
mod preparers;

use std::sync::Arc;

use tonic::Request;
use tonic::Response;
use tonic::Status;
use tonic::Streaming;
use tracing::warn;
use uuid::Uuid;

use crate::error::Result as AppResult;
use crate::ingest::{CommitOutcome, EventIdentity, PreparedDashboardEvent};
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::repository::Repository;

use self::convert::to_status;

/// gRPC service implementation that receives events from Stove test processes.
pub struct DashboardEventServiceImpl {
  repository: Arc<Repository>,
  sse_manager: Arc<SseManager>,
}

impl DashboardEventServiceImpl {
  #[must_use]
  pub fn new(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Self {
    Self {
      repository,
      sse_manager,
    }
  }

  /// Commit an event transactionally before acknowledging it to the producer.
  fn process_event(
    &self,
    event: &proto::DashboardEvent,
  ) -> std::result::Result<CommitOutcome, Status> {
    let Some(prepared) = self.prepare_event(event).map_err(to_status)? else {
      return Err(Status::invalid_argument("dashboard event has no payload"));
    };
    let identity = EventIdentity {
      event_id: if event.event_id.is_empty() {
        Uuid::new_v4().to_string()
      } else {
        event.event_id.clone()
      },
      sequence: (event.sequence > 0).then_some(event.sequence),
    };
    let outcome = self
      .repository
      .commit_dashboard_event(&identity, &prepared)
      .map_err(to_status)?;
    if !outcome.duplicate {
      self.broadcast_committed_events();
    }
    Ok(outcome)
  }

  fn broadcast_committed_events(&self) {
    let mut cursor = self.sse_manager.last_broadcast_id();
    if let Err(error) = crate::sse::relay::broadcast_available(
      self.repository.as_ref(),
      self.sse_manager.as_ref(),
      &mut cursor,
    ) {
      warn!(%error, "Failed to broadcast committed dashboard events");
    }
  }

  fn prepare_event(
    &self,
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
          self
            .repository
            .get_open_assertion(&event.run_id, &inner.test_id, &correlation_key)?;
        preparers::prepare_entry_recorded(&event.run_id, inner, open_assertion)
      }
      proto::dashboard_event::Event::SpanRecorded(inner) => {
        let trace_test_id = self
          .repository
          .get_test_id_for_trace(&event.run_id, &inner.trace_id)?;
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

#[tonic::async_trait]
impl proto::dashboard_event_service_server::DashboardEventService for DashboardEventServiceImpl {
  async fn stream_events(
    &self,
    request: Request<Streaming<proto::DashboardEvent>>,
  ) -> std::result::Result<Response<proto::EventAck>, Status> {
    let mut stream = request.into_inner();
    while let Some(event) = stream.message().await? {
      self.process_event(&event)?;
    }
    Ok(Response::new(proto::EventAck {
      accepted: true,
      ..Default::default()
    }))
  }

  async fn send_event(
    &self,
    request: Request<proto::DashboardEvent>,
  ) -> std::result::Result<Response<proto::EventAck>, Status> {
    let event = request.into_inner();
    let outcome = self.process_event(&event)?;
    Ok(Response::new(proto::EventAck {
      accepted: true,
      event_id: event.event_id,
      sequence: event.sequence,
      duplicate: outcome.duplicate,
    }))
  }
}

#[cfg(test)]
mod tests;
