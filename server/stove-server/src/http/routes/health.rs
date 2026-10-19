use std::time::Duration;

use axum::{Json, extract::State, http::StatusCode};
use serde::Serialize;
use utoipa::ToSchema;

use crate::http::server::AppState;

#[derive(Serialize, ToSchema)]
pub struct HealthResponse {
  pub healthy: bool,
  pub stove_server_version: &'static str,
  pub backend: &'static str,
  pub checked_at: String,
  pub checks: Vec<HealthCheck>,
}

#[derive(Serialize, ToSchema)]
pub struct HealthCheck {
  pub name: &'static str,
  pub healthy: bool,
  pub message: &'static str,
}

#[utoipa::path(
  get,
  path = "/api/v1/health",
  tag = "system",
  responses(
    (status = 200, description = "Server and storage are responding", body = HealthResponse),
    (status = 503, description = "A health check failed or timed out", body = HealthResponse)
  )
)]
pub async fn get_health(State(state): State<AppState>) -> (StatusCode, Json<HealthResponse>) {
  let storage = check_storage(&state).await;
  let healthy = storage.healthy;
  (
    if healthy {
      StatusCode::OK
    } else {
      StatusCode::SERVICE_UNAVAILABLE
    },
    Json(HealthResponse {
      healthy,
      stove_server_version: crate::STOVE_SERVER_VERSION,
      backend: state.repository.backend_kind(),
      checked_at: chrono::Utc::now().to_rfc3339(),
      checks: vec![
        HealthCheck {
          name: "HTTP API",
          healthy: true,
          message: "Server is responding.",
        },
        storage,
      ],
    }),
  )
}

async fn check_storage(state: &AppState) -> HealthCheck {
  // Keep the permit inside the blocking task: a timed-out database query cannot
  // accumulate more workers on subsequent health requests.
  let Ok(Ok(permit)) = tokio::time::timeout(
    Duration::from_secs(1),
    state.health_probe.clone().acquire_owned(),
  )
  .await
  else {
    return HealthCheck {
      name: "Storage",
      healthy: false,
      message: "A storage check is still in progress. Try again shortly.",
    };
  };
  let repository = state.repository.clone();
  let probe = tokio::task::spawn_blocking(move || {
    let _permit = permit;
    repository.check_health()
  });
  let (healthy, message) = match tokio::time::timeout(Duration::from_secs(3), probe).await {
    Ok(Ok(Ok(()))) => (
      true,
      "Dashboard and ingestion database connections are responding.",
    ),
    Ok(Ok(Err(error))) => {
      tracing::warn!(%error, "Storage health check failed");
      (
        false,
        "Database check failed. Check the server logs and database availability.",
      )
    }
    Ok(Err(error)) => {
      tracing::warn!(%error, "Storage health check could not complete");
      (
        false,
        "Storage check could not complete. Check the server logs.",
      )
    }
    Err(_) => (
      false,
      "Database did not respond within 3 seconds. It may be busy or unavailable.",
    ),
  };
  HealthCheck {
    name: "Storage",
    healthy,
    message,
  }
}

#[cfg(test)]
mod tests {
  use std::sync::Arc;

  use super::*;
  use crate::{ingest::EventIngestor, sse::manager::SseManager, storage::repository::Repository};

  fn state() -> AppState {
    let repository = Arc::new(Repository::connect_sqlite(":memory:", 1).unwrap());
    let sse_manager = Arc::new(SseManager::new());
    AppState {
      public_url: None,
      ingestor: EventIngestor::new(repository.clone(), sse_manager.clone()),
      repository,
      sse_manager,
      health_probe: Arc::new(tokio::sync::Semaphore::new(1)),
    }
  }

  #[tokio::test]
  async fn health_reports_pending_probe_and_recovers() {
    let state = state();
    let permit = state.health_probe.clone().acquire_owned().await.unwrap();
    let (status, Json(report)) = get_health(State(state.clone())).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(!report.healthy);
    assert!(report.checks[0].healthy);
    assert!(!report.checks[1].healthy);
    drop(permit);
    let (status, Json(report)) = get_health(State(state)).await;
    assert_eq!(status, StatusCode::OK);
    assert!(report.healthy);
    assert!(report.checks.iter().all(|check| check.healthy));
  }

  #[tokio::test]
  async fn health_times_out_without_accumulating_database_workers() {
    let state = state();
    let repository = state.repository.clone();
    let (locked_tx, locked_rx) = tokio::sync::oneshot::channel();
    let (release_tx, release_rx) = std::sync::mpsc::channel();
    let lock = std::thread::spawn(move || {
      repository.with_read_db_locked(|| {
        locked_tx.send(()).unwrap();
        release_rx.recv().unwrap();
      });
    });
    locked_rx.await.unwrap();
    let (status, Json(report)) = get_health(State(state.clone())).await;
    // Release the lock even if an assertion below fails.
    release_tx.send(()).unwrap();
    lock.join().unwrap();
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(!report.healthy);
    assert!(report.checks[1].message.contains("within 3 seconds"));
    // The permit is held until the timed-out worker actually finishes.
    let permit = tokio::time::timeout(Duration::from_secs(1), state.health_probe.acquire())
      .await
      .unwrap()
      .unwrap();
    drop(permit);
    assert!(get_health(State(state)).await.1.0.healthy);
  }
}
