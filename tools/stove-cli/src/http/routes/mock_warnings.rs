use axum::Json;
use axum::extract::{Path, State};

use crate::error::AppError;
use crate::http::server::AppState;
use crate::storage::models::MockWarning;

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/mock-warnings",
  tag = "mocks",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier")
  ),
  responses((status = 200, description = "Mock diagnostics attributed to the test", body = [MockWarning]))
)]
pub async fn get_test_mock_warnings(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<MockWarning>>, AppError> {
  Ok(Json(
    state
      .repository
      .get_mock_warnings_for_test(&run_id, &test_id)?,
  ))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/mock-warnings",
  tag = "mocks",
  params(("run_id" = String, Path, description = "Run identifier")),
  responses((status = 200, description = "All mock diagnostics in the run", body = [MockWarning]))
)]
pub async fn get_run_mock_warnings(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockWarning>>, AppError> {
  Ok(Json(state.repository.get_mock_warnings_for_run(&run_id)?))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/mock-warnings/ambient",
  tag = "mocks",
  params(("run_id" = String, Path, description = "Run identifier")),
  responses((status = 200, description = "Mock diagnostics not attributed to a test", body = [MockWarning]))
)]
pub async fn get_unattributed_run_mock_warnings(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockWarning>>, AppError> {
  Ok(Json(
    state
      .repository
      .get_unattributed_mock_warnings_for_run(&run_id)?,
  ))
}
