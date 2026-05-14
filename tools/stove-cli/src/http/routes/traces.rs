use axum::Json;
use axum::extract::{Path, Query, State};

use super::LogsQueryParams;
use crate::http::server::AppState;
use crate::storage::models::Span;

pub async fn get_trace(
  State(state): State<AppState>,
  Path(trace_id): Path<String>,
) -> Result<Json<Vec<Span>>, crate::error::AppError> {
  let spans = state.repository.get_trace(&trace_id)?;
  Ok(Json(spans))
}

pub async fn get_trace_logs(
  State(state): State<AppState>,
  Path(trace_id): Path<String>,
  Query(query): Query<LogsQueryParams>,
) -> Result<Json<Vec<crate::storage::models::LogRecord>>, crate::error::AppError> {
  let logs = state
    .repository
    .get_logs_for_trace(&trace_id, &query.into())?;
  Ok(Json(logs))
}
