use axum::Json;
use axum::extract::{Path, Query, State};
use serde::Deserialize;

use crate::http::server::AppState;
use crate::storage::models::{Entry, LogQuery, LogRecord, Snapshot, Test};

#[derive(Debug, Deserialize, Default)]
pub struct LogsQueryParams {
  level: Option<String>,
  logger: Option<String>,
  thread: Option<String>,
  q: Option<String>,
  scope: Option<String>,
  cursor: Option<i64>,
  limit: Option<usize>,
}

impl From<LogsQueryParams> for LogQuery {
  fn from(value: LogsQueryParams) -> Self {
    Self {
      level: value.level,
      min_severity: None,
      logger: value.logger,
      thread: value.thread,
      q: value.q,
      scope: value.scope,
      cursor: value.cursor,
      limit: value.limit.unwrap_or(500).clamp(1, 2_000),
    }
  }
}

pub async fn get_tests(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<Test>>, crate::error::AppError> {
  let tests = state.repository.get_tests_for_run(&run_id)?;
  Ok(Json(tests))
}

pub async fn get_entries(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<Entry>>, crate::error::AppError> {
  let entries = state.repository.get_entries(&run_id, &test_id)?;
  Ok(Json(entries))
}

pub async fn get_snapshots(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<Snapshot>>, crate::error::AppError> {
  let snapshots = state.repository.get_snapshots(&run_id, &test_id)?;
  Ok(Json(snapshots))
}

pub async fn get_test_spans(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<crate::storage::models::Span>>, crate::error::AppError> {
  let spans = state.repository.get_spans_for_test(&run_id, &test_id)?;
  Ok(Json(spans))
}

pub async fn get_test_logs(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
  Query(query): Query<LogsQueryParams>,
) -> Result<Json<Vec<LogRecord>>, crate::error::AppError> {
  let logs = state
    .repository
    .get_logs_for_test(&run_id, &test_id, &query.into())?;
  Ok(Json(logs))
}

pub async fn get_run_logs(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
  Query(query): Query<LogsQueryParams>,
) -> Result<Json<Vec<LogRecord>>, crate::error::AppError> {
  let logs = state.repository.get_logs_for_run(&run_id, &query.into())?;
  Ok(Json(logs))
}
