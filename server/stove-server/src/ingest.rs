mod convert;
mod events;
mod preparers;

use std::sync::Arc;

use tracing::warn;
use uuid::Uuid;

use crate::error::AppError;
use crate::error::Result as AppResult;
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::repository::Repository;

pub use events::*;

/// Receives dashboard events from Stove test processes, commits them
/// transactionally, then fans committed events out to the live UI.
///
/// Shared by the gRPC service and the HTTP ingestion endpoint so both
/// transports apply identical validation, deduplication, and broadcast rules.
#[derive(Clone)]
pub struct EventIngestor {
  repository: Arc<Repository>,
  sse_manager: Arc<SseManager>,
}

impl EventIngestor {
  #[must_use]
  pub fn new(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Self {
    Self {
      repository,
      sse_manager,
    }
  }

  /// Commit an event transactionally before acknowledging it to the producer.
  pub(crate) fn ingest(&self, event: &proto::DashboardEvent) -> AppResult<proto::EventAck> {
    let prepared = self.prepare_event(event)?;
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
      .commit_dashboard_event(&identity, &prepared)?;
    if !outcome.duplicate {
      self.sse_manager.notify_commit();
    }
    Ok(proto::EventAck {
      event_id: event.event_id.clone(),
      sequence: event.sequence,
      duplicate: outcome.duplicate,
      ..Self::accepted_ack()
    })
  }

  pub(crate) fn accepted_ack() -> proto::EventAck {
    proto::EventAck {
      accepted: true,
      ..Default::default()
    }
  }

  fn prepare_event(&self, event: &proto::DashboardEvent) -> AppResult<PreparedDashboardEvent> {
    let Some(inner_event) = &event.event else {
      warn!("Received DashboardEvent with no event payload");
      return Err(AppError::InvalidEvent(
        "dashboard event has no payload".to_string(),
      ));
    };

    match inner_event {
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
        preparers::prepare_test_ended(&event.run_id, inner)
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
    }
  }
}

#[cfg(test)]
mod tests;
