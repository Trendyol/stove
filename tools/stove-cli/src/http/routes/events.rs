//! HTTP ingestion endpoint for dashboard events.
//!
//! Accepts the same `DashboardEvent` protobuf message the gRPC `SendEvent`
//! RPC consumes, encoded as raw protobuf bytes (`application/x-protobuf`),
//! and responds with the `EventAck` protobuf message.
//!
//! This transport exists for deployments where the CLI sits behind an
//! HTTPS-only ingress or API gateway that cannot forward plaintext gRPC —
//! the events travel as an ordinary HTTPS POST instead.

use axum::body::Bytes;
use axum::extract::State;
use axum::http::header;
use axum::response::IntoResponse;
use prost::Message;

use crate::error::{AppError, Result};
use crate::http::server::AppState;
use crate::proto;

/// `POST /api/v1/events` — ingest a single protobuf-encoded `DashboardEvent`.
#[utoipa::path(
  post,
  path = "/api/v1/events",
  tag = "ingestion",
  request_body(
    content = inline(crate::http::openapi::ProtobufBody),
    content_type = "application/x-protobuf",
    description = "Binary stove.dashboard.v1.DashboardEvent generated from stove-dashboard-api"
  ),
  responses(
    (
      status = 200,
      description = "Event committed or recognized as a duplicate",
      body = inline(crate::http::openapi::ProtobufBody),
      content_type = "application/x-protobuf"
    ),
    (status = 400, description = "Malformed protobuf or invalid dashboard event"),
    (status = 500, description = "Event could not be persisted")
  )
)]
pub async fn post_event(State(state): State<AppState>, body: Bytes) -> Result<impl IntoResponse> {
  let event = proto::DashboardEvent::decode(body.as_ref())
    .map_err(|error| AppError::InvalidEvent(format!("invalid protobuf DashboardEvent: {error}")))?;
  let outcome = state.ingestor.process_event(&event)?;
  let ack = proto::EventAck {
    accepted: true,
    event_id: event.event_id,
    sequence: event.sequence,
    duplicate: outcome.duplicate,
  };
  Ok((
    [(header::CONTENT_TYPE, "application/x-protobuf")],
    ack.encode_to_vec(),
  ))
}

#[cfg(test)]
mod tests {
  use std::sync::Arc;

  use prost::Message;

  use super::*;
  use crate::http::server::create_router;
  use crate::sse::manager::SseManager;
  use crate::storage::repository::Repository;

  async fn spawn_server() -> (String, Arc<Repository>) {
    let repository = Arc::new(Repository::connect_sqlite(":memory:", 1).unwrap());
    let sse_manager = Arc::new(SseManager::new());
    let router = create_router(repository.clone(), sse_manager);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    tokio::spawn(async move {
      axum::serve(listener, router).await.unwrap();
    });
    (format!("http://{address}"), repository)
  }

  fn run_started_event(run_id: &str) -> proto::DashboardEvent {
    proto::DashboardEvent {
      run_id: run_id.to_string(),
      event_id: "event-1".to_string(),
      sequence: 1,
      event: Some(proto::dashboard_event::Event::RunStarted(
        proto::RunStartedEvent {
          timestamp: Some(prost_types::Timestamp {
            seconds: 1_704_067_200,
            nanos: 0,
          }),
          app_name: "http-ingested-app".to_string(),
          systems: vec!["HTTP".to_string()],
          stove_version: "0.27.0".to_string(),
          metadata: [("team".to_string(), "productivity".to_string())].into(),
        },
      )),
    }
  }

  #[tokio::test]
  async fn post_event_accepts_protobuf_dashboard_event() {
    let (base_url, repository) = spawn_server().await;

    let response = reqwest::Client::new()
      .post(format!("{base_url}/api/v1/events"))
      .header("content-type", "application/x-protobuf")
      .body(run_started_event("run-http-1").encode_to_vec())
      .send()
      .await
      .unwrap();

    assert_eq!(response.status(), reqwest::StatusCode::OK);
    let ack = proto::EventAck::decode(response.bytes().await.unwrap().as_ref()).unwrap();
    assert!(ack.accepted);
    assert_eq!(ack.event_id, "event-1");
    assert_eq!(ack.sequence, 1);
    assert!(!ack.duplicate);

    let runs = repository.get_runs(None).unwrap();
    assert_eq!(runs.len(), 1);
    assert_eq!(runs[0].app_name, "http-ingested-app");
    assert_eq!(
      runs[0].metadata.get("team").map(String::as_str),
      Some("productivity")
    );
  }

  #[tokio::test]
  async fn post_event_marks_replayed_event_as_duplicate() {
    let (base_url, repository) = spawn_server().await;
    let client = reqwest::Client::new();

    let post = || {
      let body = run_started_event("run-http-dup").encode_to_vec();
      client
        .post(format!("{base_url}/api/v1/events"))
        .body(body)
        .send()
    };

    let first = post().await.unwrap();
    let first_ack = proto::EventAck::decode(first.bytes().await.unwrap().as_ref()).unwrap();
    assert!(first_ack.accepted);
    assert!(!first_ack.duplicate);

    let second = post().await.unwrap();
    let second_ack = proto::EventAck::decode(second.bytes().await.unwrap().as_ref()).unwrap();
    assert!(second_ack.accepted);
    assert!(second_ack.duplicate);

    assert_eq!(repository.get_runs(None).unwrap().len(), 1);
  }

  #[tokio::test]
  async fn post_event_rejects_malformed_body() {
    let (base_url, _) = spawn_server().await;

    let response = reqwest::Client::new()
      .post(format!("{base_url}/api/v1/events"))
      .body(vec![0xFF_u8, 0xFF, 0xFF])
      .send()
      .await
      .unwrap();

    assert_eq!(response.status(), reqwest::StatusCode::BAD_REQUEST);
  }

  #[tokio::test]
  async fn post_event_rejects_event_without_payload() {
    let (base_url, _) = spawn_server().await;
    let empty = proto::DashboardEvent {
      run_id: "run-http-empty".to_string(),
      ..Default::default()
    };

    let response = reqwest::Client::new()
      .post(format!("{base_url}/api/v1/events"))
      .body(empty.encode_to_vec())
      .send()
      .await
      .unwrap();

    assert_eq!(response.status(), reqwest::StatusCode::BAD_REQUEST);
  }

  #[tokio::test]
  async fn swagger_ui_documents_protobuf_ingestion() {
    let repository = Arc::new(Repository::connect_sqlite(":memory:", 1).unwrap());
    let router = axum::Router::new().nest(
      "/gateway/stove",
      create_router(repository, Arc::new(SseManager::new())),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base_url = format!("http://{}/gateway/stove", listener.local_addr().unwrap());
    tokio::spawn(async move {
      axum::serve(listener, router).await.unwrap();
    });
    let client = reqwest::Client::new();

    let document: serde_json::Value = client
      .get(format!("{base_url}/api-docs/openapi.json"))
      .send()
      .await
      .unwrap()
      .json()
      .await
      .unwrap();
    assert!(
      document["paths"]["/api/v1/events"]["post"]["requestBody"]["content"]
        ["application/x-protobuf"]
        .is_object()
    );
    assert_eq!(
      document["paths"]["/api/v1/events"]["post"]["requestBody"]["content"]["application/x-protobuf"]
        ["schema"]["type"],
      "string"
    );
    assert_eq!(
      document["paths"]["/api/v1/events"]["post"]["requestBody"]["content"]["application/x-protobuf"]
        ["schema"]["format"],
      "binary"
    );
    assert!(
      document["paths"]["/api/v1/events"]["post"]["responses"]["200"]["content"]
        ["application/x-protobuf"]
        .is_object()
    );
    assert_eq!(
      document["paths"]["/api/v1/events"]["post"]["responses"]["200"]["content"]["application/x-protobuf"]
        ["schema"]["format"],
      "binary"
    );
    assert_eq!(document["servers"][0]["url"], "..");
    assert_eq!(document["paths"].as_object().unwrap().len(), 25);
    assert!(document["paths"]["/api/v1/runs"].is_object());
    assert!(document["paths"]["/api/v1/admin/status"].is_object());
    assert!(document["paths"]["/api/v1/events/stream"].is_object());

    let ui = client
      .get(format!("{base_url}/swagger-ui/swagger-initializer.js"))
      .send()
      .await
      .unwrap()
      .text()
      .await
      .unwrap();
    assert!(ui.contains("../api-docs/openapi.json"));
  }
}
