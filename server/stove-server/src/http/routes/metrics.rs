use crate::http::server::AppState;
use axum::{extract::State, http::header};

/// Cached Prometheus exposition; deliberately performs no database calls.
#[utoipa::path(get, path = "/metrics", tag = "system", responses((status = 200, description = "Prometheus database metrics", content_type = "text/plain", body = String)))]
pub async fn get_metrics(
  State(state): State<AppState>,
) -> ([(header::HeaderName, &'static str); 2], String) {
  (
    [
      (header::CONTENT_TYPE, prometheus::TEXT_FORMAT),
      (header::CACHE_CONTROL, "no-store"),
    ],
    state.repository.metrics.prometheus(),
  )
}
