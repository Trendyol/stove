//! gRPC service that receives dashboard events from Stove test processes,
//! fans them out to the SSE broadcast for the live UI, and queues them for
//! batched persistence.
//!
//! The implementation is split across this module: `mod.rs` owns the service
//! struct, the tonic trait impl, and the orchestration that dispatches each
//! incoming event; `preparers.rs` owns the per-event preparation logic;
//! `state.rs` owns the in-memory ordering state; `convert.rs` owns the
//! protobuf↔internal conversion helpers.

mod convert;
mod preparers;
mod state;

use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;
use std::time::Duration;

use tonic::Request;
use tonic::Response;
use tonic::Status;
use tonic::Streaming;
use tracing::warn;

use crate::error::Result as AppResult;
use crate::ingest::DEFAULT_MAX_BATCH_DELAY;
use crate::ingest::DEFAULT_MAX_BATCH_SIZE;
use crate::ingest::EventIngestor;
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::repository::Repository;

use self::convert::PreparedDashboardEvent;
use self::convert::to_status;
use self::state::LiveState;

/// gRPC service implementation that receives events from Stove test processes.
pub struct DashboardEventServiceImpl {
  #[allow(dead_code)]
  repository: Arc<Repository>,
  ingestor: EventIngestor,
  sse_manager: Arc<SseManager>,
  next_live_seq: AtomicU64,
  state: Mutex<LiveState>,
}

impl DashboardEventServiceImpl {
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
    let ingestor = EventIngestor::with_config(repository.clone(), max_batch_size, max_batch_delay);
    Self::new_with_ingestor(repository, sse_manager, ingestor)
  }

  #[must_use]
  pub fn new_with_ingestor(
    repository: Arc<Repository>,
    sse_manager: Arc<SseManager>,
    ingestor: EventIngestor,
  ) -> Self {
    Self {
      repository,
      ingestor,
      sse_manager,
      next_live_seq: AtomicU64::new(0),
      state: Mutex::new(LiveState::default()),
    }
  }

  pub async fn flush_pending(&self) -> AppResult<()> {
    self.ingestor.flush_pending().await
  }

  /// Queue persistence work and immediately broadcast the full event to SSE.
  fn process_event(&self, event: &proto::DashboardEvent) -> std::result::Result<(), Status> {
    let Some(prepared) = self.prepare_event(event).map_err(to_status)? else {
      return Ok(());
    };

    self
      .ingestor
      .enqueue(prepared.persisted, prepared.flush)
      .map_err(to_status)?;

    let seq = self.next_live_seq.fetch_add(1, Ordering::Relaxed) + 1;
    let live_event = prepared.live.with_seq(seq);
    match serde_json::to_string(&live_event) {
      Ok(json) => self.sse_manager.broadcast(&json),
      Err(error) => warn!(%error, "Failed to serialize live SSE event"),
    }

    Ok(())
  }

  fn prepare_event(
    &self,
    event: &proto::DashboardEvent,
  ) -> AppResult<Option<PreparedDashboardEvent>> {
    let Some(inner_event) = &event.event else {
      warn!("Received DashboardEvent with no event payload");
      return Ok(None);
    };

    let mut state = self
      .state
      .lock()
      .expect("dashboard live state lock poisoned");
    let prepared = match inner_event {
      proto::dashboard_event::Event::RunStarted(inner) => Ok(preparers::prepare_run_started(
        &mut state,
        &event.run_id,
        inner,
      )),
      proto::dashboard_event::Event::RunEnded(inner) => {
        preparers::prepare_run_ended(&mut state, &event.run_id, inner)
      }
      proto::dashboard_event::Event::TestStarted(inner) => {
        preparers::prepare_test_started(&mut state, &event.run_id, inner)
      }
      proto::dashboard_event::Event::TestEnded(inner) => {
        preparers::prepare_test_ended(&mut state, &event.run_id, inner)
      }
      proto::dashboard_event::Event::EntryRecorded(inner) => {
        preparers::prepare_entry_recorded(&mut state, &event.run_id, inner)
      }
      proto::dashboard_event::Event::SpanRecorded(inner) => {
        preparers::prepare_span_recorded(&mut state, &event.run_id, inner)
      }
      proto::dashboard_event::Event::Snapshot(inner) => {
        preparers::prepare_snapshot(&mut state, &event.run_id, inner)
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
    Ok(Response::new(proto::EventAck { accepted: true }))
  }

  async fn send_event(
    &self,
    request: Request<proto::DashboardEvent>,
  ) -> std::result::Result<Response<proto::EventAck>, Status> {
    self.process_event(&request.into_inner())?;
    Ok(Response::new(proto::EventAck { accepted: true }))
  }
}

#[cfg(test)]
mod tests;
