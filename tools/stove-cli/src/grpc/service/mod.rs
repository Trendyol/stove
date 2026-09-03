//! gRPC service that receives dashboard events from Stove test processes.
//!
//! This module is a transport-thin wrapper around
//! [`EventIngestor`](crate::ingest::EventIngestor), which owns the event
//! preparation, transactional commit, and live broadcast orchestration shared
//! with the HTTP ingestion endpoint.

use std::sync::Arc;

use tonic::Request;
use tonic::Response;
use tonic::Status;
use tonic::Streaming;

use crate::error::AppError;
use crate::ingest::EventIngestor;
use crate::proto;
use crate::sse::manager::SseManager;
use crate::storage::repository::Repository;

/// gRPC service implementation that receives events from Stove test processes.
pub struct DashboardEventServiceImpl {
  ingestor: EventIngestor,
}

impl DashboardEventServiceImpl {
  #[must_use]
  pub fn new(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Self {
    Self {
      ingestor: EventIngestor::new(repository, sse_manager),
    }
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
      self.ingestor.process_event(&event).map_err(to_status)?;
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
    let outcome = self.ingestor.process_event(&event).map_err(to_status)?;
    Ok(Response::new(proto::EventAck {
      accepted: true,
      event_id: event.event_id,
      sequence: event.sequence,
      duplicate: outcome.duplicate,
    }))
  }
}

#[allow(clippy::needless_pass_by_value)]
fn to_status(error: AppError) -> Status {
  match error {
    AppError::InvalidEvent(message) => Status::invalid_argument(message),
    AppError::Database(_)
    | AppError::Diesel(_)
    | AppError::DieselConnection(_)
    | AppError::Migration(_)
    | AppError::Postgres(_)
    | AppError::PostgresTls(_)
    | AppError::GrpcTransport(_)
    | AppError::Serialization(_)
    | AppError::Startup(_) => Status::internal(error.to_string()),
  }
}
