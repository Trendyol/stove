use std::sync::Arc;

use axum::Router;
use axum::routing::{delete, get, post, put};
use tower_http::cors::CorsLayer;

use crate::ingest::EventIngestor;
use crate::sse::manager::SseManager;
use crate::storage::repository::Repository;

/// Shared application state passed to all HTTP handlers.
#[derive(Clone)]
pub struct AppState {
  pub repository: Arc<Repository>,
  pub sse_manager: Arc<SseManager>,
  pub(crate) ingestor: EventIngestor,
}

/// Create the axum router with all API routes, SSE, and embedded SPA.
pub fn create_router(repository: Arc<Repository>, sse_manager: Arc<SseManager>) -> Router {
  let state = AppState {
    ingestor: EventIngestor::new(repository.clone(), sse_manager.clone()),
    repository,
    sse_manager,
  };

  Router::new()
    .route(
      "/mcp",
      get(crate::mcp::handle_get).post(crate::mcp::handle_post),
    )
    .nest(
      "/api/v1",
      run_routes().merge(mock_routes()).merge(admin_routes()),
    )
    .merge(super::openapi::router())
    .fallback(super::routes::static_handler)
    .layer(CorsLayer::permissive())
    .with_state(state)
}

fn run_routes() -> Router<AppState> {
  Router::new()
    .route("/meta", get(super::routes::get_meta))
    .route("/apps", get(super::routes::get_apps))
    .route("/events", post(super::routes::post_event))
    .route("/runs", get(super::routes::get_runs))
    .route("/runs/{run_id}", get(super::routes::get_run))
    .route("/runs/{run_id}/tests", get(super::routes::get_tests))
    .route(
      "/runs/{run_id}/tests/{test_id}/entries",
      get(super::routes::get_entries),
    )
    .route(
      "/runs/{run_id}/tests/{test_id}/entries/raw",
      get(super::routes::get_raw_entries),
    )
    .route(
      "/runs/{run_id}/tests/{test_id}/spans",
      get(super::routes::get_test_spans),
    )
    .route(
      "/runs/{run_id}/tests/{test_id}/snapshots",
      get(super::routes::get_snapshots),
    )
    .route("/traces/{trace_id}", get(super::routes::get_trace))
    .route("/events/stream", get(super::routes::sse_handler))
    .route("/data", delete(super::routes::clear_all))
}

fn mock_routes() -> Router<AppState> {
  Router::new()
    .route(
      "/runs/{run_id}/tests/{test_id}/mock-interactions",
      get(super::routes::get_test_mock_interactions),
    )
    .route(
      "/runs/{run_id}/tests/{test_id}/mock-warnings",
      get(super::routes::get_test_mock_warnings),
    )
    .route(
      "/runs/{run_id}/mock-interactions",
      get(super::routes::get_run_mock_interactions),
    )
    .route(
      "/runs/{run_id}/mock-interactions/ambient",
      get(super::routes::get_unattributed_run_mock_interactions),
    )
    .route(
      "/runs/{run_id}/mock-warnings",
      get(super::routes::get_run_mock_warnings),
    )
    .route(
      "/runs/{run_id}/mock-warnings/ambient",
      get(super::routes::get_unattributed_run_mock_warnings),
    )
}

fn admin_routes() -> Router<AppState> {
  Router::new()
    .route("/admin/status", get(super::routes::get_admin_status))
    .route(
      "/admin/database/schema",
      get(super::routes::get_database_schema),
    )
    .route(
      "/admin/database/query",
      post(super::routes::execute_database_query),
    )
    .route("/admin/retention", put(super::routes::update_retention))
    .route("/admin/purge/preview", post(super::routes::preview_purge))
    .route("/admin/purge", post(super::routes::purge_runs))
}
