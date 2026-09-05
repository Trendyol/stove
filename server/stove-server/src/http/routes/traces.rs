use crate::storage::repository::pagination::{Collection, PageQuery};
use axum::Json;
use axum::extract::{Path, Query, State};

use crate::http::server::AppState;
use crate::storage::models::Span;

#[utoipa::path(
  get,
  path = "/api/v1/traces/{trace_id}",
  tag = "evidence",
  params(("trace_id" = String, Path, description = "Trace identifier"), PageQuery),
  responses((status = 200, description = "All spans in the trace", body = Collection<Span>))
)]
pub async fn get_trace(
  State(state): State<AppState>,
  Path(trace_id): Path<String>,
  Query(query): Query<PageQuery>,
) -> Result<Json<Collection<Span>>, crate::error::AppError> {
  if query.page {
    return Ok(Json(Collection::Page(
      state
        .repository
        .spans_page(String::new(), trace_id, true, query)
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
    .read_async(move |repository| repository.get_trace(&trace_id))
    .await?;
  Ok(Json(Collection::Legacy(spans)))
}
