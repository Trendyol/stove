use crate::storage::repository::{TestFilter, TestOutcome, TestPage};
use axum::Json;
use axum::extract::{Path, Query, State};
use serde::Deserialize;
use utoipa::IntoParams;

use crate::http::server::AppState;
use crate::storage::models::{Entry, Snapshot};

#[derive(Deserialize, IntoParams)]
pub struct TestsQuery {
  pub limit: Option<usize>,
  pub cursor: Option<String>,
  #[serde(default)]
  pub outcome: TestOutcome,
  #[serde(default)]
  pub search: String,
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests",
  tag = "evidence",
  params(("run_id" = String, Path, description = "Run identifier"), TestsQuery),
  responses((status = 200, description = "Page of tests in the run", body = TestPage))
)]
pub async fn get_tests(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
  Query(query): Query<TestsQuery>,
) -> Result<Json<TestPage>, crate::error::AppError> {
  tokio::task::spawn_blocking(move || {
    state
      .repository
      .get_test_page(
        &TestFilter {
          run_id,
          outcome: query.outcome,
          search: query.search,
        },
        query.limit.unwrap_or(50),
        query.cursor.as_deref(),
      )
      .map(Json)
  })
  .await
  .map_err(|error| crate::error::AppError::Startup(format!("test listing task failed: {error}")))?
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
