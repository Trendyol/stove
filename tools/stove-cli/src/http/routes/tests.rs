use axum::Json;
use axum::extract::{Path, State};

use crate::http::server::AppState;
use crate::storage::models::{Entry, MockInteraction, MockWarning, Snapshot, Test};

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

pub async fn get_test_interactions(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<MockInteraction>>, crate::error::AppError> {
  let interactions = state
    .repository
    .get_mock_interactions_for_test(&run_id, &test_id)?;
  Ok(Json(interactions))
}

pub async fn get_run_interactions(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockInteraction>>, crate::error::AppError> {
  let interactions = state.repository.get_mock_interactions_for_run(&run_id)?;
  Ok(Json(interactions))
}

pub async fn get_unattributed_run_interactions(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockInteraction>>, crate::error::AppError> {
  let interactions = state
    .repository
    .get_unattributed_mock_interactions_for_run(&run_id)?;
  Ok(Json(interactions))
}

pub async fn get_test_warnings(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
) -> Result<Json<Vec<MockWarning>>, crate::error::AppError> {
  let warnings = state
    .repository
    .get_mock_warnings_for_test(&run_id, &test_id)?;
  Ok(Json(warnings))
}

pub async fn get_run_warnings(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockWarning>>, crate::error::AppError> {
  let warnings = state.repository.get_mock_warnings_for_run(&run_id)?;
  Ok(Json(warnings))
}

pub async fn get_unattributed_run_warnings(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Vec<MockWarning>>, crate::error::AppError> {
  let warnings = state
    .repository
    .get_unattributed_mock_warnings_for_run(&run_id)?;
  Ok(Json(warnings))
}
