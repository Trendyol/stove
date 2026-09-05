use crate::storage::repository::pagination::{Collection, PageQuery, SnapshotCollection};
use axum::Json;
use axum::extract::{Path, Query, State};

use crate::http::server::AppState;
use crate::storage::models::{Entry, Snapshot, Test};

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests",
  tag = "evidence",
  params(("run_id" = String, Path, description = "Run identifier"), PageQuery),
  responses((status = 200, description = "Tests in the run", body = Collection<Test>))
)]
pub async fn get_tests(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<Test>>, crate::error::AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state.repository.tests_page(run_id, query).await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(crate::error::AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  let tests = state
    .repository
    .read_async(move |repository| repository.get_tests_for_run(&run_id))
    .await?;
  Ok(Json(Collection::Legacy(tests)))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/entries",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier"),
    PageQuery
  ),
  responses((status = 200, description = "Collapsed report entries", body = Collection<Entry>))
)]
pub async fn get_entries(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<Entry>>, crate::error::AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .entries_page(run_id, test_id, false, query)
        .await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(crate::error::AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  let entries = state
    .repository
    .read_async(move |repository| repository.get_entries(&run_id, &test_id))
    .await?;
  Ok(Json(Collection::Legacy(entries)))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/entries/raw",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier"),
    PageQuery
  ),
  responses((status = 200, description = "Uncollapsed report entries", body = Collection<Entry>))
)]
pub async fn get_raw_entries(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<Entry>>, crate::error::AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .entries_page(run_id, test_id, true, query)
        .await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(crate::error::AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  let entries = state
    .repository
    .read_async(move |repository| repository.get_raw_entries(&run_id, &test_id))
    .await?;
  Ok(Json(Collection::Legacy(entries)))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/snapshots",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier"),
    PageQuery
  ),
  responses((status = 200, description = "System snapshots for the test", body = SnapshotCollection))
)]
pub async fn get_snapshots(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
  Query(query): Query<PageQuery>,
) -> Result<Json<SnapshotCollection>, crate::error::AppError> {
  if query.page {
    return Ok(Json(SnapshotCollection::Page(
      state
        .repository
        .snapshots_page(run_id, test_id, query)
        .await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(crate::error::AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  let snapshots = state
    .repository
    .read_async(move |repository| repository.get_snapshots(&run_id, &test_id))
    .await?;
  Ok(Json(SnapshotCollection::Legacy(snapshots)))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/spans",
  tag = "evidence",
  params(
    ("run_id" = String, Path, description = "Run identifier"),
    ("test_id" = String, Path, description = "Test identifier"),
    PageQuery
  ),
  responses((status = 200, description = "Trace spans for the test", body = Collection<crate::storage::models::Span>))
)]
pub async fn get_test_spans(
  State(state): State<AppState>,
  Path((run_id, test_id)): Path<(String, String)>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<crate::storage::models::Span>>, crate::error::AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .spans_page(run_id, test_id, false, query)
        .await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(crate::error::AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  let spans = state
    .repository
    .read_async(move |repository| repository.get_spans_for_test(&run_id, &test_id))
    .await?;
  Ok(Json(Collection::Legacy(spans)))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}/tests/{test_id}/snapshots/{snapshot_id}",
  tag = "evidence",
  params(("run_id" = String, Path), ("test_id" = String, Path), ("snapshot_id" = i64, Path)),
  responses((status = 200, description = "Snapshot body, or null when unavailable", body = Option<Snapshot>))
)]
pub async fn get_snapshot(
  State(state): State<AppState>,
  Path((run_id, test_id, snapshot_id)): Path<(String, String, i64)>,
) -> Result<Json<Option<Snapshot>>, crate::error::AppError> {
  Ok(Json(
    state
      .repository
      .snapshot_detail(run_id, test_id, snapshot_id)
      .await?,
  ))
}
