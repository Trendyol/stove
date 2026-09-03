use axum::Json;
use axum::extract::{Path, State};

use crate::http::server::AppState;
use crate::storage::models::{Entry, Snapshot, Test};

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests",
  tag = "evidence",
  params(("run_id" = String, Path, description = "Run identifier")),
  responses((status = 200, description = "Tests in the run", body = [Test]))
)]
pub async fn get_tests(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<Test>>, crate::error::AppError> {
  let tests = state.repository.get_tests_for_run(&run_id)?;
  Ok(Json(tests))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/entries",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier")
  ),
  responses((status = 200, description = "Collapsed report entries", body = [Entry]))
)]
pub async fn get_entries(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<Entry>>, crate::error::AppError> {
  let entries = state.repository.get_entries(&run_id, &test_id)?;
  Ok(Json(entries))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/entries/raw",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier")
  ),
  responses((status = 200, description = "Uncollapsed report entries", body = [Entry]))
)]
pub async fn get_raw_entries(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<Entry>>, crate::error::AppError> {
  let entries = state.repository.get_raw_entries(&run_id, &test_id)?;
  Ok(Json(entries))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/snapshots",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier")
  ),
  responses((status = 200, description = "System snapshots for the test", body = [Snapshot]))
)]
pub async fn get_snapshots(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<Snapshot>>, crate::error::AppError> {
  let snapshots = state.repository.get_snapshots(&run_id, &test_id)?;
  Ok(Json(snapshots))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/spans",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier")
  ),
  responses((status = 200, description = "Trace spans for the test", body = [crate::storage::models::Span]))
)]
pub async fn get_test_spans(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<crate::storage::models::Span>>, crate::error::AppError> {
  let spans = state.repository.get_spans_for_test(&run_id, &test_id)?;
  Ok(Json(spans))
}
