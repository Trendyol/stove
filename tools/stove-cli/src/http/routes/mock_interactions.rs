use axum::Json;
use axum::extract::{Path, State};

use crate::error::AppError;
use crate::http::server::AppState;
use crate::storage::models::MockInteraction;

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

pub async fn get_run_mock_interactions(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockInteraction>>, AppError> {
  Ok(Json(
    state.repository.get_mock_interactions_for_run(&run_id)?,
  ))
}

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
