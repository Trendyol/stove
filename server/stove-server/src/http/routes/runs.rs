use axum::Json;
use axum::extract::{Path, Query, State};
use serde::Deserialize;
use std::collections::BTreeMap;
use utoipa::IntoParams;

use crate::error::AppError;
use crate::http::server::AppState;
use crate::storage::models::{AppSummary, Run};
use crate::storage::repository::pagination::{Collection, PageQuery};

#[derive(Deserialize, IntoParams)]
#[into_params(parameter_in = Query)]
pub struct RunsQuery {
  pub app: Option<String>,
  /// URL-encoded JSON object containing an exact metadata subset to match.
  pub metadata: Option<String>,
}

#[utoipa::path(
  get,
  path = "/api/v1/apps",
  tag = "runs",
  params(PageQuery),
  responses((status = 200, description = "Known applications", body = Collection<AppSummary>))
)]
pub async fn get_apps(
  State(state): State<AppState>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<AppSummary>>, crate::error::AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state.repository.apps_page(query).await?,
    )));
  }
  if query.cursor.is_some() || query.limit.is_some() || query.search.is_some() {
    return Err(AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  let apps = state
    .repository
    .read_async(move |repository| repository.get_apps())
    .await?;
  Ok(Json(Collection::Legacy(apps)))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs",
  tag = "runs",
  params(RunsQuery, PageQuery),
  responses(
    (status = 200, description = "Runs matching the filters", body = Collection<Run>),
    (status = 400, description = "Invalid metadata filter")
  )
)]
pub async fn get_runs(
  State(state): State<AppState>,
  Query(query): Query<RunsQuery>,
  Query(page): Query<PageQuery>,
) -> Result<Json<Collection<Run>>, crate::error::AppError> {
  let metadata = query
    .metadata
    .as_deref()
    .map(serde_json::from_str::<BTreeMap<String, String>>)
    .transpose()
    .map_err(|error| {
      AppError::InvalidEvent(format!(
        "metadata must be a JSON object with string values: {error}"
      ))
    })?
    .unwrap_or_default();
  if page.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .runs_page(query.app, metadata, page)
        .await?,
    )));
  }
  if page.cursor.is_some() || page.limit.is_some() || page.search.is_some() {
    return Err(AppError::InvalidEvent(
      "pagination and search require page=true".into(),
    ));
  }
  let runs = state
    .repository
    .read_async(move |repository| repository.get_runs_filtered(query.app.as_deref(), &metadata))
    .await?;
  Ok(Json(Collection::Legacy(runs)))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs/{run_id}",
  tag = "runs",
  params(("run_id" = String, Path, description = "Run identifier")),
  responses((status = 200, description = "Run, or null when it does not exist", body = Option<Run>))
)]
pub async fn get_run(
  State(state): State<AppState>,
  Path(run_id): Path<String>,
) -> Result<Json<Option<Run>>, crate::error::AppError> {
  let run = state
    .repository
    .read_async(move |repository| repository.get_run(&run_id))
    .await?;
  Ok(Json(run))
}

#[utoipa::path(
  delete,
  path = "/api/v1/data",
  tag = "admin",
  responses((status = 200, description = "All dashboard data was cleared"))
)]
pub async fn clear_all(
  State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, crate::error::AppError> {
  state.repository.clear_all()?;
  Ok(Json(serde_json::json!({ "cleared": true })))
}
