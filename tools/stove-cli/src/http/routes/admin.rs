use axum::Json;
use axum::extract::State;
use serde::Deserialize;

use crate::error::{AppError, Result};
use crate::http::server::AppState;
use crate::storage::models::{PurgePreview, PurgeResult, StorageStats};

#[derive(Debug, Deserialize)]
pub struct RetentionRequest {
  pub runs_per_app: usize,
}

#[derive(Debug, Deserialize)]
pub struct PurgePreviewRequest {
  pub app_name: Option<String>,
  pub older_than: Option<String>,
  #[serde(default)]
  pub include_running: bool,
}

#[derive(Debug, Deserialize)]
pub struct PurgeRequest {
  pub run_ids: Vec<String>,
  #[serde(default)]
  pub include_running: bool,
}

pub async fn get_admin_status(State(state): State<AppState>) -> Result<Json<StorageStats>> {
  Ok(Json(state.repository.storage_stats()?))
}

pub async fn update_retention(
  State(state): State<AppState>,
  Json(request): Json<RetentionRequest>,
) -> Result<Json<StorageStats>> {
  state.repository.update_retention(request.runs_per_app)?;
  Ok(Json(state.repository.storage_stats()?))
}

pub async fn preview_purge(
  State(state): State<AppState>,
  Json(request): Json<PurgePreviewRequest>,
) -> Result<Json<PurgePreview>> {
  if let Some(older_than) = &request.older_than {
    chrono::DateTime::parse_from_rfc3339(older_than).map_err(|error| {
      AppError::InvalidEvent(format!("older_than must be an RFC 3339 timestamp: {error}"))
    })?;
  }
  Ok(Json(state.repository.preview_purge(
    request.app_name.as_deref(),
    request.older_than.as_deref(),
    request.include_running,
  )?))
}

pub async fn purge_runs(
  State(state): State<AppState>,
  Json(request): Json<PurgeRequest>,
) -> Result<Json<PurgeResult>> {
  Ok(Json(
    state
      .repository
      .purge_runs(&request.run_ids, request.include_running)?,
  ))
}
