use crate::storage::repository::pagination::{Collection, PageQuery};
use axum::Json;
use axum::extract::{Path, Query, State};

use crate::error::AppError;
use crate::http::server::AppState;
use crate::storage::models::MockInteraction;

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/mock-interactions",
  tag = "mocks",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier"),
    PageQuery
  ),
  responses((status = 200, description = "Mock interactions attributed to the test", body = Collection<MockInteraction>))
)]
pub async fn get_test_mock_interactions(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<MockInteraction>>, AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .mock_interactions_page(run_id, Some(test_id), false, query)
        .await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  Ok(Json(Collection::Legacy(
    state
      .repository
      .read_async(move |repository| repository.get_mock_interactions_for_test(&run_id, &test_id))
      .await?,
  )))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/mock-interactions",
  tag = "mocks",
  params(("run_id" = String, Path, description = "Run identifier"), PageQuery),
  responses((status = 200, description = "All mock interactions in the run", body = Collection<MockInteraction>))
)]
pub async fn get_run_mock_interactions(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<MockInteraction>>, AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .mock_interactions_page(run_id, None, false, query)
        .await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  Ok(Json(Collection::Legacy(
    state
      .repository
      .read_async(move |repository| repository.get_mock_interactions_for_run(&run_id))
      .await?,
  )))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/mock-interactions/ambient",
  tag = "mocks",
  params(("run_id" = String, Path, description = "Run identifier"), PageQuery),
  responses((status = 200, description = "Mock interactions not attributed to a test", body = Collection<MockInteraction>))
)]
pub async fn get_unattributed_run_mock_interactions(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<MockInteraction>>, AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .mock_interactions_page(run_id, None, true, query)
        .await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  Ok(Json(Collection::Legacy(
    state
      .repository
      .read_async(move |repository| repository.get_unattributed_mock_interactions_for_run(&run_id))
      .await?,
  )))
}
