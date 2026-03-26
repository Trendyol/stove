use axum::Json;
use axum::extract::{Path, State};

use crate::http::server::AppState;
use crate::storage::models::Span;

pub async fn get_trace(
  State(state): State<AppState>,
  Path(trace_id): Path<String>,
) -> Result<Json<Vec<Span>>, crate::error::AppError> {
  let spans = state.repository.get_trace(&trace_id)?;
  Ok(Json(spans))
}
