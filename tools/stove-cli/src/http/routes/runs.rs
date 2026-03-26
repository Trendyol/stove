use axum::Json;
use axum::extract::{Path, Query, State};
use serde::Deserialize;

use crate::http::server::AppState;
use crate::storage::models::{AppSummary, Run};

#[derive(Deserialize)]
pub struct RunsQuery {
  pub app: Option<String>,
}

pub async fn get_apps(
  State(state): State<AppState>,
) -> Result<Json<Vec<AppSummary>>, crate::error::AppError> {
  let apps = state.repository.get_apps()?;
  Ok(Json(apps))
}

pub async fn get_runs(
  State(state): State<AppState>,
  Query(query): Query<RunsQuery>,
) -> Result<Json<Vec<Run>>, crate::error::AppError> {
  let runs = state.repository.get_runs(query.app.as_deref())?;
  Ok(Json(runs))
}

pub async fn get_run(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Option<Run>>, crate::error::AppError> {
  let run = state.repository.get_run(&run_id)?;
  Ok(Json(run))
}

pub async fn clear_all(
  State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, crate::error::AppError> {
  state.repository.clear_all()?;
  Ok(Json(serde_json::json!({ "cleared": true })))
}
