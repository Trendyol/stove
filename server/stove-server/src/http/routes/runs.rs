use axum::Json;
use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Response};
use serde::Deserialize;
use std::collections::BTreeMap;
use utoipa::IntoParams;

use crate::error::AppError;
use crate::http::server::AppState;
use crate::storage::models::{AppSummary, Run};
use crate::storage::repository::{
  DEFAULT_RUN_PAGE_SIZE, MetadataSelectors, RunFilter, RunMetadataFacet, RunPage,
};

#[derive(Deserialize, IntoParams)]
#[into_params(parameter_in = Query)]
pub struct RunsQuery {
  pub app: Option<String>,
  /// JSON object of metadata selectors; values may be strings or arrays (OR within a key).
  pub metadata: Option<String>,
  /// Maximum runs in a page (1–100, default 50).
  pub limit: Option<usize>,
  /// Opaque cursor returned by the preceding page, with the same filters.
  pub cursor: Option<String>,
  /// Optional run status: RUNNING, PASSED, or FAILED.
  pub status: Option<String>,
}

#[derive(Deserialize, IntoParams)]
#[into_params(parameter_in = Query)]
pub struct RunFacetsQuery {
  pub app: Option<String>,
  /// JSON object of exact metadata values limiting the facet scope.
  pub metadata: Option<String>,
}

#[utoipa::path(
  get, path = "/api/v1/runs/metadata", tag = "runs", params(RunFacetsQuery),
  responses((status = 200, description = "Metadata values and counts across matching runs", body = [RunMetadataFacet]), (status = 400, description = "Invalid metadata filter"))
)]
pub async fn get_run_metadata_facets(
  State(state): State<AppState>,
  Query(query): Query<RunFacetsQuery>,
) -> Result<Json<Vec<RunMetadataFacet>>, AppError> {
  let scope = query
    .metadata
    .as_deref()
    .map(serde_json::from_str::<BTreeMap<String, String>>)
    .transpose()
    .map_err(|_| {
      AppError::InvalidEvent("metadata must be a JSON object with string values".into())
    })?
    .unwrap_or_default();
  let facets = tokio::task::spawn_blocking(move || {
    state
      .repository
      .get_run_metadata_facets(query.app.as_deref(), &scope)
  })
  .await
  .map_err(|error| AppError::Startup(format!("metadata facets task failed: {error}")))??;
  Ok(Json(facets))
}

#[utoipa::path(
  get,
  path = "/api/v1/apps",
  tag = "runs",
  responses((status = 200, description = "Known applications", body = [AppSummary]))
)]
pub async fn get_apps(
  State(state): State<AppState>,
) -> Result<Json<Vec<AppSummary>>, crate::error::AppError> {
  let apps = state.repository.get_apps()?;
  Ok(Json(apps))
}

#[utoipa::path(
  get,
  path = "/api/v1/runs",
  tag = "runs",
  params(RunsQuery),
  responses(
    (status = 200, description = "Page of runs matching the filters", body = RunPage),
    (status = 400, description = "Invalid metadata filter")
  )
)]
pub async fn get_runs(
  State(state): State<AppState>,
  Query(query): Query<RunsQuery>,
) -> Result<Response, crate::error::AppError> {
  let metadata = query
    .metadata
    .as_deref()
    .map(serde_json::from_str::<MetadataSelectors>)
    .transpose()
    .map_err(|error| {
      AppError::InvalidEvent(format!(
        "metadata must be a JSON object with string or string-array values: {error}"
      ))
    })?
    .unwrap_or_default();
  let filter = RunFilter {
    app: query.app,
    status: query
      .status
      .as_deref()
      .map(str::parse)
      .transpose()
      .map_err(AppError::InvalidEvent)?,
    metadata,
  };
  tokio::task::spawn_blocking(move || {
    let page = state.repository.get_run_page(
      &filter,
      query.limit.unwrap_or(DEFAULT_RUN_PAGE_SIZE),
      query.cursor.as_deref(),
    )?;
    Ok(Json(page).into_response())
  })
  .await
  .map_err(|error| AppError::Startup(format!("run listing task failed: {error}")))?
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
  let run = state.repository.get_run(&run_id)?;
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
