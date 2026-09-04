use axum::Json;
use axum::extract::{Path, State};

use crate::error::AppError;
use crate::http::server::AppState;
use crate::storage::models::MockInteraction;

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/mock-interactions",
  tag = "mocks",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier")
  ),
  responses((status = 200, description = "Mock interactions attributed to the test", body = [MockInteraction]))
)]
pub async fn get_test_mock_interactions(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<MockInteraction>>, AppError> {
  Ok(Json(
    state
      .repository
      .get_mock_interactions_for_test(&run_id, &test_id)?,
  ))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/mock-interactions",
  tag = "mocks",
  params(("run_id" = String, Path, description = "Run identifier")),
  responses((status = 200, description = "All mock interactions in the run", body = [MockInteraction]))
)]
pub async fn get_run_mock_interactions(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockInteraction>>, AppError> {
  Ok(Json(
    state.repository.get_mock_interactions_for_run(&run_id)?,
  ))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/mock-interactions/ambient",
  tag = "mocks",
  params(("run_id" = String, Path, description = "Run identifier")),
  responses((status = 200, description = "Mock interactions not attributed to a test", body = [MockInteraction]))
)]
pub async fn get_unattributed_run_mock_interactions(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockInteraction>>, AppError> {
  Ok(Json(
    state
      .repository
      .get_unattributed_mock_interactions_for_run(&run_id)?,
  ))
}
