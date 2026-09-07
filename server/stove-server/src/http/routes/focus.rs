use crate::{
  error::AppError,
  focus::{self, EvidenceKind, FocusedEvidence},
  http::server::AppState,
  storage::models::Test,
};
use axum::{
  Json,
  extract::{Path, Query, State},
};
use serde::Deserialize;

#[derive(Deserialize)]
pub struct FocusQuery {
  #[serde(default = "default_context")]
  context: usize,
}
fn default_context() -> usize {
  10
}

#[utoipa::path(get, path = "/api/v1/runs/{run_id}/tests/{test_id}", tag = "evidence",
  params(("run_id" = String, Path), ("test_id" = String, Path)),
  responses((status = 200, body = Test), (status = 404, description = "Test unavailable")))]
pub async fn get_test(
  State(state): State<AppState>,
  Path((run, test)): Path<(String, String)>,
) -> Result<Json<Test>, AppError> {
  state
    .repository
    .get_test(&run, &test)?
    .map(Json)
    .ok_or_else(|| AppError::NotFound("Test is unavailable in this run".into()))
}

#[utoipa::path(get, path = "/api/v1/runs/{run_id}/tests/{test_id}/evidence/{kind}/{id}", tag = "evidence",
  params(("run_id" = String, Path), ("test_id" = String, Path), ("kind" = EvidenceKind, Path), ("id" = i64, Path), ("context" = Option<usize>, Query, description = "Surrounding records, default 10, maximum 100")),
  responses((status = 200, body = FocusedEvidence), (status = 404, description = "Evidence unavailable in this scope")))]
pub async fn get_focused_evidence(
  State(state): State<AppState>,
  Path((run, test, kind, id)): Path<(String, String, EvidenceKind, i64)>,
  Query(query): Query<FocusQuery>,
) -> Result<Json<FocusedEvidence>, AppError> {
  focus::resolve(
    &state.repository,
    &run,
    Some(&test),
    kind,
    id,
    query.context,
  )
  .map(Json)
}

#[utoipa::path(get, path = "/api/v1/runs/{run_id}/evidence/{kind}/{id}", tag = "evidence",
  params(("run_id" = String, Path), ("kind" = EvidenceKind, Path), ("id" = i64, Path), ("context" = Option<usize>, Query)),
  responses((status = 200, body = FocusedEvidence), (status = 404, description = "Run evidence unavailable")))]
pub async fn get_run_focused_evidence(
  State(state): State<AppState>,
  Path((run, kind, id)): Path<(String, EvidenceKind, i64)>,
  Query(query): Query<FocusQuery>,
) -> Result<Json<FocusedEvidence>, AppError> {
  focus::resolve(&state.repository, &run, None, kind, id, query.context).map(Json)
}
