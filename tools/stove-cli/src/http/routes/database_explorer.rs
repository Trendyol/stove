use axum::Json;
use axum::extract::State;
use serde::Deserialize;

use crate::error::{AppError, Result};
use crate::http::server::AppState;
use crate::storage::models::{DatabaseQueryResult, DatabaseSchema};

const DEFAULT_MAX_ROWS: usize = 100;
const MAX_ROWS: usize = 500;
const MAX_SQL_BYTES: usize = 64 * 1024;

#[derive(Debug, Deserialize)]
pub struct DatabaseQueryRequest {
  pub sql: String,
  #[serde(default = "default_max_rows")]
  pub max_rows: usize,
}

pub async fn get_database_schema(State(state): State<AppState>) -> Result<Json<DatabaseSchema>> {
  Ok(Json(state.repository.database_schema()?))
}

pub async fn execute_database_query(
  State(state): State<AppState>,
  Json(request): Json<DatabaseQueryRequest>,
) -> Result<Json<DatabaseQueryResult>> {
  let sql = request.sql.trim();
  if sql.is_empty() {
    return Err(AppError::InvalidEvent(
      "database query must not be empty".to_string(),
    ));
  }
  if sql.len() > MAX_SQL_BYTES {
    return Err(AppError::InvalidEvent(format!(
      "database query exceeds the {MAX_SQL_BYTES}-byte limit"
    )));
  }

  let max_rows = request.max_rows.clamp(1, MAX_ROWS);
  state
    .repository
    .execute_database_query(sql, max_rows)
    .map(Json)
    .map_err(|error| AppError::InvalidEvent(format!("database query failed: {error}")))
}

const fn default_max_rows() -> usize {
  DEFAULT_MAX_ROWS
}
