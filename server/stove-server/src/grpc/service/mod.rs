//! gRPC service that receives dashboard events from Stove test processes.
//!
//! This module is a transport-thin wrapper around
//! [`EventIngestor`](crate::ingest::EventIngestor), which owns the event
//! preparation, transactional commit, and live broadcast orchestration shared
//! with the HTTP ingestion endpoint.

use tonic::Request;
use tonic::Response;
use tonic::Status;
use tonic::Streaming;

use crate::error::AppError;
use crate::ingest::EventIngestor;
use crate::proto;

/// gRPC service implementation that receives events from Stove test processes.
pub struct DashboardEventServiceImpl {
  ingestor: EventIngestor,
}

impl DashboardEventServiceImpl {
  #[must_use]
  pub fn new(ingestor: EventIngestor) -> Self {
    Self { ingestor }
  }
}

#[tonic::async_trait]
impl proto::dashboard_event_service_server::DashboardEventService for DashboardEventServiceImpl {
  async fn send_batch(
    &self,
    request: Request<proto::DashboardEventBatch>,
  ) -> std::result::Result<Response<proto::BatchAck>, Status> {
    self
      .ingestor
      .ingest_batch(request.into_inner())
      .await
      .map(Response::new)
      .map_err(to_status)
  }

  async fn stream_events(
    &self,
    request: Request<Streaming<proto::DashboardEvent>>,
  ) -> std::result::Result<Response<proto::EventAck>, Status> {
    let mut stream = request.into_inner();
    while let Some(event) = stream.message().await? {
      self.ingestor.ingest_async(event).await.map_err(to_status)?;
    }
    Ok(Response::new(EventIngestor::accepted_ack()))
  }

  async fn send_event(
    &self,
    request: Request<proto::DashboardEvent>,
  ) -> std::result::Result<Response<proto::EventAck>, Status> {
    let event = request.into_inner();
    self
      .ingestor
      .ingest_async(event)
      .await
      .map(Response::new)
      .map_err(to_status)
  }
}

#[allow(clippy::needless_pass_by_value)]
fn to_status(error: AppError) -> Status {
  match error {
    AppError::Overloaded => Status::resource_exhausted(error.to_string()),
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
